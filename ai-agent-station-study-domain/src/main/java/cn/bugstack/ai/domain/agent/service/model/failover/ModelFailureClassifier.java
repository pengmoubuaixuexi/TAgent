package cn.bugstack.ai.domain.agent.service.model.failover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Status code first, provider wording second. Unrecognised server-side failures count as
 * transient, so a flaky network never causes a switch.
 *
 * <p>Samples (2026-10-24, real responses):
 * <ul>
 *   <li>mimo: 400 {@code {"error":{"code":"400","message":"Unsupported model mimo-v2.5"}}} (sync and stream,
 *       before any SSE); 401 {@code "Invalid API Key"}; 400 {@code "Param Incorrect"} with
 *       {@code param "temperature must be within [0, 1.5]"}; 404 openresty HTML for a wrong path.</li>
 *   <li>DeepSeek: 400 {@code "The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed X"};
 *       401 {@code "Authentication Fails, Your api key: ****test is invalid"}; 400 {@code "Invalid temperature value"};
 *       400 {@code "The `reasoning_content` in the thinking mode must be passed back to the API."};
 *       documented 402 {@code "Insufficient Balance"}, 429 {@code "Rate Limit Reached"}, 500/503.</li>
 * </ul>
 */
public final class ModelFailureClassifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MESSAGE_CHARS = 240;
    private static final Pattern MODEL_GONE = Pattern.compile(
            "unsupported model|model[ _-]?not[ _-]?found|no such model|invalid model|unknown model|model_not_found"
                    + "|supported (api )?model names|but you passed|model .{0,80}(does not exist|not exist|is not available|not supported|has been deprecated)"
                    + "|模型不存在|不支持的模型|模型.{0,10}(下线|不可用|不存在)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTA_GONE = Pattern.compile(
            "insufficient[ _-]?(quota|balance)|quota exceeded|exceeded your current quota|billing|credit balance|"
                    + "余额|欠费|额度(已)?(用尽|不足|耗尽)", Pattern.CASE_INSENSITIVE);
    /** Providers echo masked or partial keys and request ids in error text. */
    private static final Pattern SECRETS = Pattern.compile(
            "(?i)(api[ _-]?key|token|key)\\s*[:=]\\s*\\S+|\\b(sk|ak|pk)-[A-Za-z0-9*._-]{4,}|\\*{3,}\\w*|\\b[A-Za-z0-9_-]{32,}\\b");
    private static final Pattern REQUEST_ID = Pattern.compile("\\s*\\(request[ _]id:[^)]*\\)", Pattern.CASE_INSENSITIVE);

    private ModelFailureClassifier() { }

    public static ModelFailure classify(ModelTarget target, int status, byte[] body) {
        String message = message(body);
        ModelFailure.Kind kind;
        if (status == 401 || status == 402 || status == 403 || status == 404) kind = ModelFailure.Kind.MODEL_UNAVAILABLE;
        else if (status == 429) kind = QUOTA_GONE.matcher(message).find() ? ModelFailure.Kind.MODEL_UNAVAILABLE : ModelFailure.Kind.RATE_LIMITED;
        else if (status == 408 || status == 425 || status >= 500) kind = ModelFailure.Kind.TRANSIENT;
        else if (status >= 400) kind = MODEL_GONE.matcher(message).find() || QUOTA_GONE.matcher(message).find()
                ? ModelFailure.Kind.MODEL_UNAVAILABLE : ModelFailure.Kind.REQUEST_REJECTED;
        else kind = ModelFailure.Kind.TRANSIENT;
        return new ModelFailure(target, kind, status, message);
    }

    /**
     * An error object inside an HTTP 200 stream, before any output. The provider code stands in
     * for the status when it is numeric.
     */
    public static ModelFailure classifyStreamError(ModelTarget target, JsonNode error) {
        String code = error.path("code").asText("");
        String type = error.path("type").asText("");
        int status = code.matches("[1-5][0-9]{2}") ? Integer.parseInt(code)
                : type.contains("rate_limit") ? 429 : type.contains("authentication") || type.contains("invalid_key") ? 401
                : type.contains("server") || type.contains("overloaded") ? 503 : 400;
        try {
            return classify(target, status, JSON.writeValueAsBytes(java.util.Map.of("error", error)));
        } catch (IOException e) {
            return classify(target, status, new byte[0]);
        }
    }

    /** Null means "not a provider failure" (cancellation, budget, programming error): propagate unchanged. */
    public static ModelFailure classify(ModelTarget target, Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof TimeoutException)
                return new ModelFailure(target, ModelFailure.Kind.TRANSIENT, 0, bounded(t.getClass().getSimpleName()));
            if (t.getCause() == t) break;
        }
        return null;
    }

    static String message(byte[] body) {
        if (body == null || body.length == 0) return "";
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.trim().startsWith("<")) return "服务返回了网页错误";
        try {
            JsonNode root = JSON.readTree(text);
            JsonNode error = root.path("error");
            JsonNode message = error.isTextual() ? error : error.path("message");
            if (!message.isTextual()) message = root.path("message");
            if (message.isTextual()) {
                JsonNode param = error.path("param");
                String detail = param.isTextual() && !param.asText().isBlank() ? message.asText() + "：" + param.asText() : message.asText();
                return bounded(detail);
            }
        } catch (IOException notJson) {
            // Gateways sometimes answer with plain text.
        }
        return bounded(text);
    }

    private static String bounded(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        compact = SECRETS.matcher(REQUEST_ID.matcher(compact).replaceAll("")).replaceAll("[已隐藏]");
        return compact.length() <= MAX_MESSAGE_CHARS ? compact : compact.substring(0, MAX_MESSAGE_CHARS) + "…";
    }
}
