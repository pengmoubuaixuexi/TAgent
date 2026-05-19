package cn.bugstack.ai.infrastructure.adapter.repository.cache;

import cn.bugstack.ai.domain.agent.service.rag.cache.ISemanticCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P2.4 13.1 Semantic Cache：用 PgVector cosine distance 做 query embedding 近邻搜索。
 * 命中的话跳过 LLM 调用直接返回历史答案。
 */
@Slf4j
@Service("semanticCacheService")
public class SemanticCacheService implements ISemanticCacheService {

    private final JdbcTemplate pgVectorJdbcTemplate;
    private final EmbeddingModel embeddingModel;

    public SemanticCacheService(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                EmbeddingModel embeddingModel) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String lookup(String query, double minSimilarity, int ttlMinutes) {
        try {
            float[] embedding = embeddingModel.embed(query);
            String vectorStr = "[" + java.util.Arrays.stream(toDoubleArray(embedding))
                    .mapToObj(d -> String.format("%.8f", d))
                    .collect(Collectors.joining(",")) + "]";

            // 相似度 + 时间窗口双重过滤：只命中 ttlMinutes 内的缓存
            String sql = """
                    SELECT content, metadata->>'response' AS response,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM semantic_cache
                    WHERE 1 - (embedding <=> ?::vector) > ?
                      AND created_at >= NOW() - (? || ' minutes')::INTERVAL
                    ORDER BY similarity DESC
                    LIMIT 1
                    """;

            List<Map<String, Object>> rows = pgVectorJdbcTemplate.queryForList(
                    sql, vectorStr, vectorStr, minSimilarity, String.valueOf(ttlMinutes));

            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String cachedQuery = (String) row.get("content");
                String cachedResponse = (String) row.get("response");
                double similarity = ((Number) row.get("similarity")).doubleValue();

                // touch last_accessed
                pgVectorJdbcTemplate.update(
                        "UPDATE semantic_cache SET last_accessed = ? WHERE content = ?",
                        Timestamp.from(Instant.now()), cachedQuery);

                log.info("[SemanticCache] HIT similarity={} ttl={}min query={}", String.format("%.4f", similarity), ttlMinutes,
                        query.length() > 60 ? query.substring(0, 60) + "..." : query);
                return cachedResponse;
            }
        } catch (Exception e) {
            log.debug("[SemanticCache] lookup failed, treating as miss: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public int deleteOlderThan(int ttlMinutes) {
        try {
            int deleted = pgVectorJdbcTemplate.update(
                    "DELETE FROM semantic_cache WHERE created_at < NOW() - (? || ' minutes')::INTERVAL",
                    String.valueOf(ttlMinutes));
            if (deleted > 0) {
                log.info("[SemanticCache] cleanup deleted={} records older than {}min", deleted, ttlMinutes);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("[SemanticCache] cleanup failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void store(String query, String response, String model) {
        try {
            float[] embedding = embeddingModel.embed(query);
            String vectorStr = "[" + java.util.Arrays.stream(toDoubleArray(embedding))
                    .mapToObj(d -> String.format("%.8f", d))
                    .collect(Collectors.joining(",")) + "]";

            String metadata = String.format(
                    "{\"response\": %s, \"model\": \"%s\", \"hit_count\": 0}",
                    escapeJson(response), model);

            pgVectorJdbcTemplate.update(
                    "INSERT INTO semantic_cache (content, metadata, embedding) VALUES (?, ?::jsonb, ?::vector)",
                    query, metadata, vectorStr);

            log.debug("[SemanticCache] stored query len={} model={}", query.length(), model);
        } catch (Exception e) {
            log.debug("[SemanticCache] store failed: {}", e.getMessage());
        }
    }

    private static double[] toDoubleArray(float[] f) {
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
