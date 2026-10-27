package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ModelStreamException;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.model.ModelCredentialRequestAuthorizer;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelCall;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelConnectionAssembler;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelEndpointRegistry;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailoverObserver;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFallbackPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelRetriesExhaustedException;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelRetryPolicy;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelsUnavailableException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Request-layer failover through the real Spring AI tool loop, our credential, budget and reasoning
 * guards, and real HTTP. Fake providers only; no paid calls.
 */
public class ModelRequestFailoverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANSWER = "北京今天晴，25°C。";
    private static final String REASONING = "需要先查询北京天气";
    private static final ModelRetryPolicy RETRY_THREE = new ModelRetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50));

    private final Usage usage = new Usage();
    private final Health health = new Health();
    private final ModelEndpointRegistry endpoints = new ModelEndpointRegistry();
    private final List<FakeProvider> providers = new ArrayList<>();
    private final Weather weather = new Weather();
    private final Access access = new Access();
    private String run;

    @Before public void start() {
        ModelUsageLedger.install(usage);
        run = "failover-" + UUID.randomUUID();
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(2), 20, 20, 1_000_000));
        MDC.put("runId", run);
        MDC.put("userId", "alice");
    }

    @After public void stop() {
        ModelUsageLedger.uninstall(usage);
        RunBudgetRegistry.finish(run);
        ReasoningContentFilter.clearRun(run);
        MDC.clear();
        providers.forEach(FakeProvider::close);
    }

    // ---------------------------------------------------------------- switching

    @Test public void synchronousToolLoopMovesToAnotherConnectionWithItsOwnCredentialAndAccounting() throws Exception {
        FakeProvider a = provider(always(400, "Unsupported model mimo-v2.5"));
        FakeProvider b = provider(weatherScript());
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        assertEquals(ANSWER, model.call(prompt(null)).getResult().getOutput().getText());

        assertEquals("unhealthy primary is skipped on the tool follow-up", 1, a.exchanges.size());
        assertEquals("Bearer key-api-a", a.exchanges.get(0).authorization());
        assertEquals(2, b.exchanges.size());
        for (var exchange : b.exchanges) {
            assertEquals("mimo-v2.6-flash", exchange.model());
            assertEquals("substitute uses its own credential, never the primary's", "Bearer key-api-b", exchange.authorization());
        }
        assertTrue(b.exchanges.get(1).hasToolResult());
        assertEquals(1, weather.calls.get());
        assertEquals("a rejected request is never charged", List.of("api-a/mimo-v2.5/ERROR/0", "api-b/mimo-v2.6-flash/SUCCESS/15",
                "api-b/mimo-v2.6-flash/SUCCESS/20"), usage.settled());
        var budget = RunBudgetRegistry.find(run).snapshot();
        assertEquals(3, budget.get("modelCalls"));
        assertEquals(35L, budget.get("confirmedTokens"));
        assertEquals(0, budget.get("unknownUsageCalls"));
        assertEquals(35L, budget.get("reservedTokens"));
        assertEquals(List.of(run + "/api-b/mimo-v2.6-flash", run + "/api-b/mimo-v2.6-flash"), health.substitutions);
    }

    @Test public void streamingToolLoopMovesToAnotherConnectionAndKeepsTheSubstitutesReasoning() throws Exception {
        FakeProvider a = provider(always(400, "Unsupported model mimo-v2.5"));
        FakeProvider b = provider(weatherScript());
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        assertEquals(ANSWER, stream(model));

        assertEquals(1, a.exchanges.size());
        assertEquals(2, b.exchanges.size());
        for (var exchange : b.exchanges) {
            assertEquals("mimo-v2.6-flash", exchange.model());
            assertEquals("Bearer key-api-b", exchange.authorization());
            assertTrue(exchange.stream());
        }
        assertEquals("the substitute's own reasoning goes back to the same provider",
                REASONING, b.exchanges.get(1).toolCallReasoning());
        assertEquals(1, weather.calls.get());
        // Spring AI cancels the body after [DONE]; a stream that finished still settles as SUCCESS.
        assertEquals(List.of("api-a/mimo-v2.5/ERROR/0", "api-b/mimo-v2.6-flash/SUCCESS/15", "api-b/mimo-v2.6-flash/SUCCESS/20"),
                usage.settled());
        assertEquals(35L, RunBudgetRegistry.find(run).snapshot().get("confirmedTokens"));
        assertEquals(List.of(run + "/api-b/mimo-v2.6-flash", run + "/api-b/mimo-v2.6-flash"), health.substitutions);
    }

    @Test public void sameConnectionSwapsOnlyTheModelNameInBothModes() throws Exception {
        FakeProvider shared = provider((exchange, index) -> "mimo-v2.5".equals(exchange.model())
                ? error(400, "Unsupported model mimo-v2.5") : weatherScript().reply(exchange, index));
        var planner = planner(Map.of(new ModelTarget("api-s", "mimo-v2.5"), new ModelTarget("api-s", "mimo-v2.6-flash")));
        OpenAiApi api = assembler(planner, RETRY_THREE).assemble(settings("api-s", shared));

        assertEquals(ANSWER, model(api, "mimo-v2.5").call(prompt(null)).getResult().getOutput().getText());
        health.down.clear();
        assertEquals(ANSWER, stream(model(api, "mimo-v2.5")));

        assertEquals(List.of("mimo-v2.5", "mimo-v2.6-flash", "mimo-v2.6-flash", "mimo-v2.5", "mimo-v2.6-flash", "mimo-v2.6-flash"),
                shared.exchanges.stream().map(FakeProvider.Exchange::model).toList());
        assertTrue(shared.exchanges.stream().allMatch(e -> "Bearer key-api-s".equals(e.authorization())));
        assertEquals(REASONING, shared.exchanges.get(5).toolCallReasoning());
        assertEquals(2, weather.calls.get());
    }

    // ---------------------------------------------------------------- not switching

    @Test public void transientFailuresRetryTheSameModelAndNeverSwitch() throws Exception {
        AtomicInteger overloaded = new AtomicInteger(2);
        FakeProvider a = provider((exchange, index) -> overloaded.getAndDecrement() > 0
                ? error(503, "The server is overloaded") : weatherScript().reply(exchange, index));
        FakeProvider b = provider(weatherScript());
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        assertEquals(ANSWER, model.call(prompt(null)).getResult().getOutput().getText());
        overloaded.set(2);
        assertEquals(ANSWER, stream(model));

        assertEquals(0, b.exchanges.size());
        assertEquals(8, a.exchanges.size());
        assertTrue(a.exchanges.stream().allMatch(e -> "mimo-v2.5".equals(e.model())));
        assertTrue(health.down.isEmpty());
        assertTrue(health.substitutions.isEmpty());
    }

    @Test public void requestErrorsReturnUnchangedWithoutTryingASubstitute() throws Exception {
        FakeProvider a = provider(always(400, "This model's maximum context length is 128000 tokens"));
        FakeProvider b = provider(weatherScript());
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        var sync = assertThrows(NonTransientAiException.class, () -> model.call(prompt(null)));
        assertTrue(sync.getMessage(), sync.getMessage().contains("maximum context length"));
        Throwable streamed = assertThrows(RuntimeException.class, () -> stream(model));
        WebClientResponseException http = cause(streamed, WebClientResponseException.class);
        assertNotNull(streamed.toString(), http);
        assertEquals(400, http.getStatusCode().value());
        assertTrue(http.getResponseBodyAsString().contains("maximum context length"));

        assertEquals(2, a.exchanges.size());
        assertEquals(0, b.exchanges.size());
    }

    @Test public void everyCandidateUnavailableListsEachReason() throws Exception {
        FakeProvider a = provider(always(400, "Unsupported model mimo-v2.5"));
        FakeProvider b = provider(always(401, "Invalid API key"));
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        var sync = assertThrows(ModelsUnavailableException.class, () -> model.call(prompt(null)));
        assertTrue(sync.getMessage(), sync.getMessage().contains("mimo-v2.5（模型不存在或已下线，HTTP 400）"));
        assertTrue(sync.getMessage(), sync.getMessage().contains("mimo-v2.6-flash（鉴权失败，密钥无效或已撤销，HTTP 401）"));
        assertFalse("provider text stays out of the user-facing error", sync.getMessage().contains("Invalid API key"));
        health.down.clear();
        Throwable streamed = assertThrows(RuntimeException.class, () -> stream(model));
        assertNotNull(streamed.toString(), cause(streamed, ModelsUnavailableException.class));

        assertEquals(2, a.exchanges.size());
        assertEquals(2, b.exchanges.size());
        assertEquals(0, weather.calls.get());
    }

    // ---------------------------------------------------------------- inside the stream

    @Test public void streamErrorBeforeAnyOutputStillMovesToTheSubstitute() throws Exception {
        FakeProvider shared = provider((exchange, index) -> "mimo-v2.5".equals(exchange.model())
                ? new FakeProvider.Reply(200, "text/event-stream", "data: {\"error\":{\"code\":\"400\",\"message\":\"Unsupported model mimo-v2.5\"}}\n\n")
                : weatherScript().reply(exchange, index));
        var planner = planner(Map.of(new ModelTarget("api-s", "mimo-v2.5"), new ModelTarget("api-s", "mimo-v2.6-flash")));
        OpenAiApi api = assembler(planner, RETRY_THREE).assemble(settings("api-s", shared));

        assertEquals(ANSWER, stream(model(api, "mimo-v2.5")));

        assertEquals(List.of("mimo-v2.5", "mimo-v2.6-flash", "mimo-v2.6-flash"),
                shared.exchanges.stream().map(FakeProvider.Exchange::model).toList());
        assertTrue(health.down.contains(new ModelTarget("api-s", "mimo-v2.5")));
        assertEquals(REASONING, shared.exchanges.get(2).toolCallReasoning());
    }

    @Test public void streamErrorAfterOutputIsNeverReplayedOnAnotherModel() throws Exception {
        String head = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mimo-v2.5\",\"choices\":[{\"index\":0,";
        FakeProvider a = provider((exchange, index) -> new FakeProvider.Reply(200, "text/event-stream", List.of(
                head + "\"delta\":{\"role\":\"assistant\",\"content\":\"北京今天\"},\"finish_reason\":null}]}\n\n",
                "data: {\"error\":{\"code\":\"500\",\"message\":\"internal error\"}}\n\n")));
        FakeProvider b = provider(weatherScript());
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        Throwable error = assertThrows(RuntimeException.class, () -> stream(model));

        assertNotNull(error.toString(), cause(error, ModelStreamException.class));
        assertEquals(1, a.exchanges.size());
        assertEquals("output already reached the caller", 0, b.exchanges.size());
    }

    @Test public void exhaustedTransientRetriesStopWithoutSwitchingOrAnotherRetryLayer() throws Exception {
        FakeProvider a = provider(always(503, "The server is overloaded"));
        FakeProvider b = provider(weatherScript());
        OpenAiChatModel model = model(connect("api-a", a, "api-b", b, RETRY_THREE), "mimo-v2.5");

        var sync = assertThrows(ModelRetriesExhaustedException.class, () -> model.call(prompt(null)));
        assertTrue(sync.getMessage(), sync.getMessage().contains("已重试 3 次"));
        Throwable streamed = assertThrows(RuntimeException.class, () -> stream(model));
        assertNotNull(streamed.toString(), cause(streamed, ModelRetriesExhaustedException.class));

        assertEquals(6, a.exchanges.size());
        assertEquals(0, b.exchanges.size());
        assertTrue(health.down.isEmpty());
    }

    @Test public void candidateTheCallerMayNotUseIsSkippedInBothModes() throws Exception {
        FakeProvider a = provider(always(400, "Unsupported model mimo-v2.5"));
        FakeProvider b = provider(weatherScript());
        FakeProvider c = provider(weatherScript());
        access.denied.add("api-b");
        ModelConnectionAssembler assembler = assembler((requested, traits) -> "mimo-v2.5".equals(requested.model())
                ? List.of(new ModelTarget("api-b", "mimo-v2.6-flash"), new ModelTarget("api-c", "mimo-v2.6-pro")) : List.of(), RETRY_THREE);
        assembler.assemble(settings("api-b", b));
        assembler.assemble(settings("api-c", c));
        OpenAiChatModel model = model(assembler.assemble(settings("api-a", a)), "mimo-v2.5");

        assertEquals(ANSWER, model.call(prompt(null)).getResult().getOutput().getText());
        health.down.clear();
        assertEquals(ANSWER, stream(model));

        assertEquals(0, b.exchanges.size());
        assertEquals(4, c.exchanges.size());
        assertTrue(c.exchanges.stream().allMatch(e -> "mimo-v2.6-pro".equals(e.model()) && "Bearer key-api-c".equals(e.authorization())));
    }

    // ---------------------------------------------------------------- wiring

    private FakeProvider provider(FakeProvider.Behavior behavior) throws Exception {
        FakeProvider provider = new FakeProvider(behavior);
        providers.add(provider);
        return provider;
    }

    private OpenAiApi connect(String primaryId, FakeProvider primary, String substituteId, FakeProvider substitute,
                              ModelRetryPolicy retry) {
        var planner = planner(Map.of(new ModelTarget(primaryId, "mimo-v2.5"), new ModelTarget(substituteId, "mimo-v2.6-flash")));
        ModelConnectionAssembler assembler = assembler(planner, retry);
        assembler.assemble(settings(substituteId, substitute));
        return assembler.assemble(settings(primaryId, primary));
    }

    private ModelConnectionAssembler assembler(ModelFallbackPlanner planner, ModelRetryPolicy retry) {
        return new ModelConnectionAssembler(new ModelCredentialRequestAuthorizer(access), endpoints, planner, health, retry);
    }

    private static ModelConnectionAssembler.Settings settings(String apiId, FakeProvider provider) {
        return new ModelConnectionAssembler.Settings(apiId, provider.baseUrl(), "/v1/chat/completions", "/v1/embeddings", null);
    }

    private static ModelFallbackPlanner planner(Map<ModelTarget, ModelTarget> substitutes) {
        return (requested, traits) -> substitutes.containsKey(requested) ? List.of(substitutes.get(requested)) : List.of();
    }

    private OpenAiChatModel model(OpenAiApi api, String modelName) {
        // A single Spring AI attempt isolates the request-layer policy under test.
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).toolCallbacks(List.of(weather)).build())
                .toolCallingManager(new RobustToolCallingManager(ToolCallingManager.builder().build()))
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    private Prompt prompt(StreamingActivityTracker.Activity activity) {
        Map<String, Object> context = new HashMap<>(Map.of("userId", "alice", "agent.run_id", run, "sessionId", "session",
                ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.BUSINESS_ONLY.name()));
        if (activity != null) context.put(StreamingActivityTracker.CONTEXT_KEY, activity);
        return new Prompt("北京今天天气怎么样？", OpenAiChatOptions.builder().toolContext(context).build());
    }

    /** Same scopes as the production streaming steps: call scope, activity and inactivity guard. */
    private String stream(OpenAiChatModel model) throws Exception {
        var activity = StreamingActivityTracker.start("failover-test");
        try (var call = ReasoningContentFilter.scopeCall("session", run); var bound = StreamingActivityTracker.scope(activity)) {
            Flux<ChatResponse> flux = model.stream(prompt(activity));
            return StreamingActivityTracker.timeoutOnInactivity(flux, activity, Duration.ofSeconds(20))
                    .map(ModelRequestFailoverTest::text).collect(Collectors.joining()).block(Duration.ofSeconds(30));
        }
    }

    private static String text(ChatResponse response) {
        return response.getResult() != null && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null ? response.getResult().getOutput().getText() : "";
    }

    private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
        for (Throwable t = error; t != null; t = t.getCause()) if (type.isInstance(t)) return type.cast(t);
        return null;
    }

    // ---------------------------------------------------------------- fakes

    private static FakeProvider.Behavior always(int status, String message) {
        return (exchange, index) -> error(status, message);
    }

    private static FakeProvider.Reply error(int status, String message) {
        return new FakeProvider.Reply(status, "application/json",
                "{\"error\":{\"code\":\"" + status + "\",\"message\":\"" + message + "\"}}");
    }

    /** First request: call get_weather with reasoning; follow-up with a tool result: the answer. */
    private static FakeProvider.Behavior weatherScript() {
        return (exchange, index) -> {
            boolean followUp = exchange.hasToolResult();
            String model = exchange.model();
            int tokens = followUp ? 20 : 15;
            if (!exchange.stream()) {
                String message = followUp
                        ? "{\"role\":\"assistant\",\"content\":\"" + ANSWER + "\"}"
                        : "{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"" + REASONING + "\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]}";
                return new FakeProvider.Reply(200, "application/json", "{\"id\":\"c" + index + "\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + model
                        + "\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + (followUp ? "stop" : "tool_calls") + "\"}],"
                        + usageJson(tokens) + "}");
            }
            String head = "data: {\"id\":\"c" + index + "\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + model + "\",\"choices\":[{\"index\":0,";
            String body = followUp
                    ? head + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + ANSWER + "\"},\"finish_reason\":null}]}\n\n"
                    + head + "\"delta\":{},\"finish_reason\":\"stop\"}]," + usageJson(tokens) + "}\n\n"
                    : head + "\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"" + REASONING + "\"},\"finish_reason\":null}]}\n\n"
                    + head + "\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                    + "\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                    + head + "\"delta\":{},\"finish_reason\":\"tool_calls\"}]," + usageJson(tokens) + "}\n\n";
            return new FakeProvider.Reply(200, "text/event-stream", body + "data: [DONE]\n\n");
        };
    }

    private static String usageJson(int total) {
        return "\"usage\":{\"prompt_tokens\":" + (total - 5) + ",\"completion_tokens\":5,\"total_tokens\":" + total + "}";
    }

    static final class FakeProvider implements AutoCloseable {
        /** More than one chunk is sent with chunked encoding, flushed and spaced apart. */
        record Reply(int status, String contentType, List<String> chunks) {
            Reply(int status, String contentType, String body) { this(status, contentType, List.of(body)); }
        }

        interface Behavior { Reply reply(Exchange exchange, int index); }

        record Exchange(String authorization, Map<String, Object> body) {
            String model() { return (String) body.get("model"); }
            boolean stream() { return Boolean.TRUE.equals(body.get("stream")); }
            @SuppressWarnings("unchecked") List<Map<String, Object>> messages() { return (List<Map<String, Object>>) body.get("messages"); }
            boolean hasToolResult() { return messages().stream().anyMatch(m -> "tool".equals(m.get("role"))); }
            Object toolCallReasoning() {
                return messages().stream().filter(m -> "assistant".equals(m.get("role")) && m.get("tool_calls") != null)
                        .map(m -> m.get("reasoning_content")).findFirst().orElse(null);
            }
        }

        final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        FakeProvider(Behavior behavior) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", http -> {
                try {
                    Map<String, Object> body = JSON.readValue(http.getRequestBody().readAllBytes(), new TypeReference<>() {});
                    Exchange exchange = new Exchange(http.getRequestHeaders().getFirst("Authorization"), body);
                    int index;
                    synchronized (exchanges) { index = exchanges.size(); exchanges.add(exchange); }
                    Reply reply = behavior.reply(exchange, index);
                    http.getResponseHeaders().set("Content-Type", reply.contentType());
                    if (reply.chunks().size() == 1) {
                        byte[] bytes = reply.chunks().get(0).getBytes(StandardCharsets.UTF_8);
                        http.sendResponseHeaders(reply.status(), bytes.length);
                        http.getResponseBody().write(bytes);
                    } else {
                        http.sendResponseHeaders(reply.status(), 0);
                        for (String chunk : reply.chunks()) {
                            http.getResponseBody().write(chunk.getBytes(StandardCharsets.UTF_8));
                            http.getResponseBody().flush();
                            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        }
                    }
                } finally {
                    http.close();
                }
            });
            server.setExecutor(executor);
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    static final class Weather implements ToolCallback {
        final AtomicInteger calls = new AtomicInteger();
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("get_weather").description("查询城市天气")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}").build();
        }
        @Override public String call(String input) {
            calls.incrementAndGet();
            return "{\"city\":\"北京\",\"weather\":\"晴\",\"temperature\":25}";
        }
        @Override public String call(String input, ToolContext context) { return call(input); }
    }

    /** Credentials per connection; the owner must come from the trusted run, never be missing. */
    static final class Access implements ModelProviderAccess {
        final Set<String> denied = ConcurrentHashMap.newKeySet();
        @Override public String apiKeyForRequest(String apiId, String user) {
            if (!"alice".equals(user)) throw new SecurityException("unexpected caller " + user);
            if (denied.contains(apiId)) throw new SecurityException("无权使用该模型连接");
            return "key-" + apiId;
        }
        @Override public String apiKeyForRequest(String apiId, String user, URI requestUri) { return apiKeyForRequest(apiId, user); }
        @Override public void requirePlatformAuxiliary(String user, String purpose) { }
    }

    static final class Health implements ModelFailoverObserver {
        final Set<ModelTarget> down = ConcurrentHashMap.newKeySet();
        final List<String> substitutions = new CopyOnWriteArrayList<>();
        @Override public boolean available(ModelTarget target) { return !down.contains(target); }
        @Override public void failed(ModelFailure failure) {
            if (failure.kind() == ModelFailure.Kind.MODEL_UNAVAILABLE) down.add(failure.target());
        }
        @Override public void substituted(ModelCall call, ModelTarget requested, ModelTarget actual, List<ModelFailure> failures) {
            substitutions.add(call.runId() + "/" + actual.apiId() + "/" + actual.model());
        }
    }

    static final class Usage implements ModelUsageAccounting {
        record Entry(Attempt attempt, AtomicReference<String> outcome, AtomicReference<Long> tokens) { }
        final List<Entry> entries = new CopyOnWriteArrayList<>();
        @Override public Ticket begin(Attempt attempt) {
            Entry entry = new Entry(attempt, new AtomicReference<>(), new AtomicReference<>());
            entries.add(entry);
            return (tokens, outcome) -> { if (entry.outcome().compareAndSet(null, outcome)) entry.tokens().set(tokens); };
        }
        @Override public void openRun(String runId, String userId, long deadline, boolean system) { }
        @Override public void changeRunState(String runId, String userId, String state) { }

        /** Streaming settlement runs on a worker after the body completes. */
        List<String> settled() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (entries.stream().anyMatch(e -> e.outcome().get() == null) && System.nanoTime() < deadline) Thread.sleep(20);
            return entries.stream().map(e -> e.attempt().apiId() + "/" + e.attempt().model() + "/" + e.outcome().get() + "/" + e.tokens().get()).toList();
        }
    }
}
