package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.rag.HyDEService;
import cn.bugstack.ai.domain.agent.service.rag.LlmQueryDecomposer;
import cn.bugstack.ai.domain.agent.service.rag.fusion.RagFusionService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.LlmRerankService;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RagFusionCancellationTest {
    @Test public void allFailedVariantsRemainAnErrorWithoutSecondFallbackSearch() {
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("fixture unavailable"));
        RagFusionService service = new RagFusionService(mock(ChatClient.class), vectors);
        assertThrows(IllegalStateException.class,
                () -> service.fusedRetrieveWithVariants("original", List.of("variant"), 2));
        verify(vectors, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test public void commonDeadlineReturnsSuccessfulVariantsAndInterruptsUnfinishedWork() throws Exception {
        VectorStore vectors = mock(VectorStore.class);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (request.getQuery().equals("original")) return List.of(new Document("surviving result"));
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
            return List.of();
        });
        RagFusionService service = new RagFusionService(mock(ChatClient.class), vectors);
        ReflectionTestUtils.setField(service, "retrievalTimeoutMs", 150L);
        long start = System.nanoTime();
        assertEquals("surviving result", service.fusedRetrieveWithVariants("original", List.of("slow"), 2).get(0).getText());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test public void activityCancellationIsPropagatedToFusionWorkers() throws Exception {
        VectorStore vectors = mock(VectorStore.class);
        CountDownLatch entered = new CountDownLatch(2), interrupted = new CountDownLatch(2);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
            return List.of();
        });
        RagFusionService service = new RagFusionService(mock(ChatClient.class), vectors);
        var activity = StreamingActivityTracker.start("fusion fixture");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = caller.submit(() -> {
                try (var ignored = StreamingActivityTracker.scope(activity)) {
                    service.fusedRetrieveWithVariants("original", List.of("variant"), 2);
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            activity.cancel();
            ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof CancellationException);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); }
    }

    @Test public void runBudgetFailureSurvivesHybridFusionAndHelperFallbacks() {
        RunBudgetExceededException terminal = new RunBudgetExceededException("fixture token budget");
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(terminal);
        RagFusionService fusion = new RagFusionService(mock(ChatClient.class), vectors);
        assertSame(terminal, assertThrows(RunBudgetExceededException.class,
                () -> fusion.fusedRetrieveWithVariants("original", List.of("variant"), 2)));

        HybridRetriever hybrid = new HybridRetriever();
        ReflectionTestUtils.setField(hybrid, "vectorStore", vectors);
        ReflectionTestUtils.setField(hybrid, "bm25SearchService", mock(cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService.class));
        try {
            assertSame(terminal, assertThrows(RunBudgetExceededException.class,
                    () -> hybrid.retrieve("original", SearchRequest.builder().topK(2).build(), "kb", 2)));
        } finally { hybrid.close(); }

        ChatClient client = mock(ChatClient.class);
        when(client.prompt(anyString())).thenThrow(terminal);
        when(client.prompt(any(Prompt.class))).thenThrow(terminal);
        assertSame(terminal, assertThrows(RunBudgetExceededException.class,
                () -> new HyDEService(client).generateHypotheticalDocument("question")));
        assertSame(terminal, assertThrows(RunBudgetExceededException.class,
                () -> new LlmQueryDecomposer(client, 0).decompose("first? second?")));
        assertSame(terminal, assertThrows(RunBudgetExceededException.class,
                () -> new LlmRerankService().rerank(client, "question", List.of(new Document("one"), new Document("two")), 1)));
    }
}
