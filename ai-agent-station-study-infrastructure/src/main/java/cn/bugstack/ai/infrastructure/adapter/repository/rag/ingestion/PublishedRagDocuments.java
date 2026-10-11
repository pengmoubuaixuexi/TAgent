package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.rag.RagDocumentVisibility;
import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** MySQL publication is authoritative; unavailable metadata fails closed instead of exposing partial writes. */
@Service
public class PublishedRagDocuments implements RagDocumentVisibility {
    private final JdbcTemplate db;
    public PublishedRagDocuments(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db) { this.db = db; }
    private record File(String owner, String tag, String locator, boolean legacy) { }
    private static String text(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
    private static File identity(Document doc) {
        String owner = text(doc, "user_id"), tag = text(doc, "knowledge"), id = text(doc, RagFileScope.FILE_ID_KEY);
        String source = text(doc, "source");
        return owner == null || tag == null || (id == null && source == null) ? null : new File(owner, tag, id == null ? source : id, id == null);
    }
    @Override public List<Document> published(List<Document> candidates) {
        try (var guard = new RagReadGuard()) {
            return published(candidates, guard);
        }
    }
    private List<Document> published(List<Document> candidates, RagReadGuard guard) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        String owner = guard.owner();
        List<File> identities = candidates.stream().map(PublishedRagDocuments::identity).filter(Objects::nonNull)
                .filter(file -> owner == null || owner.equals(file.owner)).distinct().toList();
        Set<File> allowed = new HashSet<>();
        for (int start = 0; start < identities.size(); start += 100) {
            guard.check();
            List<File> batch = identities.subList(start, Math.min(identities.size(), start + 100));
            List<Object> arguments = new ArrayList<>();
            StringJoiner conditions = new StringJoiner(" OR ");
            for (File file : batch) {
                conditions.add("(d.user_id=? AND d.knowledge_tag=? AND " + (file.legacy ? "d.rag_name=? AND LEFT(d.rag_id,3)<>'rf_'" : "d.rag_id=?") + ")");
                arguments.add(file.owner); arguments.add(file.tag); arguments.add(file.locator);
            }
            String query = "SELECT d.user_id,d.knowledge_tag,d.rag_id,d.rag_name FROM ai_client_rag_order d WHERE d.status=1 AND ("
                    + conditions + ") AND NOT EXISTS (SELECT 1 FROM ai_rag_ingestion_task t WHERE t.owner_user_id=d.user_id"
                    + " AND t.knowledge_tag=d.knowledge_tag AND t.rag_id=d.rag_id AND t.status NOT IN ('SUCCEEDED','DELETED'))";
            try {
                db.query(connection -> {
                    var statement = connection.prepareStatement(query);
                    try {
                        for (int i = 0; i < arguments.size(); i++) statement.setObject(i + 1, arguments.get(i));
                        guard.attach(statement);
                        return statement;
                    } catch (RuntimeException | java.sql.SQLException error) {
                        statement.close();
                        throw error;
                    }
                }, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    guard.check();
                    allowed.add(new File(row.getString("user_id"), row.getString("knowledge_tag"), row.getString("rag_id"), false));
                    if (!row.getString("rag_id").startsWith(RagFileScope.FILE_ID_PREFIX)) {
                        allowed.add(new File(row.getString("user_id"), row.getString("knowledge_tag"), row.getString("rag_name"), true));
                    }
                });
            } catch (RuntimeException error) {
                guard.check(); // Driver cancellation errors must remain terminal rather than become degraded retrieval.
                throw error;
            }
            guard.check();
        }
        guard.check();
        return candidates.stream().filter(doc -> allowed.contains(identity(doc))).toList();
    }
}
