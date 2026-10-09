package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.config.ReactorContextPropagationConfig;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real framework delegation and async subscriptions, without network or model calls. */
public class StreamingAttemptLifecycleTest {
    @Test
    public void activitySurvivesAdvisorThreadSwitchAndDoesNotLeak() {
        new ReactorContextPropagationConfig().enableMdcContextPropagation();
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("advisor-switch");
        AtomicReference<StreamingActivityTracker.Activity> observed = new AtomicReference<>();
        StreamingActivityTracker.timeoutOnInactivity(Flux.just(1).publishOn(Schedulers.boundedElastic())
                .doOnNext(ignored -> observed.set(StreamingActivityTracker.current())), activity, Duration.ofSeconds(2))
                .blockLast();
        assertSame(activity, observed.get());
        assertNull(StreamingActivityTracker.current());
        assertTrue(activity.isCancelled());
    }

    @Test
    public void rawToolAndContentFramesRenewWatchdogAfterThreadSwitch() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("raw-tool-frames");
        ReasoningContentFilter filter = new ReasoningContentFilter();
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://unused/chat")).build();
        String event = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}}]}\n\n";
        Flux<String> frames = Flux.interval(Duration.ofMillis(100)).take(7).publishOn(Schedulers.boundedElastic())
                .concatMap(ignored -> filter.filter(request, req -> Mono.just(
                        ClientResponse.create(HttpStatus.OK).body(event).build())))
                .concatMap(response -> response.bodyToMono(String.class));
        assertEquals(7, StreamingActivityTracker.timeoutOnInactivity(frames, activity, Duration.ofMillis(400))
                .collectList().block().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sameSessionKeepsDistinctRunReasoningAcrossAsyncSubscriptions() throws Exception {
        ReasoningContentFilter filter = new ReasoningContentFilter();
        Flux<String> first = reasoningRun(filter, "shared-session", "first-run", "first answer reasoning");
        Flux<String> second = reasoningRun(filter, "shared-session", "second-run", "second answer reasoning");
        try {
            Flux.merge(first, second).blockLast();
            java.lang.reflect.Field field = ReasoningContentFilter.class.getDeclaredField("sessionReasonings");
            field.setAccessible(true);
            Map<String, List<String>> cache = (Map<String, List<String>>) field.get(filter);
            // Body doFinally runs just after completion is delivered to blockLast.
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while ((cache.getOrDefault("first-run", List.of()).isEmpty()
                    || cache.getOrDefault("second-run", List.of()).isEmpty()) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(List.of("first answer reasoning"), cache.get("first-run"));
            assertEquals(List.of("second answer reasoning"), cache.get("second-run"));
            assertFalse(cache.containsKey("shared-session"));
            assertFalse(cache.containsKey("unknown-session"));
        } finally {
            ReasoningContentFilter.clearRun("first-run");
            ReasoningContentFilter.clearRun("second-run");
            ReasoningContentFilter.clearLatestReasoning("shared-session");
        }
    }

    private static Flux<String> reasoningRun(ReasoningContentFilter filter, String session, String run, String text)
            throws Exception {
        try (AutoCloseable ignored = ReasoningContentFilter.scopeSession(session, run)) {
            Flux<String> source = Mono.delay(Duration.ofMillis(20)).publishOn(Schedulers.boundedElastic())
                    .flatMap(ignoredTick -> filter.filter(
                            ClientRequest.create(HttpMethod.GET, URI.create("http://unused/chat")).build(), request ->
                                    Mono.just(ClientResponse.create(HttpStatus.OK)
                                            .body("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"" + text + "\"}}]}\n\n")
                                            .build())))
                    .flatMapMany(response -> response.bodyToFlux(String.class));
            return StreamingActivityTracker.timeoutOnInactivity(source, StreamingActivityTracker.start(run),
                    Duration.ofSeconds(2));
        }
    }

    @Test
    public void canceledAttemptCannotStartAnotherModelRequest() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("orphan-request");
        activity.cancel();
        AtomicInteger requests = new AtomicInteger();
        ReasoningContentFilter filter = new ReasoningContentFilter();
        assertThrows(CancellationException.class, () -> filter.filter(
                ClientRequest.create(HttpMethod.GET, URI.create("http://unused/chat")).build(), request -> {
                    requests.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }).contextWrite(context -> context.put(StreamingActivityTracker.CONTEXT_KEY, activity)).block());
        assertEquals(0, requests.get());
    }

    @Test
    public void cancelImmediatelyStopsSilentInnerHttpBody() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("silent-inner-body");
        AtomicBoolean bodyCancelled = new AtomicBoolean();
        ReasoningContentFilter filter = new ReasoningContentFilter();
        reactor.core.Disposable subscription = filter.filter(
                ClientRequest.create(HttpMethod.GET, URI.create("http://unused/chat")).build(), request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK).body(Flux.<org.springframework.core.io.buffer.DataBuffer>never()
                                .doOnCancel(() -> bodyCancelled.set(true))).build()))
                .flatMap(response -> response.bodyToMono(String.class))
                .contextWrite(context -> context.put(StreamingActivityTracker.CONTEXT_KEY, activity)).subscribe();
        try {
            activity.cancel();
            assertTrue("even a silent nested stream must be cancelled", bodyCancelled.get());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void frameworkSerialBatchStopsBeforeSecondCallbackAfterCancellation() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("serial-batch");
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ToolCallback first = callback("first", () -> { firstCalls.incrementAndGet(); activity.cancel(); return "ok"; });
        ToolCallback second = callback("second", () -> { secondCalls.incrementAndGet(); return "ok"; });
        OpenAiChatOptions options = OpenAiChatOptions.builder().toolCallbacks(List.of(first, second))
                .toolContext(Map.of(StreamingActivityTracker.CONTEXT_KEY, activity,
                        ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.BUSINESS_ONLY.name())).build();
        Prompt prompt = new Prompt("test", options);
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "first", "{}"),
                        new AssistantMessage.ToolCall("2", "function", "second", "{}"))).build())));
        RobustToolCallingManager manager = new RobustToolCallingManager(DefaultToolCallingManager.builder().build());
        assertThrows(CancellationException.class, () -> manager.executeToolCalls(prompt, response));
        assertEquals(1, firstCalls.get());
        assertEquals(0, secondCalls.get());
        assertSame("guard wrappers must not leak into original options", first, options.getToolCallbacks().get(0));
        assertTrue(activity.hasToolActivity());
    }

    @Test
    public void canceledMcpFailureCannotRetryOrBecomeModelErrorText() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("mcp-retry");
        AtomicInteger calls = new AtomicInteger();
        ToolCallback raw = callback("weather", () -> {
            calls.incrementAndGet();
            activity.cancel();
            throw new RuntimeException(new java.io.IOException("503 temporary unavailable"));
        });
        MeteredToolCallback callback = new MeteredToolCallback(raw, new McpToolMetrics(new SimpleMeterRegistry()),
                true, false, 10, 20_000, false, true, 3, 1);
        assertThrows(CancellationException.class, () -> callback.call("{}",
                new ToolContext(Map.of(StreamingActivityTracker.CONTEXT_KEY, activity))));
        assertEquals(1, calls.get());
    }

    @Test
    public void privateActivityNeverReachesMcpMetadata() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("private-meta");
        AtomicReference<ToolContext> received = new AtomicReference<>();
        ToolCallback raw = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("search").description("test").inputSchema("{}").build();
            }
            @Override public String call(String input) { return "ok"; }
            @Override public String call(String input, ToolContext context) { received.set(context); return "ok"; }
        };
        ToolContext original = new ToolContext(Map.of(StreamingActivityTracker.CONTEXT_KEY, activity, "sessionId", "s1"));
        assertEquals("ok", new MeteredToolCallback(raw, new McpToolMetrics(new SimpleMeterRegistry())).call("{}", original));
        assertFalse(received.get().getContext().containsKey(StreamingActivityTracker.CONTEXT_KEY));
        assertEquals("s1", received.get().getContext().get("sessionId"));
        assertSame(activity, original.getContext().get(StreamingActivityTracker.CONTEXT_KEY));
    }

    @Test
    public void humanWaitHasFiniteDeadlineAndOnlySuspendsItsOwnAttempt() throws Exception {
        StreamingActivityTracker.Activity waiting = StreamingActivityTracker.start("human-wait");
        StreamingActivityTracker.Activity other = StreamingActivityTracker.start("other-step");
        try (StreamingActivityTracker.Scope ignored = waiting.awaitingUser(Duration.ofMillis(300))) {
            Thread.sleep(60);
            assertFalse(waiting.isIdle(Duration.ofMillis(30)));
            assertTrue(other.isIdle(Duration.ofMillis(30)));
            Thread.sleep(300);
            assertTrue(waiting.isIdle(Duration.ofMillis(30)));
        }
    }

    @Test
    public void compactedRouteKeepsOriginalEvidence() {
        String raw = "{\"route\":{\"origin\":\"116,39\",\"destination\":\"117,40\",\"paths\":[{\"distance\":\"1234\","
                + "\"duration\":\"456\",\"steps\":[{\"instruction\":\"walk east\",\"polyline\":\"" + "116,39;".repeat(1200) + "\"}]}]}}";
        ToolCallProgressEmitter progress = mock(ToolCallProgressEmitter.class);
        MeteredToolCallback callback = new MeteredToolCallback(callback("maps_direction_walking", () -> raw),
                new McpToolMetrics(new SimpleMeterRegistry()));
        callback.setToolCallProgressEmitter(progress);
        String result = callback.call("{}", new ToolContext(Map.of("sessionId", "s1", "stepLabel", "step4")));
        assertTrue(result.length() < raw.length());
        assertTrue(result.contains("amap_route_compacted"));
        verify(progress).recordEvidence(eq("s1"), eq("maps_direction_walking"), eq("{}"), eq(raw),
                eq("success"), anyLong(), eq(raw.length()), eq("step4"), anyString());
    }

    private static ToolCallback callback(String name, java.util.function.Supplier<String> action) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
            }
            @Override public String call(String input) { return action.get(); }
        };
    }
}
