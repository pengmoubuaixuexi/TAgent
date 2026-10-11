package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static cn.bugstack.ai.trigger.eval.EvalJudgeResponseSupportTest.VALID;
import static cn.bugstack.ai.trigger.eval.EvalJudgeResponseSupportTest.response;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only loopback HTTP fixtures and mocked accounting; no provider calls or database writes. */
public class EvalJudgeResponseRetryTest {
    private record Reply(int status, String body) { }
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private EvalJudgeService service;
    private AiClientApiVO api;
    private HttpServer server;
    private Map<String, String> originalMdc;
    private ModelUsageAccounting ledger;
    private String customBudgetRun;

    @Before public void setup() throws Exception {
        originalMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("userId", "alice");
        MDC.put("runId", "model:EVAL_JUDGE:test");
        service = new EvalJudgeService(mock(IAiEvalOpsDao.class), mock(IAgentRepository.class));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.add(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "unexpected extra request");
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("x-request-id", "request-fixture-" + requests.size());
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        api = AiClientApiVO.builder().apiId("fixture-api").baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("SYNTHETIC_KEY_MUST_NOT_BE_DIAGNOSTIC").build();
    }

    @After public void cleanup() {
        if (customBudgetRun != null) RunBudgetRegistry.finish(customBudgetRun);
        if (ledger != null) ModelUsageLedger.uninstall(ledger);
        Thread.interrupted();
        if (originalMdc == null) MDC.clear(); else MDC.setContextMap(originalMdc);
        server.stop(0);
        service.shutdown();
    }

    @Test public void emptyReplyIsRetriedOnceAndAllAttemptsHaveSafeCorrelatedDiagnostics() {
        enqueue("", "stop");
        enqueue(null, "stop");
        var error = assertThrows(EvalJudgeResponseSupport.JudgeFailure.class, this::call);
        assertEquals("EMPTY_CONTENT", error.getCode());
        assertEquals(2, requests.size());
        JSONObject diagnostic = JSON.parseObject(error.diagnosticsJson());
        assertEquals("result-46", diagnostic.getString("resultId"));
        assertEquals("judge-job-1", diagnostic.getString("judgeJobId"));
        assertEquals("model:EVAL_JUDGE:test", diagnostic.getString("runId"));
        assertEquals(2, diagnostic.getJSONArray("attempts").size());
        assertEquals("request-fixture-2", diagnostic.getJSONArray("attempts").getJSONObject(1).getString("requestId"));
        assertEquals(3000, requests.get(1).getIntValue("max_tokens"));
        assertFalse(error.diagnosticsJson().contains(api.getApiKey()));
        assertFalse(error.diagnosticsJson().contains("PRIVATE_REASONING"));
        assertFalse(error.diagnosticsJson().contains("PRIVATE_PROMPT"));
    }

    @Test public void malformedScoringJsonCanRecoverWithoutASecondNestedRetryBudget() throws Exception {
        enqueue("{broken", "stop");
        enqueue(VALID, "stop");
        assertEquals(92, call().parsed().getIntValue("overall"));
        assertEquals(2, requests.size());
    }

    @Test public void invalidScoringSchemaIsRetriedRatherThanPersistingCoercedScores() throws Exception {
        enqueue(VALID.replace("\"correctness\":5", "\"correctness\":\"5\""), "stop");
        enqueue(VALID, "stop");
        assertEquals(5, call().parsed().getIntValue("correctness"));
        assertEquals(2, requests.size());
    }

    @Test public void truncationRaisesOutputLimitOnlyForTheSingleGuardedRetry() throws Exception {
        enqueue(VALID, "length");
        enqueue(VALID, "stop");
        assertEquals(VALID, call().raw());
        assertEquals(2, requests.size());
        assertEquals(3000, requests.get(0).getIntValue("max_tokens"));
        assertEquals(6000, requests.get(1).getIntValue("max_tokens"));
        assertFalse(requests.get(1).containsKey("thinking"));
        assertFalse(requests.get(1).containsKey("enable_thinking"));
    }

    @Test public void transportAndContentFailuresShareTwoTotalRequests() {
        replies.add(new Reply(503, "FAKE_SECRET_IN_PROVIDER_ERROR"));
        enqueue("", "stop");
        var error = assertThrows(EvalJudgeResponseSupport.JudgeFailure.class, this::call);
        assertEquals("EMPTY_CONTENT", error.getCode());
        assertEquals(2, requests.size());
        var attempts = JSON.parseObject(error.diagnosticsJson()).getJSONArray("attempts");
        assertEquals("HTTP_ERROR", attempts.getJSONObject(0).getString("code"));
        assertEquals(503, attempts.getJSONObject(0).getIntValue("httpStatus"));
        assertFalse(error.diagnosticsJson().contains("FAKE_SECRET"));
    }

    @Test public void invalidEnvelopeAndMissingChoicesAreDiagnosable() {
        replies.add(new Reply(200, "not json"));
        replies.add(new Reply(200, "{\"choices\":[],\"usage\":{\"total_tokens\":12}}"));
        var error = assertThrows(EvalJudgeResponseSupport.JudgeFailure.class, this::call);
        assertEquals("MISSING_CHOICES", error.getCode());
        assertEquals(2, requests.size());
        var attempts = JSON.parseObject(error.diagnosticsJson()).getJSONArray("attempts");
        assertEquals("INVALID_RESPONSE_JSON", attempts.getJSONObject(0).getString("code"));
        assertEquals(12, attempts.getJSONObject(1).getJSONObject("usage").getIntValue("total_tokens"));
    }

    @Test public void nonRetryableHttpErrorsDoNotSpendAnotherAttempt() {
        replies.add(new Reply(401, "provider key diagnostic should not be stored"));
        var error = assertThrows(EvalJudgeResponseSupport.JudgeFailure.class, this::call);
        assertEquals("HTTP_ERROR", error.getCode());
        assertEquals(1, requests.size());
    }

    @Test public void preCancelledThreadNeverSendsHttpRequest() {
        Thread.currentThread().interrupt();
        assertThrows(java.util.concurrent.CancellationException.class, this::call);
        assertEquals(0, requests.size());
    }

    @Test public void revokedAccessStopsRetryBeforeAnotherProviderCall() {
        ModelProviderAccess access = mock(ModelProviderAccess.class);
        ReflectionTestUtils.setField(service, "modelAccess", access);
        when(access.apiKeyForRequest(eq("fixture-api"), eq("alice"), any(URI.class)))
                .thenReturn("SYNTHETIC_FIRST_KEY").thenThrow(new SecurityException("access revoked"));
        enqueue("", "stop");
        assertThrows(SecurityException.class, this::call);
        assertEquals(1, requests.size());
        verify(access, times(2)).apiKeyForRequest(eq("fixture-api"), eq("alice"), any(URI.class));
    }

    @Test public void retryStillHonorsRunBudgetBeforeSendingHttpRequest() {
        customBudgetRun = "model:EVAL_JUDGE:" + UUID.randomUUID();
        RunBudgetRegistry.start(customBudgetRun, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 1, 1, 10000));
        MDC.put("runId", customBudgetRun);
        enqueue("", "length");
        assertThrows(RunBudgetExceededException.class, this::call);
        assertEquals(1, requests.size());
    }

    @Test public void emptyScoringResponseRetainsBilledUsageButIsNotRecordedAsSuccess() throws Exception {
        ledger = mock(ModelUsageAccounting.class);
        ModelUsageAccounting.Ticket first = mock(ModelUsageAccounting.Ticket.class);
        ModelUsageAccounting.Ticket second = mock(ModelUsageAccounting.Ticket.class);
        when(ledger.begin(any())).thenReturn(first, second);
        ModelUsageLedger.install(ledger);
        enqueue("", "stop");
        enqueue(VALID, "stop");
        try (var ignored = ModelInvocationScope.open(new RunBudgetRegistry(), "alice", "EVAL_JUDGE")) {
            assertEquals(92, call().parsed().getIntValue("overall"));
        }
        verify(first).settle(35L, "ERROR");
        verify(first, never()).settle(any(), eq("SUCCESS"));
        verify(second).settle(35L, "SUCCESS");
        verify(ledger, times(2)).begin(any());
    }

    private void enqueue(String content, String finishReason) {
        replies.add(new Reply(200, response(content, finishReason, "PRIVATE_REASONING_DO_NOT_STORE").toJSONString()));
    }

    private EvalJudgeResponseSupport.ScoredReply call() throws Exception {
        return service.call(api, "fixture-model", "PRIVATE_PROMPT_DO_NOT_STORE", "judge-job-1", "result-46");
    }
}
