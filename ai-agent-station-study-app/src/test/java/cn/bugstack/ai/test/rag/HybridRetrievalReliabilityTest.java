package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import org.elasticsearch.client.Cancellable;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Latches and storage doubles only: no real embedding, databases, or external requests. */
public class HybridRetrievalReliabilityTest {
    private HybridRetriever hybrid;
    private VectorStore vectors;
    private BM25SearchService keywords;

    @Before public void setup() {
        hybrid = new HybridRetriever(); vectors = mock(VectorStore.class); keywords = mock(BM25SearchService.class);
        ReflectionTestUtils.setField(hybrid, "vectorStore", vectors);
        ReflectionTestUtils.setField(hybrid, "bm25SearchService", keywords);
        ReflectionTestUtils.setField(hybrid, "retrievalTimeoutMs", 150L);
        when(keywords.search(anyString(), anyString(), anyMap(), anyInt())).thenReturn(List.of(document()));
    }
    @After public void close() { hybrid.close(); StreamingActivityTracker.clear(); }

    @Test public void fastBackendSurvivesPeerDeadlineAndSlowWorkerIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
            return List.of();
        });
        long started = System.nanoTime();
        var result = hybrid.retrieveDetailed("fixture", request(), "kb", 4);
        assertEquals(List.of(document().getId()), result.documents().stream().map(Document::getId).toList());
        assertEquals("timeout", result.branches().get("vector"));
        assertEquals("success", result.branches().get("bm25"));
        assertTrue(result.degraded()); assertTrue(result.available());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
        assertTrue("Timed-out work must receive interruption", interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test public void bothBackendsFailIsNotAnEmptyKnowledgeBase() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("vector fixture"));
        when(keywords.search(anyString(), anyString(), anyMap(), anyInt())).thenThrow(new IllegalStateException("es fixture"));
        var detail = hybrid.retrieveDetailed("fixture", request(), "kb", 4);
        assertFalse(detail.available());
        assertThrows(IllegalStateException.class, () -> hybrid.retrieve("fixture", request(), "kb", 4));
    }

    @Test public void genuinelyEmptySuccessfulBackendsStillReturnAnEmptyResult() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(keywords.search(anyString(), anyString(), anyMap(), anyInt())).thenReturn(List.of());
        var result = hybrid.retrieveDetailed("fixture", request(), "kb", 4);
        assertTrue(result.documents().isEmpty()); assertTrue(result.available()); assertFalse(result.degraded());
    }

    @Test public void unsupportedOrFilterCannotBeFlattenedAndWeakenedForBm25() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        SearchRequest query = SearchRequest.builder().query("fixture").topK(4)
                .filterExpression("user_id == 'alice' && (knowledge == 'one' || knowledge == 'two')").build();
        var result = hybrid.retrieveDetailed("fixture", query, "kb", 4);
        assertEquals("unsupported_filter", result.branches().get("bm25"));
        verifyNoInteractions(keywords);
        verify(vectors).similaritySearch(argThat((SearchRequest value) -> value.getFilterExpression().equals(query.getFilterExpression())));
    }

    @Test public void activityCancellationInterruptsBothWorkersInsteadOfReturningEmptyResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), interrupted = new CountDownLatch(2);
        org.mockito.stubbing.Answer<List<Document>> blocking = invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
            return List.of();
        };
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(blocking);
        when(keywords.search(anyString(), anyString(), anyMap(), anyInt())).thenAnswer(blocking);
        ReflectionTestUtils.setField(hybrid, "retrievalTimeoutMs", 5000L);
        var activity = StreamingActivityTracker.start("rag cancellation fixture");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = caller.submit(() -> {
                try (var ignored = StreamingActivityTracker.scope(activity)) {
                    hybrid.retrieve("fixture", request(), "kb", 4);
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); activity.cancel();
            ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof CancellationException);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); }
    }

    @Test public void interruptedKeywordWorkerCancelsItsPendingHttpRequest() throws Exception {
        RestClient client = mock(RestClient.class); Cancellable pending = mock(Cancellable.class);
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        when(client.performRequestAsync(any(), any())).thenAnswer(invocation -> { entered.countDown(); return pending; });
        doAnswer(invocation -> { cancelled.countDown(); return null; }).when(pending).cancel();
        var realKeywords = new BM25SearchService();
        ReflectionTestUtils.setField(realKeywords, "restClient", client);
        ReflectionTestUtils.setField(realKeywords, "ragIndex", "fixture");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = caller.submit(() -> realKeywords.search("fixture", "kb", Map.of("user_id", "alice"), 4));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); result.cancel(true);
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); }
    }

    private SearchRequest request() {
        return SearchRequest.builder().topK(4).filterExpression("user_id == 'alice' && knowledge == 'kb'").build();
    }
    private Document document() { return new Document("id-fixture", "safe fixture", Map.of("user_id", "alice")); }
}
