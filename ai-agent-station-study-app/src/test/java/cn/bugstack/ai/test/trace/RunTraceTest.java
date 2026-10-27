package cn.bugstack.ai.test.trace;

import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.trace.ModelReplies;
import cn.bugstack.ai.domain.agent.service.trace.ModelTraceFilter;
import cn.bugstack.ai.domain.agent.service.trace.RunTrace;
import cn.bugstack.ai.domain.agent.service.trace.TraceRecorder;
import cn.bugstack.ai.infrastructure.adapter.repository.trace.RunTraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RunTraceTest {

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate db;
    private RunTraceStore store;
    private TraceRecorder recorder;
    private final String run = UUID.randomUUID().toString();

    @Before
    public void setup() throws Exception {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        Path migration = Path.of("../docs/dev-ops/sql-migrations/V073__run_trace.sql");
        if (!Files.exists(migration)) migration = Path.of("docs/dev-ops/sql-migrations/V073__run_trace.sql");
        String sql = Files.readString(migration).replaceAll("COMMENT '[^']*'", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        db.execute("CREATE TABLE ai_run_history(run_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(128),session_id VARCHAR(255))");
        db.execute("CREATE TABLE ai_run_feedback(owner_user_id VARCHAR(64),run_id VARCHAR(128))");
        store = new RunTraceStore(db, 1000);
        recorder = store.recorder();
    }

    @After
    public void teardown() {
        RunTrace.uninstall(recorder);
        RunBudgetRegistry.finish(run);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private RunTrace.ModelExchange exchange(List<Map<String, Object>> messages, RunTrace.ModelReply reply) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "mimo-v2.6-flash");
        request.put("messages", messages);
        request.put("tools", List.of(Map.of("type", "function", "function", Map.of("name", "weather", "parameters", Map.of()))));
        request.put("stream", true);
        return new RunTrace.ModelExchange(run, "alice", "执行#attempt-1", "api-1", null, System.currentTimeMillis(), 800,
                request, 200, reply, RunTrace.OUTCOME_OK, null);
    }

    @Test
    public void toolLoopRequestsKeepOnlyNewMessagesAndAreRestoredCompletely() throws Exception {
        String systemPrompt = "你是旅行助手，回答前先查询实时信息。".repeat(60);
        Map<String, Object> system = message("system", systemPrompt), user = message("user", "北京今天天气怎么样？");
        List<Map<String, Object>> calls = List.of(Map.of("id", "call_1", "type", "function",
                "function", Map.of("name", "weather", "arguments", "{\"city\":\"北京\"}")));
        recorder.model(exchange(List.of(system, user),
                new RunTrace.ModelReply("mimo-v2.6-flash", null, "需要先查天气", calls, "tool_calls", 120L, 30L)));
        String bigResult = "{\"hourly\":[" + "{\"t\":\"10:00\",\"temp\":25},".repeat(1500) + "{}]}";
        recorder.tool(new RunTrace.ToolExchange(run, "alice", "执行#attempt-1", "执行", "call_1", "weather",
                "{\"city\":\"北京\"}", bigResult, 6000, "success", null, System.currentTimeMillis(), 300, "META"));
        Map<String, Object> assistant = new LinkedHashMap<>(message("assistant", ""));
        assistant.put("tool_calls", calls);
        assistant.put("reasoning_content", "需要先查天气");
        Map<String, Object> tool = new LinkedHashMap<>(message("tool", "晴，25 度"));
        tool.put("tool_call_id", "call_1");
        recorder.model(exchange(List.of(system, user, assistant, tool),
                new RunTrace.ModelReply("mimo-v2.6-flash", "北京今天晴，25 度。", null, List.of(), "stop", 200L, 20L)));
        recorder.event(run, "step_start", "{\"stepId\":\"执行\",\"displayName\":\"执行\"}");
        recorder.event(run, "token", "{\"content\":\"北\"}");
        assertEquals(4, store.drainNow());

        List<Map<String, Object>> nodes = store.nodes(run);
        assertEquals(List.of("MODEL", "TOOL", "MODEL", "EVENT"), nodes.stream().map(n -> n.get("kind")).toList());
        assertEquals("the retry suffix is not part of the step", "执行", nodes.get(0).get("step"));
        assertEquals("a platform tool points at the request that asked for it", 1, nodes.get(1).get("parentSeq"));
        Map<?, ?> second = (Map<?, ?>) nodes.get(2).get("summary");
        assertEquals(4, second.get("messages"));
        assertEquals("only the assistant call and the tool result are stored again", 2, second.get("newMessages"));

        JsonNode restored = store.node(run, 3);
        JsonNode messages = restored.path("request").path("messages");
        assertEquals(4, messages.size());
        assertEquals(systemPrompt, messages.get(0).path("content").asText());
        assertEquals("需要先查天气", messages.get(2).path("reasoning_content").asText());
        assertEquals("weather", restored.path("request").path("tools").get(0).path("function").path("name").asText());
        assertEquals("北京今天晴，25 度。", restored.path("reply").path("content").asText());
        assertEquals(bigResult, store.node(run, 2).path("result").asText());
        assertEquals("system prompt, tool definitions and the long result are each stored once", Integer.valueOf(3),
                db.queryForObject("SELECT COUNT(*) FROM ai_run_trace_blob WHERE run_id=?", Integer.class, run));
    }

    @Test
    public void helperScopesAndForeignOwnersAreNotTraced() {
        Map<String, Object> request = Map.of("model", "m", "messages", List.of(message("user", "hi")));
        recorder.model(new RunTrace.ModelExchange("model:WORKSPACE_DRAFT:" + UUID.randomUUID(), "alice", null, "api", null,
                0, 1, request, 200, null, RunTrace.OUTCOME_OK, null));
        recorder.model(new RunTrace.ModelExchange(run, "alice", null, "api", null, 0, 1, request, 200, null, RunTrace.OUTCOME_OK, null));
        recorder.model(new RunTrace.ModelExchange(run, "mallory", null, "api", null, 0, 1, request, 200, null, RunTrace.OUTCOME_OK, null));
        assertEquals(1, store.drainNow());
        assertEquals("alice", store.owner(run));
    }

    @Test
    public void aResumedRunContinuesNumbering() {
        Map<String, Object> request = Map.of("model", "m", "messages", List.of(message("user", "hi")));
        RunTrace.ModelExchange first = new RunTrace.ModelExchange(run, "alice", null, "api", null, 0, 1, request, 200, null, RunTrace.OUTCOME_OK, null);
        recorder.model(first);
        recorder.model(first);
        recorder.finishRun(run);
        recorder.model(first);
        store.drainNow();
        // A new process has no memory of the run; the stored rows decide.
        new TraceRecorder(store).model(first);
        store.drainNow();
        assertEquals(List.of(1, 2, 3, 4), store.nodes(run).stream().map(n -> n.get("seq")).toList());
    }

    @Test
    public void tracesGoWithTheConversationOrAccountAndRetentionKeepsRunsWithFeedback() {
        Map<String, Object> request = Map.of("model", "m", "messages", List.of(message("user", "hi")));
        String kept = UUID.randomUUID().toString(), old = UUID.randomUUID().toString();
        for (String id : List.of(run, kept, old)) {
            recorder.model(new RunTrace.ModelExchange(id, "alice", null, "api", null, 0, 1, request, 200, null, RunTrace.OUTCOME_OK, null));
        }
        store.drainNow();
        db.update("INSERT INTO ai_run_history(run_id,user_id,session_id) VALUES(?,?,?)", run, "alice", "s1");
        assertEquals(1, store.deleteSession("alice", "s1"));
        assertTrue(store.nodes(run).isEmpty());

        db.update("UPDATE ai_run_trace SET started_at=?", new Timestamp(System.currentTimeMillis() - Duration.ofDays(40).toMillis()));
        db.update("INSERT INTO ai_run_feedback(owner_user_id,run_id) VALUES('alice',?)", kept);
        assertEquals(1, store.purgeOlderThan(30, 1));
        assertFalse(store.nodes(kept).isEmpty());
        assertTrue(store.nodes(old).isEmpty());
        assertEquals(1, store.deleteUser("alice"));
        assertTrue(store.nodes(kept).isEmpty());
    }

    @Test
    public void streamedRepliesAreReassembledAcrossBuffers() {
        ModelReplies.Stream stream = new ModelReplies.Stream();
        String sse = "data: {\"model\":\"mimo\",\"choices\":[{\"delta\":{\"reasoning_content\":\"先\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想想\",\"content\":\"北京\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"ci\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ty\\\":\\\"北京\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\n"
                + "data: [DONE]\n\n";
        byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
        // Split inside a multi-byte character and inside a frame.
        for (int i = 0; i < bytes.length; i += 7) stream.accept(java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + 7)));
        stream.finish();
        RunTrace.ModelReply reply = stream.reply();
        assertTrue(stream.done());
        assertEquals("先想想", reply.reasoning());
        assertEquals("北京", reply.content());
        assertEquals("tool_calls", reply.finishReason());
        assertEquals("{\"city\":\"北京\"}", ((Map<?, ?>) reply.toolCalls().get(0).get("function")).get("arguments"));
        assertEquals(Long.valueOf(11), reply.inputTokens());

        ModelReplies.Stream error = new ModelReplies.Stream();
        error.accept("{\"error\":{\"message\":\"Unsupported model\"}}".getBytes(StandardCharsets.UTF_8));
        error.finish();
        assertNull(error.reply());
        assertTrue(error.raw().contains("Unsupported model"));
    }

    @Test
    public void theStreamingFilterRecordsTheRequestAsSentAndTheReassembledReply() throws Exception {
        RunTrace.install(recorder);
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(5), 10, 10, 100000));
        String body = json.writeValueAsString(Map.of("model", "mimo", "stream", true,
                "messages", List.of(message("system", "简短回答"), message("user", "你好"))));
        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://model.test/v1/chat/completions"))
                .body(BodyInserters.fromValue(body)).build();
        AtomicReference<String> forwarded = new AtomicReference<>();
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好！\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        ClientResponse response = new ModelTraceFilter("api-1", null).filter(request, sent -> {
            forwarded.set(new String(cn.bugstack.ai.domain.agent.service.trace.ClientRequestBodies.bytes(sent), StandardCharsets.UTF_8));
            DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(sse.getBytes(StandardCharsets.UTF_8));
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(Flux.just(buffer)).build());
        }).contextWrite(Context.of(ReasoningContentFilter.RUN_CONTEXT_KEY, run)).block();
        assertEquals("the model still receives the original body", body, forwarded.get());
        assertEquals(sse, response.bodyToMono(String.class).block());
        store.drainNow();
        JsonNode node = store.node(run, 1);
        assertEquals("你好！", node.path("reply").path("content").asText());
        assertEquals("你好", node.path("request").path("messages").get(1).path("content").asText());
        assertEquals("OK", store.nodes(run).get(0).get("status"));
    }

    @Test
    public void onlyTheOwnerAndAdministratorsReadATraceAndTextsAreMaskedForDisplay() {
        Map<String, Object> request = Map.of("model", "m", "messages", List.of(message("user", "我的手机号是 13812345678，帮我记一下")));
        recorder.model(new RunTrace.ModelExchange(run, "alice", null, "api", null, 0, 1, request, 200, null, RunTrace.OUTCOME_OK, null));
        store.drainNow();
        var history = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository.class);
        org.mockito.Mockito.when(history.findOwned(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        var controller = new cn.bugstack.ai.trigger.workspace.RunHistoryController(history, null, store);
        var alice = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice", null, List.of());
        var bob = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bob", null, List.of());
        var admin = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("root", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        String shown = String.valueOf(controller.traceNode(alice, run, 1));
        assertFalse("the stored text is not shown unmasked", shown.contains("13812345678"));
        assertTrue(shown.contains("帮我记一下"));
        assertNotNull(controller.trace(admin, run));
        try {
            controller.trace(bob, run);
            fail("another user cannot read the trace");
        } catch (org.springframework.web.server.ResponseStatusException expected) {
            assertEquals(404, expected.getStatusCode().value());
        }
        assertTrue("internal storage keeps the original for the review agent",
                store.node(run, 1).toString().contains("13812345678"));
    }

    @Test
    public void failedRequestsAreTracedWithTheProviderError() throws Exception {
        RunTrace.install(recorder);
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(5), 10, 10, 100000));
        String body = json.writeValueAsString(Map.of("model", "mimo-v2.5", "stream", true, "messages", List.of(message("user", "你好"))));
        ModelTraceFilter filter = new ModelTraceFilter("api-1", null);
        ClientResponse rejected = filter.filter(ClientRequest.create(HttpMethod.POST, URI.create("http://model.test/v1/chat/completions"))
                .body(BodyInserters.fromValue(body)).build(), sent -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("{\"error\":{\"message\":\"Unsupported model mimo-v2.5\"}}"
                        .getBytes(StandardCharsets.UTF_8)))).build()))
                .contextWrite(Context.of(ReasoningContentFilter.RUN_CONTEXT_KEY, run)).block();
        rejected.bodyToMono(String.class).block();
        try {
            filter.filter(ClientRequest.create(HttpMethod.POST, URI.create("http://model.test/v1/chat/completions"))
                    .body(BodyInserters.fromValue(body)).build(), sent -> Mono.error(new java.io.IOException("Connection reset")))
                    .contextWrite(Context.of(ReasoningContentFilter.RUN_CONTEXT_KEY, run)).block();
            fail("the transport error reaches the caller unchanged");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), String.valueOf(expected.getMessage()).contains("Connection reset"));
        }
        store.drainNow();
        List<Map<String, Object>> nodes = store.nodes(run);
        assertEquals(List.of("ERROR", "ERROR"), nodes.stream().map(n -> n.get("status")).toList());
        assertTrue(store.node(run, 1).path("error").asText().contains("Unsupported model"));
        assertEquals(400, store.node(run, 1).path("httpStatus").asInt());
        assertTrue(store.node(run, 2).path("error").asText().contains("Connection reset"));
        assertEquals("no response arrived", 0, store.node(run, 2).path("httpStatus").asInt());
    }
}
