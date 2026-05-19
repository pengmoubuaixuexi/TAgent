package cn.bugstack.ai.domain.agent.service.rag.hybrid;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合检索器：并行触发 PgVector 语义召回 + ES BM25 关键词召回，用 RRF（Reciprocal Rank Fusion）融合排序。
 * <p>
 * RRF 公式：score(d) = Σ 1 / (k + rank_i(d))，k 经验值 60。
 * 不依赖两路打分的绝对值量纲（向量相似度 vs BM25 tf-idf 分，量纲不可比），只依赖各路的排名，
 * 简单鲁棒；对"某一路召回完全为空"也天然降级。
 * <p>
 * 下游会把 top-K 送给 {@link IRerankService} 做细粒度重排。
 */
@Slf4j
@Service
public class HybridRetriever {

    private static final int RRF_K = 60;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private BM25SearchService bm25SearchService;

    public List<Document> retrieve(String query, SearchRequest vectorSearchRequest, String knowledgeTag, int topK) {
        long start = System.currentTimeMillis();

        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                SearchRequest req = SearchRequest.from(vectorSearchRequest).query(query).build();
                return vectorStore.similaritySearch(req);
            } catch (Exception e) {
                log.warn("Vector similarity search failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
        // 把 SearchRequest 的 filterExpression 里的等值条件平铺给 BM25——保持两路 filter 行为一致，
        // 否则 PgVector 端按 user_id 过滤、BM25 端却跨用户召回，融合后多租户隔离破洞。
        Map<String, String> bm25ExtraFilters = extractEqualityFilters(
                vectorSearchRequest != null ? vectorSearchRequest.getFilterExpression() : null);
        // knowledgeTag 已单独传 BM25，bm25ExtraFilters 里若也有 knowledge 让它跟 knowledgeTag 重复也无害（term 等值幂等）
        CompletableFuture<List<Document>> bm25Future = CompletableFuture.supplyAsync(
                () -> bm25SearchService.search(query, knowledgeTag, bm25ExtraFilters, topK));

        List<Document> vectorDocs = vectorFuture.join();
        List<Document> bm25Docs = bm25Future.join();

        List<Document> fused = rrfFuse(List.of(vectorDocs, bm25Docs), topK);

        long latency = System.currentTimeMillis() - start;
        MDC.put("retrieval", "hybrid");
        MDC.put("vectorHits", String.valueOf(vectorDocs.size()));
        MDC.put("bm25Hits", String.valueOf(bm25Docs.size()));
        MDC.put("fusedHits", String.valueOf(fused.size()));
        MDC.put("latencyMs", String.valueOf(latency));
        try {
            log.info("Hybrid retrieval done: vector={} bm25={} fused={} latencyMs={}",
                    vectorDocs.size(), bm25Docs.size(), fused.size(), latency);
        } finally {
            MDC.remove("retrieval");
            MDC.remove("vectorHits");
            MDC.remove("bm25Hits");
            MDC.remove("fusedHits");
            MDC.remove("latencyMs");
        }
        return fused;
    }

    private List<Document> rrfFuse(List<List<Document>> rankedLists, int topK) {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();
        for (List<Document> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                Document d = list.get(rank);
                String key = dedupKey(d);
                scoreMap.merge(key, 1.0 / (RRF_K + rank + 1), Double::sum);
                docMap.putIfAbsent(key, d);
            }
        }
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .collect(Collectors.toList());
    }

    private String dedupKey(Document d) {
        // ES 用原 id；向量库可能生成独立 id，文本相同但 id 不同 → 退化到以 content 去重
        if (d.getId() != null && !d.getId().isBlank()) return "id:" + d.getId();
        return "txt:" + Integer.toHexString(Objects.hashCode(d.getText()));
    }

    /**
     * 从 Spring AI 的 {@link Filter.Expression} 树里抽出所有 EQ 等值条件 → flat map。
     * 给 BM25 端做 term filter；忽略非 EQ 节点（IN / GT / LT 等 BM25 这边不支持，不做错误降级）。
     */
    public static Map<String, String> extractEqualityFilters(Filter.Expression expr) {
        if (expr == null) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        collectEq(expr, out);
        return out;
    }

    private static void collectEq(Filter.Operand operand, Map<String, String> out) {
        if (operand instanceof Filter.Expression e) {
            switch (e.type()) {
                case AND, OR -> {
                    collectEq(e.left(), out);
                    collectEq(e.right(), out);
                }
                case EQ -> {
                    if (e.left() instanceof Filter.Key k && e.right() instanceof Filter.Value v) {
                        Object val = v.value();
                        if (val != null) out.put(k.key(), String.valueOf(val));
                    }
                }
                default -> {
                    // 其它操作符（NE / GT / GTE / LT / LTE / IN / NIN / NOT）BM25 这边不展开，
                    // 让 PgVector 一侧承载即可
                }
            }
        }
    }
}
