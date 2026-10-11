package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.vectorstore.filter.Filter;

/** Trusted directory identity, never built from a client-supplied storage filter. */
public record RagFileScope(String fileId, String userId, String knowledgeTag, String source, boolean legacy) {
    public static final String FILE_ID_KEY = "rag_file_id";
    public static final String FILE_ID_PREFIX = "rf_";

    public static String stableId(String owner, String knowledge, String hash) {
        return FILE_ID_PREFIX + java.util.UUID.nameUUIDFromBytes(
                (owner + "\u0000" + knowledge + "\u0000" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public RagFileScope {
        if (blank(fileId) || blank(userId) || blank(knowledgeTag) || blank(source)) {
            throw new RagStorageException("文件缺少可确认的归属信息，未删除任何资料；请先修复目录记录。");
        }
    }

    public Filter.Expression vectorFilter() {
        // PgVector's converter cannot express missing metadata keys; legacy cleanup uses parameterized SQL.
        if (legacy) throw new IllegalStateException("Legacy deletion requires the exact SQL metadata predicate");
        Filter.Expression document = eq(FILE_ID_KEY, fileId);
        return and(eq("user_id", userId), and(eq("knowledge", knowledgeTag),
                document));
    }

    private static Filter.Expression eq(String key, String value) {
        return new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key(key), new Filter.Value(value));
    }

    private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
        return new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
