package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * H3 (B1) 流式工具调用进度 — 后端进度事件 emitter。
 * <p>
 * <b>目的</b>：让前端在工具真实调用阶段把"思考中..."替换为"正在调用 search_repositories... / 已完成 / 已被审批拒绝"，
 * 减少 Agent 黑箱感。复用 G1-A 的 {@link ApprovalChannelRegistry} 通道反查 emitter，零侵入。
 * <p>
 * <b>SSE 事件契约</b>（按 Codex 第 50 轮约定）：
 * <ul>
 *   <li>{@code tool_call_start}: data={sessionId, toolName, callId, step, inputPreview, timestamp}</li>
 *   <li>{@code tool_call_end}: data={sessionId, toolName, callId, step, status, latencyMs, resultChars, timestamp}</li>
 *   <li>{@code tool_call_error}: data={sessionId, toolName, callId, step, error, latencyMs, timestamp}</li>
 * </ul>
 * <p>
 * <b>边界保证</b>（Codex 第 50 轮 5 条）：
 * <ol>
 *   <li>{@code inputPreview} 截断到 {@value #INPUT_PREVIEW_MAX_CHARS} 字符避免 SSE 帧过大</li>
 *   <li>emitter == null 静默跳过，不阻塞工具调用</li>
 *   <li>所有方法不抛异常到调用方，emit 失败只 log.debug</li>
 *   <li>不修改 audit/审批/工具执行任何业务语义，只是观察层</li>
 *   <li>字段最小化，前端易接</li>
 * </ol>
 */
@Slf4j
@Component
public class ToolCallProgressEmitter {

    static final int INPUT_PREVIEW_MAX_CHARS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private ApprovalChannelRegistry approvalChannelRegistry;

    /** Spring 注入路径；测试可用此构造手动装配。 */
    public ToolCallProgressEmitter() {}

    public ToolCallProgressEmitter(ApprovalChannelRegistry registry) {
        this.approvalChannelRegistry = registry;
    }

    public void emitStart(String sessionId, String toolName, String input) {
        emitStart(sessionId, toolName, input, null);
    }

    public void emitStart(String sessionId, String toolName, String input, String step) {
        emitStart(sessionId, toolName, input, step, null);
    }

    public void emitStart(String sessionId, String toolName, String input, String step, String callId) {
        ResponseBodyEmitter emitter = lookupEmitter(sessionId);
        if (emitter == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("toolName", toolName);
        putIfPresent(data, "callId", callId);
        putIfPresent(data, "step", step);
        data.put("inputPreview", truncate(input));
        data.put("timestamp", System.currentTimeMillis());
        sendEvent(emitter, "tool_call_start", data, sessionId, toolName);
    }

    public void emitEnd(String sessionId, String toolName, String status, long latencyMs, int resultChars) {
        emitEnd(sessionId, toolName, status, latencyMs, resultChars, null);
    }

    public void emitEnd(String sessionId, String toolName, String status, long latencyMs, int resultChars, String step) {
        emitEnd(sessionId, toolName, status, latencyMs, resultChars, step, null);
    }

    public void emitEnd(String sessionId, String toolName, String status, long latencyMs, int resultChars, String step, String callId) {
        ResponseBodyEmitter emitter = lookupEmitter(sessionId);
        if (emitter == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("toolName", toolName);
        putIfPresent(data, "callId", callId);
        putIfPresent(data, "step", step);
        data.put("status", status);
        data.put("latencyMs", latencyMs);
        data.put("resultChars", resultChars);
        data.put("timestamp", System.currentTimeMillis());
        sendEvent(emitter, "tool_call_end", data, sessionId, toolName);
    }

    public void emitError(String sessionId, String toolName, String errorSummary, long latencyMs) {
        emitError(sessionId, toolName, errorSummary, latencyMs, null);
    }

    public void emitError(String sessionId, String toolName, String errorSummary, long latencyMs, String step) {
        emitError(sessionId, toolName, errorSummary, latencyMs, step, null);
    }

    public void emitError(String sessionId, String toolName, String errorSummary, long latencyMs, String step, String callId) {
        ResponseBodyEmitter emitter = lookupEmitter(sessionId);
        if (emitter == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("toolName", toolName);
        putIfPresent(data, "callId", callId);
        putIfPresent(data, "step", step);
        data.put("error", errorSummary);
        data.put("latencyMs", latencyMs);
        data.put("timestamp", System.currentTimeMillis());
        sendEvent(emitter, "tool_call_error", data, sessionId, toolName);
    }

    private static void putIfPresent(Map<String, Object> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    private ResponseBodyEmitter lookupEmitter(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (approvalChannelRegistry == null) return null;
        return approvalChannelRegistry.get(sessionId);
    }

    private void sendEvent(ResponseBodyEmitter emitter, String event, Map<String, Object> data,
                           String sessionId, String toolName) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.debug("[ToolCallProgress] json serialize failed event={} tool={} err={}", event, toolName, e.toString());
            return;
        }
        try {
            emitter.send("event: " + event + "\ndata: " + payload + "\n\n");
        } catch (Exception e) {
            // 客户端可能已断开 / emitter 已完成，吃掉异常不影响工具调用
            log.debug("[ToolCallProgress] emit failed event={} sessionId={} tool={} err={}",
                    event, sessionId, toolName, e.toString());
        }
    }

    static String truncate(String input) {
        if (input == null) return "";
        if (input.length() <= INPUT_PREVIEW_MAX_CHARS) return input;
        return input.substring(0, INPUT_PREVIEW_MAX_CHARS) + "...(truncated, full=" + input.length() + ")";
    }
}
