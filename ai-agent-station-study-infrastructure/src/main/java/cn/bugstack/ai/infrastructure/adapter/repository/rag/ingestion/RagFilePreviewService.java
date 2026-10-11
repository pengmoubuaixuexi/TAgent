package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Bounded, owner-scoped stored chunk previews. Never performs an embedding/model request. */
@Service
public class RagFilePreviewService {
    private final RagIngestionRepository files;
    private final JdbcTemplate mysql;
    private final JdbcTemplate vectors;
    private final String table;
    public RagFilePreviewService(RagIngestionRepository files, @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
                                @Qualifier("pgVectorJdbcTemplate") JdbcTemplate vectors,
                                @Value("${agent.rag.vector-table:${spring.ai.vectorstore.pgvector.table-name:vector_store}}") String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid vector table");
        this.files = files; this.mysql = mysql; this.vectors = vectors; this.table = "\"public\".\"" + table + "\"";
    }

    public Map<String, Object> preview(String owner, String fileId, int page) {
        if (owner == null || owner.isBlank() || page < 0 || page > 1000) throw new RagStorageException("预览参数无效。");
        Map<String, Object> file = files.ownedFile(owner, fileId);
        RagIngestionTask task = null;
        if (file == null) task = files.forFile(owner, fileId);
        if (file == null && task == null) throw new RagStorageException("文件不存在或无权访问。");
        String tag = file != null ? String.valueOf(file.get("knowledgeTag")) : task.knowledgeTag();
        String name = file != null ? String.valueOf(file.get("fileName")) : task.fileName();
        boolean legacy = !fileId.startsWith(RagFileScope.FILE_ID_PREFIX);
        String where = " WHERE metadata->>'user_id'=? AND metadata->>'knowledge'=?";
        Object locator;
        if (legacy) {
            Integer count = mysql.queryForObject("SELECT COUNT(*) FROM ai_client_rag_order WHERE user_id=? AND knowledge_tag=? AND rag_name=? AND LEFT(rag_id,3)<>'rf_'", Integer.class, owner, tag, name);
            if (count == null || count != 1) throw new RagStorageException("旧文件存在同名记录，暂不能可靠区分其片段，请联系管理员核对。");
            where += " AND metadata->>'source'=? AND metadata->>'rag_file_id' IS NULL"; locator = name;
        } else {
            where += " AND metadata->>'rag_file_id'=?"; locator = fileId;
        }
        Long total = vectors.queryForObject("SELECT COUNT(*) FROM " + table + where, Long.class, owner, tag, locator);
        List<Map<String, Object>> chunks = vectors.queryForList("SELECT id::text AS id,LEFT(content,6000) AS content,"
                + "LENGTH(content)>6000 AS truncated,metadata->>'title' AS title,metadata->>'parent_id' AS parentId FROM "
                + table + where + " ORDER BY id LIMIT 10 OFFSET ?", owner, tag, locator, page * 10);
        return Map.of("fileName", name, "knowledgeTag", tag, "page", page, "pageSize", 10,
                "total", total == null ? 0L : total, "chunks", chunks);
    }
}
