package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailureClassifier;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

/** Real provider responses collected on 2026-10-24 (mimo and DeepSeek), plus documented ones. */
public class ModelFailureClassifierTest {
    private static final ModelTarget TARGET = new ModelTarget("api", "model");

    private static ModelFailure classify(int status, String body) {
        return ModelFailureClassifier.classify(TARGET, status, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void modelAndAccountProblemsSwitch() {
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, classify(400, "{\n    \"error\": {\n        \"code\": \"400\",\n        \"message\": \"Unsupported model mimo-v2.5\"\n    }\n}").kind());
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, classify(400, "{\"error\":{\"message\":\"The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed deepseek-nope. (request_id: aa29e25b-d01b-43fb-8ca2-51ad9bf9e85e)\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_request_error\"}}").kind());
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, classify(401, "{\"error\":{\"message\":\"Invalid API Key\",\"param\":\"Please provide valid API Key\",\"code\":\"401\",\"type\":\"invalid_key\"}}").kind());
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, classify(402, "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}").kind());
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, classify(404, "<html><head><title>404 Not Found</title></head><body><center><h1>404 Not Found</h1></center><hr><center>openresty</center></body></html>").kind());
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, classify(429, "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\"}}").kind());
    }

    @Test public void networkOverloadAndRateLimitsOnlyRetry() {
        assertEquals(ModelFailure.Kind.TRANSIENT, classify(503, "{\"error\":{\"message\":\"Server Overloaded\"}}").kind());
        assertEquals(ModelFailure.Kind.TRANSIENT, classify(500, "").kind());
        assertEquals(ModelFailure.Kind.RATE_LIMITED, classify(429, "{\"error\":{\"message\":\"Rate Limit Reached\"}}").kind());
        assertEquals(ModelFailure.Kind.TRANSIENT, ModelFailureClassifier.classify(TARGET, new RuntimeException(new ConnectException("refused"))).kind());
        assertNull("cancellation and budget stops are not provider failures",
                ModelFailureClassifier.classify(TARGET, new CancellationException("任务已取消")));
    }

    @Test public void requestProblemsAreReturnedUnchanged() {
        assertEquals(ModelFailure.Kind.REQUEST_REJECTED, classify(400, "{\"error\":{\"code\":\"400\",\"message\":\"Param Incorrect\",\"param\":\"temperature must be within [0, 1.5]\",\"type\":\"\"}}").kind());
        assertEquals(ModelFailure.Kind.REQUEST_REJECTED, classify(400, "{\"error\":{\"message\":\"The `reasoning_content` in the thinking mode must be passed back to the API. (request_id: 975cc354-f014-4a3b-9e1c-fa7633db9b7a)\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_request_error\"}}").kind());
        assertEquals(ModelFailure.Kind.REQUEST_REJECTED, classify(400, "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens\"}}").kind());
    }

    @Test public void providerTextIsRedactedAndNeverPartOfTheSafeReason() {
        ModelFailure failure = classify(401, "{\"error\":{\"message\":\"Authentication Fails, Your api key: ****test is invalid (request_id: f6e6d7c8-d143-4987-b203-7ad7749a916b)\",\"type\":\"authentication_error\"}}");
        assertFalse(failure.message(), failure.message().contains("****test"));
        assertFalse(failure.message(), failure.message().contains("f6e6d7c8"));
        assertTrue(failure.message(), failure.message().startsWith("Authentication Fails"));
        assertEquals("鉴权失败，密钥无效或已撤销，HTTP 401", failure.describe());
        assertEquals("Param Incorrect：temperature must be within [0, 1.5]",
                classify(400, "{\"error\":{\"code\":\"400\",\"message\":\"Param Incorrect\",\"param\":\"temperature must be within [0, 1.5]\"}}").message());
        assertEquals("服务返回了网页错误", classify(404, "<html>404</html>").message());
    }

    @Test public void errorFramesInsideAStreamUseTheProviderCode() throws Exception {
        var json = new ObjectMapper();
        assertEquals(ModelFailure.Kind.MODEL_UNAVAILABLE, ModelFailureClassifier.classifyStreamError(TARGET,
                json.readTree("{\"code\":\"400\",\"message\":\"Unsupported model mimo-v2.5\"}")).kind());
        assertEquals(ModelFailure.Kind.TRANSIENT, ModelFailureClassifier.classifyStreamError(TARGET,
                json.readTree("{\"code\":\"500\",\"message\":\"internal error\"}")).kind());
        assertEquals(ModelFailure.Kind.RATE_LIMITED, ModelFailureClassifier.classifyStreamError(TARGET,
                json.readTree("{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}")).kind());
    }
}
