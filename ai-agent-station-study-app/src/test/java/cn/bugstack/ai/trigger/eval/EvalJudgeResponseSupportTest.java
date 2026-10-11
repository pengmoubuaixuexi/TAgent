package cn.bugstack.ai.trigger.eval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class EvalJudgeResponseSupportTest {
    static final String VALID = "{\"correctness\":5,\"relevance\":4,\"completeness\":4,\"usefulness\":5,\"safety\":5,"
            + "\"overall\":92,\"verdict\":\"EXCELLENT\",\"reason\":\"数字例子完整且正确\",\"issues\":[]}";

    @Test public void validScoringJsonAndExistingMarkdownWrapperAreAccepted() {
        assertEquals(92, EvalJudgeResponseSupport.parseJudgeJson(VALID).getIntValue("overall"));
        assertEquals(92, EvalJudgeResponseSupport.parseJudgeJson("```json\n" + VALID + "\n```").getIntValue("overall"));
    }

    @Test public void emptyAndLiteralNullAreDistinguishedFromMalformedJson() {
        for (String content : new String[] {null, "", "  \n", "null"})
            assertFailure("EMPTY_CONTENT", () -> EvalJudgeResponseSupport.parseJudgeJson(content));
        assertFailure("INVALID_JSON", () -> EvalJudgeResponseSupport.parseJudgeJson("{broken"));
    }

    @Test public void scoreMustBeAnIntegerWithoutStringBooleanFractionOrNullCoercion() {
        for (Object invalid : new Object[] {"5", 4.5, true, null, -1, 6, new java.math.BigDecimal("5.0"),
                new java.math.BigInteger("999999999999999999999999")}) {
            JSONObject value = JSON.parseObject(VALID);
            value.put("correctness", invalid);
            assertFailure("INVALID_SCHEMA", () -> EvalJudgeResponseSupport.parseJudgeJson(value.toJSONString()));
        }
        JSONObject missing = JSON.parseObject(VALID);
        missing.remove("overall");
        assertFailure("INVALID_SCHEMA", () -> EvalJudgeResponseSupport.parseJudgeJson(missing.toJSONString()));
    }

    @Test public void allRequiredNonScoreFieldsAreValidated() {
        for (String field : List.of("verdict", "reason", "issues")) {
            JSONObject missing = JSON.parseObject(VALID);
            missing.remove(field);
            assertFailure("INVALID_SCHEMA", () -> EvalJudgeResponseSupport.parseJudgeJson(missing.toJSONString()));
        }
        for (Map.Entry<String, Object> bad : Map.<String, Object>of(
                "verdict", "unknown", "reason", " ", "issues", List.of(123)).entrySet()) {
            JSONObject value = JSON.parseObject(VALID);
            value.put(bad.getKey(), bad.getValue());
            assertFailure("INVALID_SCHEMA", () -> EvalJudgeResponseSupport.parseJudgeJson(value.toJSONString()));
        }
    }

    @Test public void missingChoicesAndNonTextContentHaveActionableCodes() {
        assertFailure("MISSING_CHOICES", () -> EvalJudgeResponseSupport.readResponse(JSON.parseObject("{\"choices\":[]}"), new LinkedHashMap<>()));
        assertFailure("MISSING_CHOICES", () -> EvalJudgeResponseSupport.readResponse(JSON.parseObject("{}"), new LinkedHashMap<>()));
        assertFailure("INVALID_RESPONSE", () -> EvalJudgeResponseSupport.readResponse(
                JSON.parseObject("{\"choices\":[{\"message\":{\"content\":[]}}]}"), new LinkedHashMap<>()));
    }

    @Test public void explicitTruncationRejectsEvenCompleteScoringJsonAndPreservesOnlyMetadata() {
        JSONObject response = response(VALID, "length", "PRIVATE_REASONING_DO_NOT_STORE");
        Map<String, Object> metadata = new LinkedHashMap<>();
        assertFailure("OUTPUT_TRUNCATED", () -> EvalJudgeResponseSupport.readResponse(response, metadata));
        assertEquals("length", metadata.get("finishReason"));
        assertEquals(VALID.length(), metadata.get("contentLength"));
        assertEquals(30, metadata.get("reasoningLength"));
        String diagnostic = new EvalJudgeResponseSupport.JudgeFailure("OUTPUT_TRUNCATED", "安全错误")
                .withDiagnostics("model:EVAL_JUDGE:test", "job-1", "result-46", List.of(metadata)).diagnosticsJson();
        assertFalse(diagnostic.contains("PRIVATE_REASONING"));
        assertFalse(diagnostic.contains("数字例子"));
        assertFalse(diagnostic.contains("FAKE_PROVIDER_SECRET"));
        assertTrue(diagnostic.contains("\"reasoning_tokens\":10"));
        assertTrue(diagnostic.contains("model:EVAL_JUDGE:test"));
        assertTrue(diagnostic.length() < 2000);
    }

    @Test public void arbitraryProviderIdentifiersAndUsageFieldsAreNotCopied() {
        JSONObject response = response("", "stop", "secret");
        response.put("id", "https://provider.example?token=private");
        response.getJSONObject("usage").put("credentials", "secret");
        Map<String, Object> metadata = new LinkedHashMap<>();
        assertFailure("EMPTY_CONTENT", () -> EvalJudgeResponseSupport.readResponse(response, metadata));
        assertFalse(metadata.containsKey("responseId"));
        assertFalse(JSON.toJSONString(metadata).contains("secret"));
    }

    static JSONObject response(String content, String finishReason, String reasoning) {
        JSONObject message = new JSONObject(true);
        message.put("content", content);
        message.put("reasoning_content", reasoning);
        JSONObject choice = new JSONObject(true);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        JSONObject response = new JSONObject(true);
        response.put("id", "response-test-1");
        response.put("choices", new com.alibaba.fastjson.JSONArray(List.of(choice)));
        response.put("usage", JSON.parseObject("{\"prompt_tokens\":20,\"completion_tokens\":15,\"total_tokens\":35,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":10}}"));
        response.put("api_key", "FAKE_PROVIDER_SECRET");
        return response;
    }

    private static void assertFailure(String code, Runnable action) {
        assertEquals(code, assertThrows(EvalJudgeResponseSupport.JudgeFailure.class, action::run).getCode());
    }
}
