package cn.bugstack.ai.trigger.eval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the scoring contract and retains metadata, never provider reasoning or prompt text. */
final class EvalJudgeResponseSupport {
    private EvalJudgeResponseSupport() { }

    record ScoredReply(String raw, JSONObject parsed) { }

    static final class JudgeFailure extends IllegalArgumentException {
        private final String code;
        private final String diagnostics;

        JudgeFailure(String code, String message) { this(code, message, null); }

        private JudgeFailure(String code, String message, String diagnostics) {
            super(message);
            this.code = code;
            this.diagnostics = diagnostics;
        }

        String getCode() { return code; }
        String diagnosticsJson() { return diagnostics; }

        JudgeFailure withDiagnostics(String runId, String judgeJobId, String resultId,
                                     List<Map<String, Object>> attempts) {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("version", 1);
            diagnostic.put("type", "JUDGE_DIAGNOSTICS");
            diagnostic.put("code", code);
            putIdentifier(diagnostic, "runId", runId);
            putIdentifier(diagnostic, "judgeJobId", judgeJobId);
            putIdentifier(diagnostic, "resultId", resultId);
            diagnostic.put("attempts", attempts.subList(0, Math.min(2, attempts.size())));
            return new JudgeFailure(code, getMessage(), JSON.toJSONString(diagnostic));
        }
    }

    static ScoredReply readResponse(JSONObject response, Map<String, Object> metadata) {
        if (response == null) throw new JudgeFailure("INVALID_RESPONSE", "Judge 接口返回结构为空");
        putIdentifier(metadata, "responseId", response.get("id"));
        copyUsage(response, metadata);
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof JSONArray choices) || choices.isEmpty()
                || !(choices.get(0) instanceof JSONObject choice)) {
            throw new JudgeFailure("MISSING_CHOICES", "Judge 接口未返回可用的 choices");
        }
        Object finishReason = choice.get("finish_reason");
        putIdentifier(metadata, "finishReason", finishReason);
        Object messageValue = choice.get("message");
        JSONObject message = messageValue instanceof JSONObject object ? object : null;
        Object content = message == null ? null : message.get("content");
        Object reasoning = message == null ? null : message.get("reasoning_content");
        metadata.put("contentPresent", content != null);
        metadata.put("reasoningPresent", reasoning != null);
        metadata.put("contentLength", content instanceof String value ? value.length() : 0);
        metadata.put("reasoningLength", reasoning instanceof String value ? value.length() : 0);
        // Even syntactically complete JSON is not trustworthy when the provider marks it truncated.
        if ("length".equals(finishReason))
            throw new JudgeFailure("OUTPUT_TRUNCATED", "Judge 输出达到长度上限，评分未被接受");
        if (message == null) throw new JudgeFailure("INVALID_RESPONSE", "Judge 接口缺少 message 对象");
        if (content != null && !(content instanceof String))
            throw new JudgeFailure("INVALID_RESPONSE", "Judge 接口 content 不是文本");
        String raw = (String) content;
        JSONObject parsed = parseJudgeJson(raw);
        return new ScoredReply(raw, parsed);
    }

    static JSONObject parseJudgeJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equals(value))
            throw new JudgeFailure("EMPTY_CONTENT", "Judge 未返回评分正文（为空），不是零分");
        // Retain compatibility with providers wrapping the object in a Markdown fence.
        int start = value.indexOf('{'), end = value.lastIndexOf('}');
        if (start >= 0 && end > start) value = value.substring(start, end + 1);
        JSONObject parsed;
        try { parsed = JSON.parseObject(value); }
        catch (RuntimeException ignored) {
            throw new JudgeFailure("INVALID_JSON", "Judge 评分正文不是有效 JSON");
        }
        if (parsed == null) throw new JudgeFailure("EMPTY_CONTENT", "Judge 未返回评分正文（为空），不是零分");
        for (String field : List.of("correctness", "relevance", "completeness", "usefulness", "safety"))
            score(parsed, field, 0, 5);
        score(parsed, "overall", 0, 100);
        if (!(parsed.get("verdict") instanceof String verdict)
                || !Set.of("EXCELLENT", "GOOD", "FAIR", "POOR").contains(verdict))
            throw new JudgeFailure("INVALID_SCHEMA", "Judge 字段 verdict 必须是有效的评价等级");
        if (!(parsed.get("reason") instanceof String reason) || reason.isBlank())
            throw new JudgeFailure("INVALID_SCHEMA", "Judge 字段 reason 必须是非空文本");
        if (!(parsed.get("issues") instanceof JSONArray issues)
                || issues.stream().anyMatch(item -> !(item instanceof String)))
            throw new JudgeFailure("INVALID_SCHEMA", "Judge 字段 issues 必须是文本数组");
        return parsed;
    }

    static int score(JSONObject object, String field, int min, int max) {
        Object value = object.get(field);
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger))
            throw new JudgeFailure("INVALID_SCHEMA", "Judge 字段必须是整数：" + field);
        BigInteger integer = new BigInteger(value.toString());
        if (integer.compareTo(BigInteger.valueOf(min)) < 0 || integer.compareTo(BigInteger.valueOf(max)) > 0)
            throw new JudgeFailure("INVALID_SCHEMA", "Judge 字段越界：" + field);
        return integer.intValue();
    }

    static void putIdentifier(Map<String, Object> target, String field, Object value) {
        // Do not copy arbitrary provider error messages, headers, URLs or response text.
        if (value instanceof String identifier && identifier.matches("[A-Za-z0-9._:-]{1,160}"))
            target.put(field, identifier);
    }

    private static void copyUsage(JSONObject response, Map<String, Object> metadata) {
        if (!(response.get("usage") instanceof JSONObject usage)) return;
        Map<String, Object> safeUsage = new LinkedHashMap<>();
        for (String field : List.of("prompt_tokens", "completion_tokens", "total_tokens", "reasoning_tokens"))
            copyCount(usage, safeUsage, field);
        if (usage.get("completion_tokens_details") instanceof JSONObject details)
            copyCount(details, safeUsage, "reasoning_tokens");
        if (!safeUsage.isEmpty()) metadata.put("usage", safeUsage);
    }

    private static void copyCount(JSONObject source, Map<String, Object> target, String field) {
        Object value = source.get(field);
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0)
            target.put(field, ((Number) value).longValue());
    }
}
