package cn.bugstack.ai.domain.agent.service.security;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D 段：{@code ask_user} 工具的阻塞通道 —— LLM 主动发起的人工补充。
 * <p>
 * 与 {@link HumanApprovalGate} 同构（{@code ConcurrentHashMap<id, future> + emit SSE + future.get(timeout) + resolve}），
 * 区别：
 * <ul>
 *   <li>future 是 {@code CompletableFuture<String>}（用户自由文本回答），不是审批 boolean；</li>
 *   <li>多了「每次执行最多问 {@code maxAsks} 次」的预算（按 sessionId 计数，执行入口 {@link #reset} 清零）；</li>
 *   <li>SSE 事件名 {@code user_input_required}，回填端点 {@code POST /api/v1/agent/user-input}。</li>
 * </ul>
 * <b>串话防护</b>：照抄审批 gate 的 {@link AtomicLong} 自增序号生成全局唯一 {@code inputId}，
 * 独立 {@link #pendingInputs} map —— DAG 并行 / 同批多问 / 与审批同时阻塞都各解各的 future，互不覆盖。
 * <p>
 * <b>零影响</b>：{@code agent.user-input.enabled=false}（默认）时，调用方（{@code RobustToolCallingManager}）
 * 既不广播 ask_user 工具定义、也不会进入本 gate，行为与现状逐字节一致。
 */
@Slf4j
@Component
public class UserInputGate {

    /** 提问结果状态。区分「拿到回答 / 超时 / 通道缺失 / 预算用尽」，调用方据此返不同结构化提示给 LLM。 */
    public enum Status {
        ANSWERED,
        TIMEOUT,
        /** 通道缺失（无 emitter）—— 不阻塞、不静默放行。 */
        UNAVAILABLE,
        /** 本次执行提问次数已用完。 */
        BUDGET_EXCEEDED
    }

    public static final class Result {
        public final Status status;
        /** 仅 {@link Status#ANSWERED} 时非空。 */
        public final String answer;
        public Result(Status status, String answer) {
            this.status = status;
            this.answer = answer;
        }
    }

    @Value("${agent.user-input.enabled:false}")
    private boolean enabled;

    @Value("${agent.user-input.max-asks:2}")
    private int maxAsks;

    @Value("${agent.user-input.timeout-seconds:60}")
    private int timeoutSeconds;

    @Resource
    private ApprovalChannelRegistry approvalChannelRegistry;

    @Resource
    private RunEventPublisher runEventPublisher;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingInputs = new ConcurrentHashMap<>();

    /** inputId 自增序号：同批多问 / 并发提问时保证 inputId 唯一、future 不互相覆盖。 */
    private final AtomicLong inputSeq = new AtomicLong();

    /** sessionId -> 本次执行已提问次数。执行入口 {@link #reset} 清零。 */
    private final ConcurrentHashMap<String, AtomicInteger> usedCount = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxAsks() {
        return maxAsks;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** 每次执行入口（SSE emitter 注册处）调用，清零该 session 的提问计数，避免 session 复用时额度残留。 */
    public void reset(String sessionId) {
        if (sessionId == null) return;
        usedCount.remove(sessionId);
    }

    /**
     * 剩余可问次数（只读，不消费）。供 {@code resolveToolDefinitions} 决定是否广播 ask_user 工具定义。
     * enabled=false / sessionId 空 → 0（不广播）。
     */
    public int remainingFor(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) return 0;
        AtomicInteger c = usedCount.get(sessionId);
        int used = c == null ? 0 : c.get();
        return Math.max(0, maxAsks - used);
    }

    /**
     * 阻塞式向用户提问。
     * @param sessionId 当前会话；用于反查 emitter + 预算计数
     * @param argsJson  ask_user 工具入参原文。questions 兼容字符串和
     *                  {@code {question, options, allowFreeText}} 两种元素格式
     * @param stepLabel 当前步骤标签（可空），仅供前端标注「哪个步骤要问的」
     */
    public Result requestUserInput(String sessionId, String argsJson, String stepLabel) {
        if (!enabled) return new Result(Status.UNAVAILABLE, null);

        if (runEventPublisher.currentRunId(sessionId) == null) {
            log.warn("[UserInput] active run missing sessionId={}, returning UNAVAILABLE", sessionId);
            return new Result(Status.UNAVAILABLE, null);
        }

        // 预算：实际发起提问时才消费
        AtomicInteger c = usedCount.computeIfAbsent(sessionId, k -> new AtomicInteger());
        if (c.incrementAndGet() > maxAsks) {
            c.decrementAndGet();
            log.info("[UserInput] budget exceeded sessionId={} maxAsks={}", sessionId, maxAsks);
            return new Result(Status.BUDGET_EXCEEDED, null);
        }

        String inputId = sessionId + ":ask_user:" + System.currentTimeMillis() + ":" + inputSeq.incrementAndGet();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingInputs.put(inputId, future);

        try {
            runEventPublisher.publishCurrent(sessionId, "user_input_required",
                    buildPayload(inputId, argsJson, stepLabel));
            log.info("[UserInput] requested inputId={} step={}", inputId, stepLabel);
            String answer = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("[UserInput] answered inputId={} len={}", inputId, answer != null ? answer.length() : 0);
            publishResult(sessionId, inputId, "ANSWERED");
            return new Result(Status.ANSWERED, answer);
        } catch (TimeoutException e) {
            log.warn("[UserInput] timeout inputId={}", inputId);
            publishResult(sessionId, inputId, "TIMEOUT");
            return new Result(Status.TIMEOUT, null);
        } catch (Exception e) {
            log.warn("[UserInput] failed inputId={}: {}", inputId, e.toString());
            return new Result(Status.UNAVAILABLE, null);
        } finally {
            pendingInputs.remove(inputId);
        }
    }

    private void publishResult(String sessionId, String inputId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputId", inputId);
        payload.put("status", status);
        runEventPublisher.publishCurrent(sessionId, "user_input_result", payload);
    }

    /** 前端回填入口（由 Controller 调用）。 */
    public boolean resolveUserInput(String inputId, String answer) {
        CompletableFuture<String> future = pendingInputs.get(inputId);
        return future != null && future.complete(answer == null ? "" : answer);
    }

    /**
     * 把工具入参解析摊平成前端易用的 payload。
     * <p>兼容约定：{@code questions} 始终输出字符串列表，保证旧前端和历史事件可继续读取；
     * 结构化问题另以 {@code questionDetails} 输出，快捷选项只负责填充最终文本回答，不改变
     * {@code POST /user-input} 的 {@code answer: String} 协议。
     * 解析失败时退回 {@code raw} 原文，保证前端永远能拿到 inputId。
     */
    private String buildPayload(String inputId, String argsJson, String stepLabel) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputId", inputId);
        try {
            JsonNode node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (node.hasNonNull("context")) {
                map.put("context", node.get("context").asText());
            }
            if (node.has("questions") && node.get("questions").isArray()) {
                List<String> qs = new ArrayList<>();
                List<Map<String, Object>> questionDetails = new ArrayList<>();
                node.get("questions").forEach(q -> {
                    if (q.isTextual()) {
                        if (!q.asText().isBlank()) qs.add(q.asText());
                        return;
                    }
                    if (!q.isObject()) return;

                    String question = textValue(q, "question");
                    if (question == null || question.isBlank()) return;
                    qs.add(question);

                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("question", question);
                    List<Map<String, String>> options = normalizeOptions(q.get("options"));
                    if (!options.isEmpty()) detail.put("options", options);
                    detail.put("allowFreeText", !q.has("allowFreeText") || q.get("allowFreeText").asBoolean(true));
                    questionDetails.add(detail);
                });
                if (!qs.isEmpty()) map.put("questions", qs);
                if (!questionDetails.isEmpty()) map.put("questionDetails", questionDetails);
            }
        } catch (Exception e) {
            map.put("raw", argsJson != null ? argsJson : "");
        }
        if (stepLabel != null && !stepLabel.isBlank()) {
            map.put("step", stepLabel);
        }
        map.put("timeoutSeconds", timeoutSeconds);
        map.put("expiresAt", System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds));
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("[UserInput] json serialize fallback inputId={}: {}", inputId, e.toString());
            return "{\"inputId\":\"" + inputId.replace("\"", "") + "\",\"timeoutSeconds\":" + timeoutSeconds + "}";
        }
    }

    /** 把字符串/对象选项统一成前端可直接渲染的 label/value/description。 */
    private List<Map<String, String>> normalizeOptions(JsonNode optionsNode) {
        List<Map<String, String>> options = new ArrayList<>();
        if (optionsNode == null || !optionsNode.isArray()) return options;
        optionsNode.forEach(option -> {
            String label;
            String value;
            String description = null;
            if (option.isTextual()) {
                label = option.asText();
                value = label;
            } else if (option.isObject()) {
                label = textValue(option, "label");
                value = textValue(option, "value");
                description = textValue(option, "description");
                if (label == null || label.isBlank()) label = value;
                if (value == null || value.isBlank()) value = label;
            } else {
                return;
            }
            if (label == null || label.isBlank()) return;
            Map<String, String> normalized = new LinkedHashMap<>();
            normalized.put("label", label);
            normalized.put("value", value);
            if (description != null && !description.isBlank()) normalized.put("description", description);
            options.add(normalized);
        });
        return options;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
