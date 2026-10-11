package cn.bugstack.ai.infrastructure.adapter.repository.rag;

import cn.bugstack.ai.domain.agent.service.rag.IRagFileDeletionService;
import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.infrastructure.dao.IAiClientRagOrderDao;
import cn.bugstack.ai.infrastructure.dao.IAiParentDocumentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientRagOrder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

/**
 * Retryable deletion: external indexes first, MySQL parent + directory last in one transaction.
 * A backend failure retains the directory. Already completed external deletions are idempotent on retry;
 * they cannot be rolled back by a MySQL transaction, so failures explicitly report partial cleanup.
 */
@Service
public class RagFileDeletionService implements IRagFileDeletionService {
    private final IAiClientRagOrderDao orders;
    private final IAiParentDocumentDao parents;
    private final PgVectorStore vectors;
    private final BM25SearchService keywords;
    private final TransactionOperations transactions;
    private final JdbcTemplate vectorJdbc;
    private final String vectorTable;

    @Autowired
    public RagFileDeletionService(IAiClientRagOrderDao orders, IAiParentDocumentDao parents,
                                  @Qualifier("ragVectorStore") PgVectorStore vectors, BM25SearchService keywords,
                                  @Qualifier("mysqlDataSource") DataSource dataSource,
                                  @Qualifier("pgVectorJdbcTemplate") JdbcTemplate vectorJdbc,
                                  @Value("${agent.rag.vector-table:${spring.ai.vectorstore.pgvector.table-name:vector_store}}") String vectorTable) {
        this(orders, parents, vectors, keywords,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), vectorJdbc, vectorTable);
    }

    public RagFileDeletionService(IAiClientRagOrderDao orders, IAiParentDocumentDao parents,
                                  PgVectorStore vectors, BM25SearchService keywords,
                                  TransactionOperations transactions, JdbcTemplate vectorJdbc, String vectorTable) {
        if (vectorTable == null || !vectorTable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("RAG vector table must be a simple PostgreSQL identifier");
        }
        this.orders = orders;
        this.parents = parents;
        this.vectors = vectors;
        this.keywords = keywords;
        this.transactions = transactions;
        this.vectorJdbc = vectorJdbc;
        this.vectorTable = "\"public\".\"" + vectorTable + "\"";
    }

    @Override public boolean deleteById(Long id) {
        if (id == null) throw new RagStorageException("请选择要删除的知识库文件。");
        return delete(() -> orders.lockById(id));
    }

    @Override public boolean deleteByRagId(String ragId) {
        if (ragId == null || ragId.isBlank()) throw new RagStorageException("请选择要删除的知识库文件。");
        return delete(() -> orders.lockByRagId(ragId));
    }

    @Override public void deleteIngestionFile(RagFileScope scope) {
        if (scope.legacy()) {
            AiClientRagOrder stored = orders.queryByRagId(scope.fileId());
            if (stored == null || !scope.userId().equals(stored.getUserId()) || !scope.knowledgeTag().equals(stored.getKnowledgeTag())) {
                throw new RagStorageException("旧文件目录缺失或归属不一致，无法安全清理，请联系管理员核对。");
            }
            deleteByRagId(scope.fileId());
            return;
        }
        transactions.execute(status -> {
            AiClientRagOrder stored = orders.lockByRagId(scope.fileId());
            if (stored != null && (!scope.userId().equals(stored.getUserId()) || !scope.knowledgeTag().equals(stored.getKnowledgeTag()))) {
                throw new RagStorageException("文件归属不一致，未删除资料。");
            }
            try {
                keywords.deleteFile(scope);
                vectors.delete(scope.vectorFilter());
                parents.deleteByFileScope(scope);
                if (stored != null && orders.deleteOwned(scope) != 1) throw new IllegalStateException("Directory changed");
            } catch (Exception error) {
                throw new RagStorageException("文件清理未完成，任务及原件已保留；请恢复存储后重试删除。", error);
            }
            return null;
        });
    }

    private boolean delete(Supplier<AiClientRagOrder> load) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                AiClientRagOrder order = load.get();
                if (order == null) return false;
                RagFileScope scope = new RagFileScope(order.getRagId(), order.getUserId(),
                        order.getKnowledgeTag(), order.getRagName(),
                        !order.getRagId().startsWith(RagFileScope.FILE_ID_PREFIX));
                if (scope.legacy() && orders.countLegacyFileCandidates(scope) != 1) {
                    throw new RagStorageException("旧文件存在同名目录，无法安全区分片段；未删除资料，请先核对文件归属。");
                }
                String stage = "关键词索引";
                try {
                    keywords.deleteFile(scope);
                    stage = "向量索引";
                    if (scope.legacy()) {
                        vectorJdbc.update("DELETE FROM " + vectorTable
                                        + " WHERE metadata->>'user_id' = ? AND metadata->>'knowledge' = ?"
                                        + " AND metadata->>'source' = ? AND metadata->>'rag_file_id' IS NULL",
                                scope.userId(), scope.knowledgeTag(), scope.source());
                    } else {
                        vectors.delete(scope.vectorFilter());
                    }
                    stage = "原文及文件目录";
                    parents.deleteByFileScope(scope);
                    if (orders.deleteOwned(scope) != 1) {
                        throw new IllegalStateException("Directory identity changed during deletion");
                    }
                    return true;
                } catch (Exception error) {
                    throw new RagStorageException("删除未完成（" + stage
                            + "）：文件目录已保留，部分索引可能已清理；恢复存储后可重试。", error);
                }
            }));
        } catch (RagStorageException error) {
            throw error;
        } catch (Exception error) {
            throw new RagStorageException("删除未完成：无法提交文件目录变更，目录仍需核对；可恢复存储后重试。", error);
        }
    }
}
