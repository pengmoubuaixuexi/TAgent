package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Resource
    private RunEventPublisher runEventPublisher;

    /**
     * 2026-06-22 评估采集开关：开后 {@code tool_call_end} 携带工具真实返回(截断 {@value #DEFAULT_RESULT_PREVIEW_MAX_CHARS} 字，可配 agent.tool-progress.result-preview.max-chars)。
     * <p><b>默认 false —— 生产环境必须保持关闭</b>：SSE 出口是 PII 脱敏边界，把原始工具返回推到前端会越过该边界
     * （见 PII 策略：只在 SSE 出口/日志/回放端点脱敏）。仅在 E2E 评估跑(application-dev)临时设 true 采集。
     * <p>注入路径用 @Value；测试用构造的实例此字段为默认 false，需要时直接 set。</p>
     */
    @Value("${agent.tool-progress.result-preview.enabled:false}")
    private boolean resultPreviewEnabled;

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
        if (emitter == null && !hasRun(sessionId)) return;
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
        emitEnd(sessionId, toolName, status, latencyMs, resultChars, step, callId, null);
    }

    /**
     * 2026-06-22 评估采集：带工具真实返回的终态。{@code resultPreview} 仅在 {@link #resultPreviewEnabled} 为 true 时
     * 才写入 SSE（默认关，生产不推，见字段注释）。其余字段与既有 {@code tool_call_end} 完全一致。
     */
    public void emitEnd(String sessionId, String toolName, String status, long latencyMs, int resultChars,
                        String step, String callId, String resultPreview) {
        ResponseBodyEmitter emitter = lookupEmitter(sessionId);
        if (emitter == null && !hasRun(sessionId)) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("toolName", toolName);
        putIfPresent(data, "callId", callId);
        putIfPresent(data, "step", step);
        data.put("status", status);
        data.put("latencyMs", latencyMs);
        data.put("resultChars", resultChars);
        if (resultPreviewEnabled) {
            putIfPresent(data, "resultPreview", truncateResult(resultPreview));
        }
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
        if (emitter == null && !hasRun(sessionId)) return;
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

    /**
     * 元工具(ask_user / request_tool)观察卡片：复用 {@code tool_call_start}/{@code tool_call_end} 事件，
     * 走前端同一套卡片生命周期(running→done、按 toolName 配对、完成折叠)，但带 {@code meta:true} 标记，
     * 前端按 toolName 专用渲染(ask_user=问题列表、request_tool=needs+装载结果)。
     */
    public void emitMetaStart(String sessionId, String toolName, String preview, String step) {
        ResponseBodyEmitter emitter = lookupEmitter(sessionId);
        if (emitter == null && !hasRun(sessionId)) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("toolName", toolName);
        putIfPresent(data, "step", step);
        data.put("inputPreview", truncateMeta(preview));
        data.put("meta", true);
        data.put("timestamp", System.currentTimeMillis());
        sendEvent(emitter, "tool_call_start", data, sessionId, toolName);
    }

    /**
     * 元工具终态。{@code status} 复用前端已识别的枚举(success / approval_timeout / blocked / approval_unavailable / error)
     * 以便沿用颜色；{@code detail} 承载给用户看的结果(用户回复 / 已装载工具列表 / 失败原因)。
     */
    public void emitMetaEnd(String sessionId, String toolName, String status, String detail, String step) {
        ResponseBodyEmitter emitter = lookupEmitter(sessionId);
        if (emitter == null && !hasRun(sessionId)) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("toolName", toolName);
        putIfPresent(data, "step", step);
        data.put("status", status);
        putIfPresent(data, "detail", truncateMeta(detail));
        data.put("meta", true);
        data.put("timestamp", System.currentTimeMillis());
        sendEvent(emitter, "tool_call_end", data, sessionId, toolName);
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

    private boolean hasRun(String sessionId) {
        return runEventPublisher != null && runEventPublisher.currentRunId(sessionId) != null;
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
        if (hasRun(sessionId)) {
            runEventPublisher.publishCurrent(sessionId, event, data);
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

    /**
     * 评估用工具返回预览默认上限（字符）。2026-07-01 由 2000 提到 8000（对齐 {@code MeteredToolCallback.toolResultFull}）：
     * 2000 太短——LLM 判官吃 trace 里的 resultPreview 核对"答案里的具体信息(标题/作者/链接/数据)是否真来自工具返回"，
     * Q65 单次 search_papers 返回 1.1 万字时前 2000 字还没到具体论文标题，判官核对不上→误判编造。8000 字足够核对
     * 绝大多数具体信息，又不至于把判官 prompt 撑爆。抽成可配置以便 E2E 按需再放宽。
     */
    static final int DEFAULT_RESULT_PREVIEW_MAX_CHARS = 8000;

    /**
     * 工具返回预览上限（字符）。仅在 {@link #resultPreviewEnabled}=true（E2E 采集）时生效；
     * 生产开关关=根本不 emit resultPreview，本值无影响，PII 边界不破。测试用构造实例时 @Value 不注入，取字段初值。
     */
    @Value("${agent.tool-progress.result-preview.max-chars:8000}")
    private int resultPreviewMaxChars = DEFAULT_RESULT_PREVIEW_MAX_CHARS;

    String truncateResult(String input) {
        if (input == null) return "";
        int max = resultPreviewMaxChars > 0 ? resultPreviewMaxChars : DEFAULT_RESULT_PREVIEW_MAX_CHARS;
        if (input.length() <= max) return input;
        return input.substring(0, max) + "...(truncated, full=" + input.length() + ")";
    }

    /** 元工具卡片用更宽的上限：ask_user 一次可能问很多条问题，300 字会被截断。 */
    static final int META_PREVIEW_MAX_CHARS = 2000;

    static String truncateMeta(String input) {
        if (input == null) return "";
        if (input.length() <= META_PREVIEW_MAX_CHARS) return input;
        return input.substring(0, META_PREVIEW_MAX_CHARS) + "...(truncated, full=" + input.length() + ")";
    }
}
