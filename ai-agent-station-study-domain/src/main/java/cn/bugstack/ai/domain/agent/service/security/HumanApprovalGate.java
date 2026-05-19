package cn.bugstack.ai.domain.agent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * P2.2 11.5 Human-in-the-Loop：敏感工具需人工审批。
 * <p>
 * 当检测到敏感工具调用时，emit SSE 事件要求前端审批，等待配置时间内回复。
 * 超时自动拒绝，保障安全。
 */
@Slf4j
@Component
public class HumanApprovalGate {

    @Value("${agent.human-approval.enabled:false}")
    private boolean enabled;

    @Value("${agent.human-approval.timeout-seconds:60}")
    private int timeoutSeconds;

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 请求人工审批。阻塞直到前端回复或超时。
     * @param sessionId 会话 ID
     * @param toolName 敏感工具名
     * @param toolInput 工具输入参数摘要
     * @param emitter SSE emitter
     * @return true=批准, false=拒绝/超时
     */
    public boolean requestApproval(String sessionId, String toolName, String toolInput,
                                   ResponseBodyEmitter emitter) {
        if (!enabled) return true; // 未启用默认放行

        String approvalId = sessionId + ":" + toolName + ":" + System.currentTimeMillis();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(approvalId, future);

        try {
            // 发送 SSE 审批请求
            String payload = String.format(
                    "{\"approvalId\":\"%s\",\"toolName\":\"%s\",\"toolInput\":\"%s\",\"timeoutSeconds\":%d}",
                    approvalId, toolName,
                    toolInput != null ? toolInput.replace("\"", "\\\"").substring(0, Math.min(500, toolInput.length())) : "",
                    timeoutSeconds);
            emitter.send("event: human_approval_required\ndata: " + payload + "\n\n");
            log.info("[HumanApproval] requested approvalId={} toolName={}", approvalId, toolName);

            // 等待审批回复或超时
            Boolean approved = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("[HumanApproval] result approvalId={} approved={}", approvalId, approved);
            return approved != null && approved;
        } catch (TimeoutException e) {
            log.warn("[HumanApproval] timeout approvalId={} toolName={}", approvalId, toolName);
            return false;
        } catch (Exception e) {
            log.debug("[HumanApproval] failed approvalId={}: {}", approvalId, e.getMessage());
            return false;
        } finally {
            pendingApprovals.remove(approvalId);
        }
    }

    /**
     * 前端审批回复入口（由 Controller 调用）。
     * @param approvalId 审批 ID
     * @param approved true=批准
     */
    public void resolveApproval(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.get(approvalId);
        if (future != null) {
            future.complete(approved);
        }
    }
}
