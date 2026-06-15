package cn.bugstack.ai.infrastructure.adapter.repository.cache;

import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态补工具的语义匹配——把每条工具的文本 embed 后存进 PgVector(mcp_tool_vector)，查询时按 cosine 相似度取 top-N。
 *
 * <p>纯词法 BM25 在"同义词错位(联网 vs 网络) + 多个'搜索X'工具"下结构性分不开；embedding 跨同义词、分领域。
 * 向量持久化在 PG(而非内存)：刷新目录时 {@link #syncAll} embed 一次写库，之后 app 启动/跑测试只查、不重算。
 * 复用系统已有的 {@code pgVectorJdbcTemplate} + {@link EmbeddingModel} + VECTOR(1024) 基建
 * (RAG / LTM / semantic_cache 同款，见 V027 / V047)。
 */
@Slf4j
@Service("toolVectorStore")
public class ToolVectorStore implements IToolVectorStore {

    private final JdbcTemplate pgVectorJdbcTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * 命中所需的最低 cosine 相似度，低于此分视为无关、不返回。{@code <=0} 表示不设下限只按 topN 取。
     * embedding 即便 top-N 也基本相关，这里只挡"全场没一个沾边"的离谱命中；设高会误杀跨同义词的正确命中。
     */
    private final double minSimilarity;

    /**
     * 相对阈值（gap）：top-N 候选里只保留与 top1 相差在此范围内的（默认 0.1）。{@code <=0} 关闭。
     * <p>绝对下限({@link #minSimilarity})挡"全场没一个沾边"；这个相对窗口挡"top1 明显更贴、后面凑数"——
     * 如 top1=0.69、第二名 0.58（差 0.11 > 0.1）就把凑数的第二名甩掉，只补真正贴题的；
     * 但若 top1=0.61、第二名 0.60（差 0.01）说明俩都贴题，都留。
     */
    private final double relativeGap;

    public ToolVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                           EmbeddingModel embeddingModel,
                           @Value("${agent.dynamic-tools.embedding.min-similarity:0.30}") double minSimilarity,
                           @Value("${agent.dynamic-tools.embedding.relative-gap:0.1}") double relativeGap) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.minSimilarity = minSimilarity;
        this.relativeGap = relativeGap;
    }

    @Override
    public void syncAll(List<AiMcpToolCatalogVO> tools) {
        // 空目录不动库：避免一次抖动读到空就把向量全删了(与 upsertCatalog 的"空不删"一致)
        if (tools == null || tools.isEmpty()) {
            log.info("[ToolVector] syncAll skipped: empty catalog");
            return;
        }
        try {
            // 1. 现有库内容：key -> content，用于跳过"内容没变"的工具(省 embed 调用)
            Map<String, String> existingContent = new HashMap<>();
            for (Map<String, Object> row : pgVectorJdbcTemplate.queryForList(
                    "SELECT mcp_id, tool_name, content FROM mcp_tool_vector")) {
                existingContent.put(key((String) row.get("mcp_id"), (String) row.get("tool_name")),
                        (String) row.get("content"));
            }

            // 2. 当前目录的全量 key（用于删除 stale）+ 找出内容变更/新增、需要重新 embed 的
            Set<String> currentKeys = new HashSet<>();
            List<AiMcpToolCatalogVO> toEmbed = new ArrayList<>();
            List<String> toEmbedTexts = new ArrayList<>();
            for (AiMcpToolCatalogVO tool : tools) {
                if (tool == null || tool.getMcpId() == null || tool.getToolName() == null) continue;
                String k = key(tool.getMcpId(), tool.getToolName());
                currentKeys.add(k);
                String content = contentText(tool);
                if (!content.equals(existingContent.get(k))) {
                    toEmbed.add(tool);
                    toEmbedTexts.add(content);
                }
            }

            // 3. 删除库里多余的(MCP 已不再上报、或目录被禁用)
            deleteStale(currentKeys);

            // 4. 只对新增/变更的批量 embed 并 upsert（内容没变的不重算）
            if (toEmbed.isEmpty()) {
                log.info("[ToolVector] syncAll: {} tools all unchanged, nothing to embed", tools.size());
                return;
            }
            List<float[]> vectors = embeddingModel.embed(toEmbedTexts);
            int upserted = 0;
            for (int i = 0; i < toEmbed.size(); i++) {
                AiMcpToolCatalogVO tool = toEmbed.get(i);
                upsertOne(tool, toEmbedTexts.get(i), toVectorLiteral(vectors.get(i)));
                upserted++;
            }
            log.info("[ToolVector] syncAll done: total={} embedded/upserted={} unchanged={}",
                    tools.size(), upserted, tools.size() - upserted);
        } catch (Exception e) {
            log.warn("[ToolVector] syncAll failed: {}", e.toString());
        }
    }

    @Override
    public List<AiMcpToolCatalogVO> search(String need, Set<String> excludeNames, int topN) {
        if (need == null || need.isBlank() || topN <= 0) {
            return List.of();
        }
        try {
            String vectorStr = toVectorLiteral(embeddingModel.embed(need));
            Set<String> exclude = excludeNames == null ? Set.of() : excludeNames;
            // 多取一些(topN + 排除数)再在内存里过滤排除项，保证过滤后仍有 topN
            int fetch = topN + exclude.size();
            String sql = """
                    SELECT mcp_id, tool_name, mcp_name, description_zh, intent_zh,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM mcp_tool_vector
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """;
            List<Map<String, Object>> rows = pgVectorJdbcTemplate.queryForList(sql, vectorStr, vectorStr, fetch);

            // 通过 排除 + 绝对下限 的候选（已按相似度降序），最多 topN 个；并记下各自相似度供相对阈值用
            List<AiMcpToolCatalogVO> survivors = new ArrayList<>();
            List<Double> sims = new ArrayList<>();
            List<String> diag = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String toolName = (String) row.get("tool_name");
                double sim = ((Number) row.get("similarity")).doubleValue();
                diag.add(String.format(java.util.Locale.ROOT, "%s=%.3f", toolName, sim));
                if (toolName != null && exclude.contains(toolName)) continue;
                if (minSimilarity > 0 && sim < minSimilarity) continue;
                survivors.add(AiMcpToolCatalogVO.builder()
                        .mcpId((String) row.get("mcp_id"))
                        .mcpName((String) row.get("mcp_name"))
                        .toolName(toolName)
                        .toolDescriptionZh((String) row.get("description_zh"))
                        .toolIntentZh((String) row.get("intent_zh"))
                        .build());
                sims.add(sim);
                if (survivors.size() >= topN) break;
            }

            // 相对阈值：只保留与 top1 相差在 relativeGap 内的。survivors 已降序，遇到第一个掉出窗口即截断。
            List<AiMcpToolCatalogVO> result = survivors;
            if (relativeGap > 0 && !survivors.isEmpty()) {
                double cutoff = sims.get(0) - relativeGap;
                result = new ArrayList<>();
                for (int i = 0; i < survivors.size(); i++) {
                    if (sims.get(i) >= cutoff) result.add(survivors.get(i));
                    else break;
                }
            }
            log.info("[ToolVector] need='{}' minSim={} gap={} candidates={} kept={}",
                    need, minSimilarity, relativeGap, diag, result.stream().map(AiMcpToolCatalogVO::getToolName).toList());
            return result;
        } catch (Exception e) {
            log.warn("[ToolVector] search failed, signaling fallback: {}", e.toString());
            return null; // null = 向量库出错 → 调用方回退 BM25
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Integer count = pgVectorJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM mcp_tool_vector WHERE embedding IS NOT NULL", Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[ToolVector] isAvailable probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** 删除库里 key 不在当前目录里的行（按 (mcp_id, tool_name) 行值 NOT IN）。 */
    private void deleteStale(Set<String> currentKeys) {
        List<Map<String, Object>> existing = pgVectorJdbcTemplate.queryForList(
                "SELECT mcp_id, tool_name FROM mcp_tool_vector");
        List<Object[]> stale = new ArrayList<>();
        for (Map<String, Object> row : existing) {
            String mcpId = (String) row.get("mcp_id");
            String toolName = (String) row.get("tool_name");
            if (!currentKeys.contains(key(mcpId, toolName))) {
                stale.add(new Object[]{mcpId, toolName});
            }
        }
        if (stale.isEmpty()) return;
        pgVectorJdbcTemplate.batchUpdate(
                "DELETE FROM mcp_tool_vector WHERE mcp_id = ? AND tool_name = ?", stale);
        log.info("[ToolVector] deleted {} stale tool vectors", stale.size());
    }

    private void upsertOne(AiMcpToolCatalogVO tool, String content, String vectorStr) {
        pgVectorJdbcTemplate.update("""
                INSERT INTO mcp_tool_vector
                    (mcp_id, tool_name, mcp_name, description_zh, intent_zh, content, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::vector, now())
                ON CONFLICT (mcp_id, tool_name) DO UPDATE SET
                    mcp_name = EXCLUDED.mcp_name,
                    description_zh = EXCLUDED.description_zh,
                    intent_zh = EXCLUDED.intent_zh,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    updated_at = now()
                """,
                tool.getMcpId(), tool.getToolName(), tool.getMcpName(),
                tool.getToolDescriptionZh(), tool.getToolIntentZh(), content, vectorStr);
    }

    /** 拿去 embed 的文本：中文用途 + doc2query 意图扩写 + 工具名 + mcp 名。 */
    private String contentText(AiMcpToolCatalogVO tool) {
        LinkedHashMap<String, String> parts = new LinkedHashMap<>();
        parts.put("zh", tool.getToolDescriptionZh());
        parts.put("intent", tool.getToolIntentZh());
        parts.put("name", tool.getToolName());
        parts.put("mcp", tool.getMcpName());
        StringBuilder sb = new StringBuilder();
        for (String v : parts.values()) {
            if (v != null && !v.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(v.trim());
            }
        }
        return sb.toString();
    }

    private static String key(String mcpId, String toolName) {
        return mcpId + "::" + toolName;
    }

    private static String toVectorLiteral(float[] embedding) {
        double[] d = new double[embedding.length];
        for (int i = 0; i < embedding.length; i++) d[i] = embedding[i];
        return "[" + Arrays.stream(d).mapToObj(v -> String.format(java.util.Locale.ROOT, "%.8f", v))
                .collect(Collectors.joining(",")) + "]";
    }
}
