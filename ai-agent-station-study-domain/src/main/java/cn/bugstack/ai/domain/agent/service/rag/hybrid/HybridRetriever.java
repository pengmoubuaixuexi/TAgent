package cn.bugstack.ai.domain.agent.service.rag.hybrid;

import jakarta.annotation.Resource;
import jakarta.annotation.PreDestroy;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
public class HybridRetriever implements AutoCloseable {

    private static final int RRF_K = 60;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private BM25SearchService bm25SearchService;

    @Value("${agent.rag.retrieval-timeout-ms:5000}")
    private long retrievalTimeoutMs = 5000;

    @Value("${agent.rag.retrieval-workers:4}")
    private int retrievalWorkers = 4;

    @Value("${agent.rag.retrieval-queue-capacity:64}")
    private int retrievalQueueCapacity = 64;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private volatile ExecutorService retrievalExecutor;
    private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

    /** Explicitly distinguishes an empty search from a backend that did not answer. */
    public record RetrievalResult(List<Document> documents, Map<String, String> branches, long latencyMs) {
        public boolean degraded() { return branches.values().stream().anyMatch(state -> !"success".equals(state)); }
        public boolean available() { return branches.containsValue("success"); }
    }

    private record BranchResult(String name, List<Document> documents, String state) { }

    public List<Document> retrieve(String query, SearchRequest vectorSearchRequest, String knowledgeTag, int topK) {
        RetrievalResult result = retrieveDetailed(query, vectorSearchRequest, knowledgeTag, topK);
        if (!result.available()) {
            throw new IllegalStateException("知识库检索暂不可用，请稍后重试（向量和关键词索引均未返回有效结果）。");
        }
        return result.documents();
    }

    public RetrievalResult retrieveDetailed(String query, SearchRequest vectorSearchRequest, String knowledgeTag, int topK) {
        if (vectorSearchRequest == null || topK < 1) throw new IllegalArgumentException("Search request and positive topK required");
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(Math.max(1, Math.min(retrievalTimeoutMs, 120_000)));
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.current();
        StreamingActivityTracker.checkActive(activity);
        ContextSnapshot context = SNAPSHOTS.captureAll();
        CompletionService<BranchResult> completed = new ExecutorCompletionService<>(executor());
        Map<String, Future<BranchResult>> pending = new LinkedHashMap<>();
        Map<String, String> states = new LinkedHashMap<>();
        Map<String, List<Document>> documents = new LinkedHashMap<>();
        try {
            submit(completed, pending, states, "vector", context, activity, () ->
                    vectorStore.similaritySearch(SearchRequest.from(vectorSearchRequest).query(query).build()));
            try {
                Map<String, String> filters = extractEqualityFilters(vectorSearchRequest.getFilterExpression());
                submit(completed, pending, states, "bm25", context, activity,
                        () -> bm25SearchService.search(query, knowledgeTag, filters, topK));
            } catch (IllegalArgumentException unsupportedFilter) {
                // Never weaken OR/IN/NOT or contradictory filters merely to make BM25 work.
                states.put("bm25", "unsupported_filter");
            }
            while (states.size() < 2) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("RAG retrieval interrupted");
                StreamingActivityTracker.checkActive(activity);
                long remaining = deadline - System.nanoTime();
                Future<BranchResult> next = remaining <= 0 ? completed.poll()
                        : completed.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
                if (next != null) {
                    BranchResult branch = next.get();
                    states.put(branch.name(), branch.state());
                    documents.put(branch.name(), branch.documents());
                } else if (remaining <= 0) {
                    break;
                }
            }
            for (String backend : List.of("vector", "bm25")) states.putIfAbsent(backend, "timeout");
            List<Document> fused = rrfFuse(List.of(documents.getOrDefault("vector", List.of()),
                    documents.getOrDefault("bm25", List.of())), topK);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            states.forEach((backend, state) -> {
                if (meterRegistry != null) meterRegistry.counter("agent.rag.retrieval.branch",
                        "backend", backend, "outcome", state).increment();
            });
            log.info("Hybrid retrieval completed branches={} fused={} latencyMs={}", states, fused.size(), elapsed);
            return new RetrievalResult(List.copyOf(fused), Map.copyOf(states), elapsed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("RAG retrieval interrupted");
        } catch (ExecutionException error) {
            StreamingActivityTracker.rethrowCancellation(error);
            throw new IllegalStateException("RAG worker failed", error.getCause());
        } finally {
            pending.values().forEach(future -> { if (!future.isDone()) future.cancel(true); });
        }
    }

    private void submit(CompletionService<BranchResult> completed, Map<String, Future<BranchResult>> pending,
                        Map<String, String> states, String backend, ContextSnapshot context,
                        StreamingActivityTracker.Activity activity, Callable<List<Document>> search) {
        try {
            pending.put(backend, completed.submit(() -> {
                try (var ignored = context.setThreadLocals(); var scope = StreamingActivityTracker.scope(activity)) {
                    StreamingActivityTracker.checkActive(activity);
                    List<Document> found = search.call();
                    return new BranchResult(backend, found == null ? List.of() : found, "success");
                } catch (Exception error) {
                    StreamingActivityTracker.rethrowCancellation(error);
                    log.warn("RAG backend {} failed: {}", backend, error.getClass().getSimpleName());
                    return new BranchResult(backend, List.of(), "failed");
                }
            }));
        } catch (RejectedExecutionException saturated) {
            states.put(backend, "rejected");
        }
    }

    private synchronized ExecutorService executor() {
        if (retrievalExecutor == null) {
            AtomicInteger sequence = new AtomicInteger();
            int workers = Math.max(1, Math.min(retrievalWorkers, 32));
            retrievalExecutor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(Math.max(1, Math.min(retrievalQueueCapacity, 1024))), runnable -> {
                        Thread thread = new Thread(runnable, "rag-retrieval-" + sequence.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        }
        return retrievalExecutor;
    }

    @Override @PreDestroy public synchronized void close() {
        if (retrievalExecutor != null) retrievalExecutor.shutdownNow();
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
     * 仅接受 AND + EQ；不能等价表达的条件必须拒绝，避免放宽用户或知识库过滤范围。
     */
    public static Map<String, String> extractEqualityFilters(Filter.Expression expr) {
        if (expr == null) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        collectEq(expr, out);
        return out;
    }

    private static void collectEq(Filter.Operand operand, Map<String, String> out) {
        if (operand instanceof Filter.Group group) {
            collectEq(group.content(), out);
            return;
        }
        if (operand instanceof Filter.Expression e) {
            switch (e.type()) {
                case AND -> {
                    collectEq(e.left(), out);
                    collectEq(e.right(), out);
                }
                case EQ -> {
                    if (e.left() instanceof Filter.Key k && e.right() instanceof Filter.Value v) {
                        Object val = v.value();
                        if (val == null) throw new IllegalArgumentException("Null filter unsupported by BM25");
                        String prior = out.putIfAbsent(k.key(), String.valueOf(val));
                        if (prior != null && !prior.equals(String.valueOf(val))) {
                            throw new IllegalArgumentException("Contradictory filters unsupported by BM25");
                        }
                    } else {
                        throw new IllegalArgumentException("Unsupported equality filter");
                    }
                }
                default -> {
                    throw new IllegalArgumentException("Unsupported BM25 filter: " + e.type());
                }
            }
        } else throw new IllegalArgumentException("Unsupported BM25 filter operand");
    }
}
