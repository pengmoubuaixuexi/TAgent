package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class StreamingActivityTrackerTest {

    @Test
    public void decodedResponsesRenewOnlyTheirOwnAttemptWatchdog() throws Exception {
        StreamingActivityTracker.Activity active = StreamingActivityTracker.start("flow-step-1");
        StreamingActivityTracker.Activity stuck = StreamingActivityTracker.start("flow-step-2");
        ageLastActivity(active, Duration.ofSeconds(3));
        ageLastActivity(stuck, Duration.ofSeconds(3));
        var activeClock = (AtomicLong) org.springframework.test.util.ReflectionTestUtils.getField(active, "lastActivityNanos");
        var stuckClock = (AtomicLong) org.springframework.test.util.ReflectionTestUtils.getField(stuck, "lastActivityNanos");
        long originalStuckClock = stuckClock.get();
        // Assert the clock contract directly: periodic 10 ms signals racing a 50 ms timer
        // measure host scheduling/GC under load, not whether two attempts share activity.
        for (int response = 0; response < 8; response++) {
            long beforeResponse = System.nanoTime();
            active.markDecodedResponse();
            assertTrue("decoded output renews its own clock", activeClock.get() >= beforeResponse);
            assertEquals("another attempt's clock must remain untouched", originalStuckClock, stuckClock.get());
        }
        assertTrue(stuck.isIdle(Duration.ofMillis(40)));
        RuntimeException error = assertThrows(RuntimeException.class, () -> StreamingActivityTracker.timeoutOnInactivity(
                Flux.never(), stuck, Duration.ofMillis(40)).blockLast(Duration.ofSeconds(5)));
        assertTrue("the already-expired attempt must fail through the actual watchdog",
                hasCause(error, StreamingActivityTracker.WaitTimeoutException.class));
        assertTrue(stuck.isCancelled());
        assertFalse("a neighbouring timeout cannot cancel the active attempt", active.isCancelled());
    }

    @Test
    public void reasoningActivityRenewsIdleClock() throws Exception {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("reasoning-call");
        Thread.sleep(25);
        activity.markReasoning();
        Thread.sleep(25);
        assertTrue("reasoning must renew the watchdog", !activity.isIdle(Duration.ofMillis(40)));
    }

    @Test
    public void rawReasoningSseRenewsTheBoundAttempt() throws Exception {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("raw-reasoning-call");
        Thread.sleep(45);
        ReasoningContentFilter filter = new ReasoningContentFilter();
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/chat")).build();
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"still thinking\"}}]}\n\n";

        try (AutoCloseable ignored = StreamingActivityTracker.scope(activity)) {
            ClientResponse response = filter.filter(request, req -> Mono.just(
                    ClientResponse.create(HttpStatus.OK).body(sse).build())).block();
            assertTrue(response != null);
            response.bodyToMono(String.class).block();
        }

        assertTrue("raw reasoning_content must renew the same attempt", !activity.isIdle(Duration.ofMillis(40)));
    }

    @Test
    public void firstResponseHasItsOwnDeadlineAndMeaningfulOutputSwitchesToIdleGap() throws Exception {
        var activity = StreamingActivityTracker.start("first-response");
        StreamingActivityTracker.timeoutOnInactivity(Flux.never(), activity,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5));
        activity.beginModelRequest();
        ageLastActivity(activity, Duration.ofSeconds(3));
        ageFirstResponse(activity, Duration.ofSeconds(3));
        assertFalse("provider first response can be slower than inter-chunk idle", activity.isIdle(Duration.ofSeconds(1)));
        activity.markReasoning();
        ageLastActivity(activity, Duration.ofSeconds(2));
        assertTrue("after meaningful reasoning the regular idle guard applies", activity.isIdle(Duration.ofSeconds(1)));
        activity.beginModelRequest();
        assertFalse("recursive tool follow-up starts a fresh first-response phase", activity.isIdle(Duration.ofSeconds(1)));
        ageFirstResponse(activity, Duration.ofSeconds(6));
        assertTrue("first-response phase remains bounded", activity.isIdle(Duration.ofSeconds(1)));
    }

    @Test
    public void metadataHeartbeatDoesNotEndOrRenewFirstResponseWait() throws Exception {
        var activity = StreamingActivityTracker.start("provider-heartbeat");
        StreamingActivityTracker.timeoutOnInactivity(Flux.never(), activity,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5));
        var filter = new ReasoningContentFilter();
        var request = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/chat")).build();
        try (var ignored = StreamingActivityTracker.scope(activity)) {
            var response = filter.filter(request, req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                    .body(": PROCESSING\n\n: PROCESSING\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n").build())).block();
            assertNotNull(response);
            response.bodyToMono(String.class).block();
        }
        var phaseField = activity.getClass().getDeclaredField("firstResponseStartedNanos");
        phaseField.setAccessible(true);
        assertTrue("HTTP filter starts first-response wait and metadata cannot end it", phaseField.getLong(activity) != 0L);
        ageLastActivity(activity, Duration.ofSeconds(3));
        ageFirstResponse(activity, Duration.ofSeconds(3));
        assertFalse("empty frame cannot switch from first-response to shorter idle gap", activity.isIdle(Duration.ofSeconds(1)));
        ageFirstResponse(activity, Duration.ofSeconds(6));
        assertTrue("empty frame cannot keep a provider alive forever", activity.isIdle(Duration.ofSeconds(1)));
    }

    @Test
    public void toolExecutionSuspendsOnlyItsOwnModelIdleAndResetsGapOnCompletion() throws Exception {
        var activity = StreamingActivityTracker.start("tool-phase");
        var other = StreamingActivityTracker.start("parallel-step");
        try (var ignored = activity.executingTool("maps_search", Duration.ofMinutes(1))) {
            ageLastActivity(activity, Duration.ofSeconds(3));
            ageLastActivity(other, Duration.ofSeconds(3));
            assertFalse(activity.isIdle(Duration.ofSeconds(1)));
            assertTrue("another DAG step cannot borrow this tool phase", other.isIdle(Duration.ofSeconds(1)));
        }
        assertFalse("model receives a fresh idle gap after tool completion", activity.isIdle(Duration.ofSeconds(1)));
        activity.cancel();
        assertThrows(CancellationException.class, () -> activity.executingTool("late-tool"));
    }

    @Test
    public void expiredToolCannotBeMaskedByAnotherToolOrModelFrames() {
        var activity = StreamingActivityTracker.start("tool-deadline");
        try (var first = activity.executingTool("stuck-write", Duration.ofNanos(1));
             var second = activity.executingTool("other-tool", Duration.ofMinutes(1))) {
            activity.markDecodedResponse();
            assertTrue(activity.isIdle(Duration.ofMinutes(1)));
            RuntimeException error = assertThrows(RuntimeException.class, () ->
                    StreamingActivityTracker.timeoutOnInactivity(Flux.never(), activity,
                            Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1)).blockLast());
            assertTrue(hasCause(error, TimeoutException.class));
            assertTrue(error.getMessage().contains("stuck-write"));
            assertTrue(error.getMessage().contains("未自动重复执行"));
            assertTrue(activity.isCancelled());
        }
    }

    @Test
    public void meteredCallbackDeclaresToolPhaseAcrossActualDelegateExecution() {
        var activity = StreamingActivityTracker.start("metered-phase");
        var delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("slow_lookup").description("fixture").inputSchema("{}").build();
            }
            @Override public String call(String input) {
                try { ageLastActivity(activity, Duration.ofSeconds(3)); }
                catch (Exception error) { throw new AssertionError(error); }
                assertFalse("real MCP work must not be treated as model silence", activity.isIdle(Duration.ofSeconds(1)));
                return "ok";
            }
        };
        var metered = new MeteredToolCallback(delegate, new McpToolMetrics(new SimpleMeterRegistry()));
        assertEquals("ok", metered.call("{}", new ToolContext(Map.of(StreamingActivityTracker.CONTEXT_KEY, activity))));
        assertFalse(activity.isIdle(Duration.ofSeconds(1)));
    }

    @Test
    public void requestToolDiscoveryUsesToolPhaseAndCannotInstallCallbacksAfterCancellation() {
        var activity = StreamingActivityTracker.start("request-tool-phase");
        var catalog = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService.class);
        org.mockito.Mockito.when(catalog.resolveDynamicToolCallbacks(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            ageLastActivity(activity, Duration.ofSeconds(3));
            assertFalse("catalog discovery is a tool wait", activity.isIdle(Duration.ofSeconds(1)));
            activity.cancel();
            return java.util.List.of();
        });
        var manager = new cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager(
                org.mockito.Mockito.mock(org.springframework.ai.model.tool.ToolCallingManager.class));
        manager.setMcpToolCatalogService(catalog);
        manager.setRequestToolEnabled(true);
        var options = org.springframework.ai.openai.OpenAiChatOptions.builder().toolContext(Map.of(
                StreamingActivityTracker.CONTEXT_KEY, activity,
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities.TOOL_CONTEXT_KEY, "ALL")).build();
        var response = new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("").properties(Map.of()).toolCalls(java.util.List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("load", "function", "request_tool",
                                        "{\"needs\":[\"weather\"]}"))).build())));
        assertThrows(CancellationException.class, () -> manager.executeToolCalls(
                new org.springframework.ai.chat.prompt.Prompt("query", options), response));
        assertTrue(options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty());
    }

    @Test
    public void progressIsThrottledPerPhaseAndNeverRenewsTheWatchdog() throws Exception {
        var activity = StreamingActivityTracker.start("visible-wait");
        var updates = new java.util.ArrayList<StreamingActivityTracker.Progress>();
        activity.onProgress(updates::add);
        StreamingActivityTracker.timeoutOnInactivity(Flux.never(), activity,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5));
        activity.beginModelRequest();
        publish(activity);
        publish(activity);
        assertEquals(1, updates.size());
        assertEquals("awaiting_model", updates.get(0).phase());
        try (var tool = activity.executingTool("lookup")) {
            publish(activity);
            assertEquals("tool_running", updates.get(1).phase());
        }
        try (var user = activity.awaitingUser(Duration.ofSeconds(5))) {
            publish(activity);
            assertEquals("awaiting_user", updates.get(2).phase());
        }
        activity.markReasoning();
        ageLastActivity(activity, Duration.ofSeconds(3));
        publish(activity);
        assertEquals("model_streaming", updates.get(3).phase());
        assertTrue("progress messages must not masquerade as model activity", activity.isIdle(Duration.ofSeconds(1)));
        int beforeCancel = updates.size();
        activity.cancel();
        publish(activity);
        assertEquals(beforeCancel, updates.size());
    }

    @Test
    public void failingProgressListenerCannotPreventProviderTimeout() {
        var activity = StreamingActivityTracker.start("broken-progress-sink");
        activity.onProgress(update -> { throw new IllegalStateException("UI disconnected"); });
        activity.beginModelRequest();
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                StreamingActivityTracker.timeoutOnInactivity(Flux.never(), activity,
                        Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofSeconds(5)).blockLast());
        assertTrue(hasCause(error, TimeoutException.class));
        assertTrue(activity.isCancelled());
    }

    @Test
    public void advisorScopesPublishImmediatelyRestoreParentsAndNeverRenewDeadlines() throws Exception {
        var activity = StreamingActivityTracker.start("advisor-ui");
        var updates = new java.util.ArrayList<StreamingActivityTracker.Progress>();
        activity.onProgress(updates::add);
        ageLastActivity(activity, Duration.ofSeconds(3));
        try (var outer = activity.advisorPhase(StreamingActivityTracker.AdvisorPhase.MEMORY_RETRIEVAL)) {
            assertEquals("memory_retrieval", updates.get(0).phase());
            try (var inner = activity.advisorPhase(StreamingActivityTracker.AdvisorPhase.RAG_ROUTING)) {
                assertEquals("rag_routing", updates.get(1).phase());
                assertTrue("visible Advisor work cannot extend the existing inactivity deadline", activity.isIdle(Duration.ofSeconds(1)));
            }
            assertEquals("memory_retrieval", updates.get(2).phase());
        }
        assertEquals("preparing_model", updates.get(3).phase());
        assertTrue(activity.isIdle(Duration.ofSeconds(1)));
        activity.beginModelRequest();
        ageFirstResponse(activity, Duration.ofMinutes(6));
        try (var ignored = activity.advisorPhase(StreamingActivityTracker.AdvisorPhase.KNOWLEDGE_RERANKING)) {
            assertTrue("even an existing provider deadline is unchanged by UI phases", activity.isIdle(Duration.ofDays(1)));
        }
    }

    @Test
    public void outOfOrderAndCancelledAdvisorScopesCannotOverwriteOtherWork() {
        var first = StreamingActivityTracker.start("parallel-first");
        var second = StreamingActivityTracker.start("parallel-second");
        var firstUpdates = new java.util.ArrayList<StreamingActivityTracker.Progress>();
        var secondUpdates = new java.util.ArrayList<StreamingActivityTracker.Progress>();
        first.onProgress(firstUpdates::add);
        second.onProgress(secondUpdates::add);
        var parent = first.advisorPhase(StreamingActivityTracker.AdvisorPhase.MEMORY_RETRIEVAL);
        var child = first.advisorPhase(StreamingActivityTracker.AdvisorPhase.RAG_ROUTING);
        var other = second.advisorPhase(StreamingActivityTracker.AdvisorPhase.KNOWLEDGE_RETRIEVAL);
        parent.close();
        assertEquals("rag_routing", firstUpdates.get(firstUpdates.size() - 1).phase());
        assertEquals("knowledge_retrieval", secondUpdates.get(0).phase());
        child.close();
        child.close();
        assertEquals(3, firstUpdates.size());
        assertEquals("preparing_model", firstUpdates.get(2).phase());
        second.cancel();
        int count = secondUpdates.size();
        other.close();
        second.advisorPhase(StreamingActivityTracker.AdvisorPhase.RAG_ROUTING).close();
        assertEquals(count, secondUpdates.size());
        assertTrue(((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(second, "advisorWaits")).isEmpty());
    }

    @Test
    public void advisorUiFailureOrMissingSubscriberCannotAffectExecution() throws Exception {
        try (var current = StreamingActivityTracker.scope(null);
             var ignored = StreamingActivityTracker.advisorPhase(StreamingActivityTracker.AdvisorPhase.RAG_ROUTING)) {
            assertNull(StreamingActivityTracker.current());
        }
        var activity = StreamingActivityTracker.start("failed-advisor-ui");
        activity.onProgress(ignored -> { throw new IllegalStateException("disconnected browser"); });
        ageLastActivity(activity, Duration.ofSeconds(3));
        try (var ignored = activity.advisorPhase(StreamingActivityTracker.AdvisorPhase.KNOWLEDGE_RETRIEVAL)) {
            assertTrue(activity.isIdle(Duration.ofSeconds(1)));
        }
        assertTrue(((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(activity, "advisorWaits")).isEmpty());
    }

    @Test
    public void concurrentPeriodicPublicationCannotOvertakeAnAdvisorScopeExit() throws Exception {
        var activity = StreamingActivityTracker.start("advisor-publication-race");
        var phase = activity.advisorPhase(StreamingActivityTracker.AdvisorPhase.RAG_ROUTING);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var closing = new java.util.concurrent.CountDownLatch(1);
        var updates = new java.util.concurrent.CopyOnWriteArrayList<StreamingActivityTracker.Progress>();
        activity.onProgress(update -> {
            if (update.phase().equals("rag_routing")) {
                entered.countDown();
                try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
            updates.add(update);
        });
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var publishing = workers.submit(() -> publish(activity));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var close = workers.submit(() -> { closing.countDown(); phase.close(); });
            assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> close.get(50, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            publishing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            close.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(java.util.List.of("rag_routing", "preparing_model"),
                    updates.stream().map(StreamingActivityTracker.Progress::phase).toList());
        } finally {
            release.countDown();
            workers.shutdownNow();
            phase.close();
            activity.cancel();
        }
    }

    @Test
    public void cancellationAndOtherStepsDoNotWaitForSlowAdvisorProgressDelivery() throws Exception {
        var activity = StreamingActivityTracker.start("slow-advisor-ui");
        var phase = activity.advisorPhase(StreamingActivityTracker.AdvisorPhase.RAG_ROUTING);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        activity.onProgress(update -> {
            entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var publishing = worker.submit(() -> publish(activity));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var other = StreamingActivityTracker.start("independent-step");
            var updates = new java.util.ArrayList<StreamingActivityTracker.Progress>();
            other.onProgress(updates::add);
            try (var ignored = other.advisorPhase(StreamingActivityTracker.AdvisorPhase.MEMORY_RETRIEVAL)) {
                assertEquals("memory_retrieval", updates.get(0).phase());
                activity.cancel();
                assertTrue(activity.isCancelled());
                assertEquals(1, release.getCount());
            }
            release.countDown();
            publishing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(activity, "advisorWaits")).isEmpty());
        } finally {
            release.countDown();
            worker.shutdownNow();
            phase.close();
        }
    }

    private static void publish(StreamingActivityTracker.Activity activity) {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(activity, "publishProgress");
    }

    private static void ageLastActivity(StreamingActivityTracker.Activity activity, Duration age) throws Exception {
        var field = activity.getClass().getDeclaredField("lastActivityNanos");
        field.setAccessible(true);
        ((AtomicLong) field.get(activity)).set(System.nanoTime() - age.toNanos());
    }

    private static void ageFirstResponse(StreamingActivityTracker.Activity activity, Duration age) throws Exception {
        var field = activity.getClass().getDeclaredField("firstResponseStartedNanos");
        field.setAccessible(true);
        field.setLong(activity, System.nanoTime() - age.toNanos());
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
