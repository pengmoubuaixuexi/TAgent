package cn.bugstack.ai.domain.agent.service.rag.fusion;

import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** All variants share one deadline. Blocking stores must not occupy the common pool. */
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private static final ExecutorService RETRIEVAL_WORKERS = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "rag-fusion-" + WORKER_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private long retrievalTimeoutMs = 15_000;
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
        this.variantCount = Math.max(1, Math.min(variantCount, 8));
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

        // Variant generation may fall back to the original query, but an unavailable retrieval
        // backend is not an empty knowledge base and must not start a second unbounded search.
        return doMultiQueryRetrieve(originalQuery, generateVariants(originalQuery), baseSearchRequest, topK);
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
        return doMultiQueryRetrieve(originalQuery, variants, baseSearchRequest, topK);
    }

    /** 多查询并行检索 + RRF 融合：originalQuery + variants 分别查，RRF 去重排序。
     *  baseSearchRequest 携带 RagAnswerAdvisor 已合并好的 filterExpression（knowledge + user_id） */
    private List<Document> doMultiQueryRetrieve(String originalQuery, List<String> variants,
                                                SearchRequest baseSearchRequest, int topK) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(originalQuery);
        variants.stream().filter(Objects::nonNull).filter(q -> !q.isBlank()).limit(variantCount).forEach(queries::add);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, retrievalTimeoutMs));
        var activity = StreamingActivityTracker.current();
        StreamingActivityTracker.checkActive(activity);
        var snapshot = SNAPSHOT_FACTORY.captureAll();
        CompletionService<List<Document>> completed = new ExecutorCompletionService<>(RETRIEVAL_WORKERS);
        List<Future<List<Document>>> pending = new ArrayList<>();
        List<List<Document>> results = new ArrayList<>();
        int processed = 0;
        try {
            for (String query : queries) {
                try {
                    pending.add(completed.submit(() -> {
                        try (var ignored = snapshot.setThreadLocals(); var scope = StreamingActivityTracker.scope(activity)) {
                            StreamingActivityTracker.checkActive(activity);
                            SearchRequest sr = buildSearchRequest(baseSearchRequest, query, topK);
                            return hybridRetriever != null ? hybridRetriever.retrieve(query, sr, knowledgeTag, topK)
                                    : vectorStore.similaritySearch(sr);
                        }
                    }));
                } catch (RejectedExecutionException saturated) {
                    log.warn("RAG Fusion retrieval queue full; variant rejected");
                }
            }
            while (processed < pending.size()) {
                StreamingActivityTracker.checkActive(activity);
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("RAG Fusion interrupted");
                long remaining = deadline - System.nanoTime();
                Future<List<Document>> next = remaining <= 0 ? completed.poll()
                        : completed.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
                if (next == null) {
                    if (remaining <= 0) break;
                    continue;
                }
                processed++;
                try {
                    List<Document> documents = next.get();
                    results.add(documents == null ? List.of() : documents);
                } catch (ExecutionException failed) {
                    StreamingActivityTracker.rethrowCancellation(failed);
                    log.warn("RAG Fusion retrieval variant failed: {}", failed.getCause().getClass().getSimpleName());
                }
            }
            if (results.isEmpty()) throw new IllegalStateException("知识库检索暂不可用，所有查询分支均失败或超时，请稍后重试。");
            return rrfFuse(results, topK);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("RAG Fusion interrupted");
        } finally {
            pending.forEach(future -> { if (!future.isDone()) future.cancel(true); });
        }
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
            StreamingActivityTracker.rethrowCancellation(e);
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
