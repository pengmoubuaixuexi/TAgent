package cn.bugstack.ai.domain.agent.service.rag.fusion;

import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LLM-based RAG Fusion：用 router-small 生成 N 个查询变体，并行检索，RRF 融合。
 */
@Slf4j
public class RagFusionService implements IRagFusionService {

    private static final String VARIANT_PROMPT = """
            Generate %d search queries that express the same information need in different ways.
            Return ONLY a JSON array of strings, no explanation.

            Original query: %s

            JSON array:""";

    private static final int DEFAULT_VARIANTS = 3;
    private static final double RRF_K = 60.0;
    /** 单变体检索超时；超过即返回空列表，不阻塞主请求 */
    private static final long PER_VARIANT_TIMEOUT_SEC = 15L;
    private static final io.micrometer.context.ContextSnapshotFactory SNAPSHOT_FACTORY =
            io.micrometer.context.ContextSnapshotFactory.builder().build();

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final int variantCount;
    private final boolean enabled;
    /** Claude 修复：补 Hybrid 接入，让每个变体也能享受 BM25 + 向量双引擎 */
    private final HybridRetriever hybridRetriever;
    private final String knowledgeTag;

    public RagFusionService(ChatClient chatClient, VectorStore vectorStore) {
        this(chatClient, vectorStore, DEFAULT_VARIANTS, chatClient != null, null, null);
    }

    public RagFusionService(ChatClient chatClient, VectorStore vectorStore, int variantCount, boolean enabled) {
        this(chatClient, vectorStore, variantCount, enabled, null, null);
    }

    public RagFusionService(ChatClient chatClient, VectorStore vectorStore,
                            HybridRetriever hybridRetriever, String knowledgeTag) {
        this(chatClient, vectorStore, DEFAULT_VARIANTS, chatClient != null, hybridRetriever, knowledgeTag);
    }

    public RagFusionService(ChatClient chatClient, VectorStore vectorStore, int variantCount, boolean enabled,
                            HybridRetriever hybridRetriever, String knowledgeTag) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.variantCount = variantCount;
        this.enabled = enabled;
        this.hybridRetriever = hybridRetriever;
        this.knowledgeTag = knowledgeTag;
    }

    @Override
    public boolean isAvailable() {
        return enabled && chatClient != null && vectorStore != null;
    }

    @Override
    public List<Document> fusedRetrieve(String originalQuery, int topK) {
        return fusedRetrieve(originalQuery, null, topK);
    }

    @Override
    public List<Document> fusedRetrieve(String originalQuery, SearchRequest baseSearchRequest, int topK) {
        if (!isAvailable()) return List.of();

        try {
            List<String> variants = generateVariants(originalQuery);
            if (variants.isEmpty()) {
                return singleQueryRetrieve(originalQuery, baseSearchRequest, topK);
            }
            return doMultiQueryRetrieve(originalQuery, variants, baseSearchRequest, topK);
        } catch (Exception e) {
            log.warn("RAG Fusion failed, falling back to single-query retrieval: {}", e.getMessage());
            try {
                return singleQueryRetrieve(originalQuery, baseSearchRequest, topK);
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    @Override
    public List<Document> fusedRetrieveWithVariants(String originalQuery, List<String> variants, int topK) {
        return fusedRetrieveWithVariants(originalQuery, variants, null, topK);
    }

    @Override
    public List<Document> fusedRetrieveWithVariants(String originalQuery, List<String> variants,
                                                    SearchRequest baseSearchRequest, int topK) {
        if (!isAvailable()) return List.of();
        if (variants == null || variants.isEmpty()) {
            return fusedRetrieve(originalQuery, baseSearchRequest, topK);
        }
        try {
            return doMultiQueryRetrieve(originalQuery, variants, baseSearchRequest, topK);
        } catch (Exception e) {
            log.warn("RAG Fusion with given variants failed: {}", e.getMessage());
            try {
                return singleQueryRetrieve(originalQuery, baseSearchRequest, topK);
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    /** 多查询并行检索 + RRF 融合：originalQuery + variants 分别查，RRF 去重排序。
     *  baseSearchRequest 携带 RagAnswerAdvisor 已合并好的 filterExpression（knowledge + user_id） */
    private List<Document> doMultiQueryRetrieve(String originalQuery, List<String> variants,
                                                SearchRequest baseSearchRequest, int topK) {
        List<String> allQueries = new ArrayList<>();
        allQueries.add(originalQuery);
        allQueries.addAll(variants);

        // 捕获主线程上下文（MDC + OTel span）让 worker 线程接力 trace；
        // 单变体超时 PER_VARIANT_TIMEOUT_SEC 秒，超时返回空列表不拖死主请求
        io.micrometer.context.ContextSnapshot snapshot;
        try { snapshot = SNAPSHOT_FACTORY.captureAll(); } catch (Throwable ignored) { snapshot = null; }
        final io.micrometer.context.ContextSnapshot finalSnapshot = snapshot;

        List<CompletableFuture<List<Document>>> futures = allQueries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> {
                    try (io.micrometer.context.ContextSnapshot.Scope ignored =
                                 finalSnapshot != null ? finalSnapshot.setThreadLocals() : null) {
                        SearchRequest sr = buildSearchRequest(baseSearchRequest, q, topK);
                        if (hybridRetriever != null) {
                            return hybridRetriever.retrieve(q, sr, knowledgeTag, topK);
                        }
                        return vectorStore.similaritySearch(sr);
                    } catch (Exception e) {
                        log.debug("RAG Fusion retrieve failed for variant: {}", e.getMessage());
                        return List.<Document>of();
                    }
                }).orTimeout(PER_VARIANT_TIMEOUT_SEC, TimeUnit.SECONDS)
                  .exceptionally(ex -> {
                      log.debug("RAG Fusion variant timed out or failed: {}", ex.getMessage());
                      return List.<Document>of();
                  }))
                .toList();

        List<List<Document>> allResults = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        return rrfFuse(allResults, topK);
    }

    private List<Document> singleQueryRetrieve(String query, SearchRequest baseSearchRequest, int topK) {
        return vectorStore.similaritySearch(buildSearchRequest(baseSearchRequest, query, topK));
    }

    /** 用 baseSearchRequest 模板继承 filter；baseSearchRequest 为 null 时构造新的（保持向后兼容）。 */
    private SearchRequest buildSearchRequest(SearchRequest baseSearchRequest, String query, int topK) {
        if (baseSearchRequest != null) {
            return SearchRequest.from(baseSearchRequest).query(query).topK(topK).build();
        }
        return SearchRequest.builder().query(query).topK(topK).build();
    }

    List<String> generateVariants(String query) {
        if (chatClient == null || query == null || query.isBlank()) return List.of();
        try {
            String prompt = String.format(VARIANT_PROMPT, variantCount, query);
            String result = chatClient.prompt(prompt).call().content();
            if (result == null || result.isBlank()) return List.of();
            return parseVariants(result);
        } catch (Exception e) {
            log.debug("RAG Fusion variant generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    List<String> parseVariants(String llmOutput) {
        try {
            String raw = llmOutput.trim();
            // 处理 ```json ... ``` 包裹
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();
            String jsonArray = raw.substring(start, end + 1);
            @SuppressWarnings("unchecked")
            List<String> list = com.alibaba.fastjson.JSON.parseObject(jsonArray, List.class);
            if (list == null) return List.of();
            return list.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("RAG Fusion parse error: {}", e.getMessage());
            return List.of();
        }
    }

    static List<Document> rrfFuse(List<List<Document>> resultLists, int topK) {
        if (resultLists == null || resultLists.isEmpty()) return List.of();

        // docId → accumulated RRF score
        Map<String, Double> rrfScores = new ConcurrentHashMap<>();
        Map<String, Document> docMap = new ConcurrentHashMap<>();

        for (List<Document> ranked : resultLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                Document doc = ranked.get(rank);
                String docId = doc.getId() != null ? doc.getId() : doc.getText();
                double score = 1.0 / (RRF_K + rank + 1);
                rrfScores.merge(docId, score, Double::sum);
                docMap.putIfAbsent(docId, doc);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
