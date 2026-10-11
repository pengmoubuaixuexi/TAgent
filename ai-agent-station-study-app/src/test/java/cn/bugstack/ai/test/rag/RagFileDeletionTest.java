package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.RagFileDeletionService;
import cn.bugstack.ai.infrastructure.dao.IAiClientRagOrderDao;
import cn.bugstack.ai.infrastructure.dao.IAiParentDocumentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientRagOrder;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real orchestration and mapper compilation with doubles; no database is contacted. */
public class RagFileDeletionTest {
    private IAiClientRagOrderDao orders;
    private IAiParentDocumentDao parents;
    private PgVectorStore vectors;
    private BM25SearchService keywords;
    private PlatformTransactionManager transactions;
    private TransactionStatus transaction;
    private RagFileDeletionService service;
    private JdbcTemplate vectorJdbc;

    @Before public void setup() {
        orders = mock(IAiClientRagOrderDao.class); parents = mock(IAiParentDocumentDao.class);
        vectors = mock(PgVectorStore.class); keywords = mock(BM25SearchService.class);
        transactions = mock(PlatformTransactionManager.class); transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        vectorJdbc = mock(JdbcTemplate.class);
        service = new RagFileDeletionService(orders, parents, vectors, keywords, new TransactionTemplate(transactions), vectorJdbc, "vector_store_openai");
        when(orders.lockById(1L)).thenReturn(order("rf_fixture", "alice"));
        when(orders.deleteOwned(any())).thenReturn(1);
    }

    @Test public void allStoresAreDeletedBeforeDirectoryCommitUsingTheStoredOwner() {
        assertTrue(service.deleteById(1L));
        var sequence = inOrder(keywords, vectors, parents, orders, transactions);
        sequence.verify(orders).lockById(1L);
        sequence.verify(keywords).deleteFile(any());
        sequence.verify(vectors).delete(any(Filter.Expression.class));
        sequence.verify(parents).deleteByFileScope(any());
        sequence.verify(orders).deleteOwned(any());
        sequence.verify(transactions).commit(transaction);
        ArgumentCaptor<RagFileScope> scope = ArgumentCaptor.forClass(RagFileScope.class);
        verify(keywords).deleteFile(scope.capture());
        assertEquals("alice", scope.getValue().userId());
        String filter = new PgVectorFilterExpressionConverter().convertExpression(scope.getValue().vectorFilter());
        assertTrue(filter.contains("alice")); assertTrue(filter.contains("rag_file_id"));
        assertFalse(filter.contains("same.txt"));
    }

    @Test public void vectorFailureKeepsDirectoryAndRetryRepeatsIdempotentIndexCleanup() {
        doThrow(new IllegalStateException("pg unavailable")).doNothing().when(vectors).delete(any(Filter.Expression.class));
        RagStorageException error = assertThrows(RagStorageException.class, () -> service.deleteById(1L));
        assertTrue(error.getMessage().contains("向量索引"));
        assertTrue(error.getMessage().contains("目录已保留"));
        verify(orders, never()).deleteOwned(any()); verifyNoInteractions(parents);
        verify(transactions).rollback(transaction);
        assertTrue(service.deleteById(1L));
        verify(keywords, times(2)).deleteFile(any());
        verify(orders, times(1)).deleteOwned(any());
        verify(transactions).commit(transaction);
    }

    @Test public void parentFailureRollsBackMysqlAndDoesNotDeleteDirectory() {
        doThrow(new IllegalStateException("mysql unavailable")).when(parents).deleteByFileScope(any());
        assertThrows(RagStorageException.class, () -> service.deleteById(1L));
        verify(orders, never()).deleteOwned(any());
        verify(transactions).rollback(transaction);
    }

    @Test public void anonymousAndAmbiguousLegacyFilesFailBeforeAnyDestructiveCall() {
        when(orders.lockById(1L)).thenReturn(order("old-id", null));
        assertThrows(RagStorageException.class, () -> service.deleteById(1L));
        when(orders.lockById(1L)).thenReturn(order("old-id", "alice"));
        when(orders.countLegacyFileCandidates(any())).thenReturn(2);
        assertThrows(RagStorageException.class, () -> service.deleteById(1L));
        verifyNoInteractions(vectors, keywords, parents);
        verify(orders, never()).deleteOwned(any());
    }

    @Test public void uniqueLegacyScopeExcludesNewFilesAndOtherOwners() {
        when(orders.lockById(1L)).thenReturn(order("old-id", "alice"));
        when(orders.countLegacyFileCandidates(any())).thenReturn(1);
        assertTrue(service.deleteById(1L));
        verify(vectorJdbc).update(contains("metadata->>'rag_file_id' IS NULL"), eq("alice"), eq("kb"), eq("same.txt"));
        verifyNoInteractions(vectors);
    }

    @Test public void mapperScopesAreParameterizedAndDirectoryReadsIncludeOwnership() throws Exception {
        Configuration configuration = new Configuration();
        for (String mapper : new String[]{"ai_parent_document_mapper.xml", "ai_client_rag_order_mapper.xml"}) {
            String resource = "mybatis/mapper/" + mapper;
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream);
                new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var scope = new RagFileScope("rf_fixture", "alice", "kb", "same.txt", false);
        String prefix = "cn.bugstack.ai.infrastructure.dao.";
        var parentSql = configuration.getMappedStatement(prefix + "IAiParentDocumentDao.deleteByFileScope")
                .getBoundSql(Map.of("scope", scope));
        assertTrue(parentSql.getSql().contains("user_id = ?"));
        assertTrue(parentSql.getSql().contains("knowledge_tag = ?"));
        assertTrue(parentSql.getSql().contains("LEFT(parent_id"));
        assertFalse(parentSql.getSql().contains("rf_fixture"));
        assertTrue(configuration.getMappedStatement(prefix + "IAiClientRagOrderDao.queryById")
                .getBoundSql(1L).getSql().contains("user_id"));
    }

    private AiClientRagOrder order(String id, String owner) {
        return AiClientRagOrder.builder().id(1L).ragId(id).userId(owner).knowledgeTag("kb").ragName("same.txt").build();
    }
}
