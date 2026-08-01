package cn.bugstack.ai.domain.agent.service.security;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * P2.2 11.5 Human-in-the-Loop：敏感工具需人工审批。
 * <p>
 * 当检测到敏感工具调用时，emit SSE 事件要求前端审批，等待配置时间内回复。
 * 超时自动拒绝，保障安全。
 * <p>
 * <b>G1-A 升级</b>（2026-05-25）：
 * <ul>
 *   <li>SSE payload 改用 Jackson 序列化（Codex 第 42 轮第 1 条），避免工具输入里的换行/反斜杠/控制字符炸前端 JSON。</li>
 *   <li>新增 {@link #requestApproval(String, String, String)} 重载：调用方只需传 sessionId，
 *       内部经 {@link ApprovalChannelRegistry} 反查 emitter。</li>
 *   <li>通道缺失（无 session / 无 emitter）时返 {@link Decision#APPROVAL_UNAVAILABLE}（Codex 第 42 轮第 3 条），
 *       不阻塞等待也不静默放行，让调用方据此返结构化错误给 LLM。</li>
 *   <li>旧的 emitter 参数签名保留，调用方可继续用；新代码请走 sessionId 重载。</li>
 * </ul>
 */
@Slf4j
@Component
public class HumanApprovalGate {

    /**
     * G1-A：审批决策结果。比原 boolean 表达力更强，区分"用户明确拒绝/超时"和"通道不可用"两种 false 路径。
     */
    public enum Decision {
        APPROVED,
        REJECTED,
        TIMEOUT,
        /** 通道缺失（找不到 emitter）—— 调用方应返结构化错误给 LLM，不视为静默放行也不视为用户拒绝。 */
        APPROVAL_UNAVAILABLE
    }

    @Value("${agent.human-approval.enabled:false}")
    private boolean enabled;

    @Value("${agent.human-approval.timeout-seconds:60}")
    private int timeoutSeconds;

    @Resource
    private ApprovalChannelRegistry approvalChannelRegistry;

    @Resource
    private RunEventPublisher runEventPublisher;

    @Resource
    private HighRiskToolRegistry highRiskToolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    /** approvalId 自增序号：DAG 并行下两个步骤同毫秒发起审批时，靠它保证 approvalId 仍唯一、future 不互相覆盖。 */
    private final java.util.concurrent.atomic.AtomicLong approvalSeq = new java.util.concurrent.atomic.AtomicLong();

    public boolean isEnabled() {
        return enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public HighRiskToolRegistry getHighRiskToolRegistry() {
        return highRiskToolRegistry;
    }

    /**
     * G1-A 新签名：调用方只持 sessionId，内部反查 emitter。
     * <p>
     * 调用流程（G1-C 会在 MeteredToolCallback 接入）：
     * <ol>
     *   <li>检查 {@link #enabled} 与 {@link HighRiskToolRegistry#isHighRisk(String)} —— 任一为 false 直接 APPROVED 放行</li>
     *   <li>查 {@link ApprovalChannelRegistry#get(String)} —— null 返 APPROVAL_UNAVAILABLE，不阻塞</li>
     *   <li>emit SSE + 等 future.complete 或 timeout</li>
     * </ol>
     */
    public Decision requestApproval(String sessionId, String toolName, String toolInput) {
        // 强转消歧义：null 否则在新 (String,String,String,String) 与旧 (...,ResponseBodyEmitter) 重载间二义
        return requestApproval(sessionId, toolName, toolInput, (String) null);
    }

    /**
     * G1-C DAG 并发增强：带 stepLabel 的重载。stepLabel = 这次工具调用所属步骤的人类可读标签（如"精准执行"），
     * 透传到 SSE payload，让前端在并发审批时标注"是哪个步骤要的许可"。stepLabel 为 null 时退化为不带步骤的旧行为。
     */
    public Decision requestApproval(String sessionId, String toolName, String toolInput, String stepLabel) {
        if (!enabled) return Decision.APPROVED;
        if (!highRiskToolRegistry.isHighRisk(toolName)) return Decision.APPROVED;

        if (runEventPublisher.currentRunId(sessionId) == null) {
            log.warn("[HumanApproval] active run missing sessionId={} toolName={}, returning APPROVAL_UNAVAILABLE",
                    sessionId, toolName);
            return Decision.APPROVAL_UNAVAILABLE;
        }
        return doRequestApproval(sessionId, toolName, toolInput, stepLabel, null);
    }

    /**
     * 旧签名（兼容保留）：调用方直接传 emitter。返回 boolean 简化为 APPROVED → true，其他 → false。
     *
     * @deprecated 新代码请用 {@link #requestApproval(String, String, String)}。
     */
    @Deprecated
    public boolean requestApproval(String sessionId, String toolName, String toolInput,
                                   ResponseBodyEmitter emitter) {
        if (!enabled) return true;
        if (emitter == null) {
            log.warn("[HumanApproval] emitter is null sessionId={} toolName={}, returning false (legacy signature)",
                    sessionId, toolName);
            return false;
        }
        return doRequestApproval(sessionId, toolName, toolInput, null, emitter) == Decision.APPROVED;
    }

    private Decision doRequestApproval(String sessionId, String toolName, String toolInput, String stepLabel,
                                       ResponseBodyEmitter emitter) {
        // 加自增序号：DAG 并行下同毫秒发起的两个审批 approvalId 仍唯一，pendingApprovals 不会被后者覆盖前者
        String approvalId = sessionId + ":" + toolName + ":" + System.currentTimeMillis() + ":" + approvalSeq.incrementAndGet();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(approvalId, future);

        try {
            if (emitter != null) {
                emitter.send("event: human_approval_required\ndata: " + buildPayload(approvalId, toolName, toolInput, stepLabel) + "\n\n");
            } else {
                runEventPublisher.publishCurrent(sessionId, "human_approval_required",
                        buildPayload(approvalId, toolName, toolInput, stepLabel));
            }
            log.info("[HumanApproval] requested approvalId={} toolName={} step={}", approvalId, toolName, stepLabel);

            Boolean approved = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("[HumanApproval] result approvalId={} approved={}", approvalId, approved);
            publishResult(sessionId, approvalId, approved != null && approved ? "APPROVED" : "REJECTED");
            return (approved != null && approved) ? Decision.APPROVED : Decision.REJECTED;
        } catch (TimeoutException e) {
            log.warn("[HumanApproval] timeout approvalId={} toolName={}", approvalId, toolName);
            publishResult(sessionId, approvalId, "TIMEOUT");
            return Decision.TIMEOUT;
        } catch (IOException e) {
            log.warn("[HumanApproval] emit failed approvalId={}: {}", approvalId, e.toString());
            return Decision.APPROVAL_UNAVAILABLE;
        } catch (Exception e) {
            log.debug("[HumanApproval] failed approvalId={}: {}", approvalId, e.getMessage());
            return Decision.REJECTED;
        } finally {
            pendingApprovals.remove(approvalId);
        }
    }

    private void publishResult(String sessionId, String approvalId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("status", status);
        runEventPublisher.publishCurrent(sessionId, "human_approval_result", payload);
    }

    /**
     * G1-A: 用 Jackson 正式序列化 SSE payload（Codex 第 42 轮第 1 条）。
     * <p>
     * 工具输入截断到 500 字符避免 SSE 帧过大；toolInput 中的换行/反斜杠/控制字符由 Jackson 自动 escape，
     * 不会再因手拼 JSON 漏 escape 而炸前端解析。
     */
    private String buildPayload(String approvalId, String toolName, String toolInput, String stepLabel) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("approvalId", approvalId);
        map.put("toolName", toolName);
        map.put("toolInput", toolInput != null
                ? toolInput.substring(0, Math.min(500, toolInput.length()))
                : "");
        // DAG 并行审批时前端用它标注"哪个步骤要的许可"；为空则前端不显示步骤行
        if (stepLabel != null && !stepLabel.isBlank()) {
            map.put("step", stepLabel);
        }
        map.put("timeoutSeconds", timeoutSeconds);
        map.put("expiresAt", System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds));
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("[HumanApproval] json serialize fallback approvalId={}: {}", approvalId, e.toString());
            // 极小概率分支；fallback 只保留安全的 approvalId（approvalId 由 sessionId/toolName/ts 拼成，
            // toolName 中的诡异字符已通过 sessionId 间接介入，这里干脆不再回写 toolName，避免 fallback 路径
            // 还有手拼 escape 风险（Codex 第 44 轮回答 2）。前端拿到 approvalId 仍能识别和回 POST /approval。
            return "{\"approvalId\":\"" + approvalId.replace("\"", "") + "\",\"toolName\":\"\",\"toolInput\":\"\",\"timeoutSeconds\":" + timeoutSeconds + "}";
        }
    }

    /**
     * 前端审批回复入口（由 Controller 调用）。
     * @param approvalId 审批 ID
     * @param approved true=批准
     */
    public boolean resolveApproval(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.get(approvalId);
        return future != null && future.complete(approved);
    }
}
