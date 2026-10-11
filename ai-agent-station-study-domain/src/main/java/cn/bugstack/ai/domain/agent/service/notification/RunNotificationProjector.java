package cn.bugstack.ai.domain.agent.service.notification;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Projects the small set of run events that need cross-session user attention. */
@Slf4j
@Component
public class RunNotificationProjector {

    private final NotificationService notificationService;

    @Autowired(required = false)
    private RunSnapshotService runSnapshotService;

    public RunNotificationProjector(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void project(String runId, String sessionId, String eventType, String payloadJson) {
        if (!isSupported(eventType)) return;
        try {
            RunSnapshot snapshot = runSnapshotService == null ? null : runSnapshotService.find(runId).orElse(null);
            String userId = firstNonBlank(snapshot == null ? null : snapshot.getUserId(), MDC.get("userId"));
            if (blank(userId)) {
                log.debug("[Notification] skip event without owner runId={} type={}", runId, eventType);
                return;
            }
            JSONObject payload = JSON.parseObject(payloadJson);
            if (payload == null) payload = new JSONObject();
            switch (eventType) {
                case "user_input_required" -> publishAskUser(userId, runId, sessionId, snapshot, payload);
                case "human_approval_required" -> publishApproval(userId, runId, sessionId, snapshot, payload);
                case "user_input_result" -> resolve(userId, payload.getString("inputId"), payload.getString("status"));
                case "human_approval_result" -> resolve(userId, payload.getString("approvalId"), payload.getString("status"));
                case "message" -> {
                    // A step error/summary is not a terminal run. Plan review deliberately has no complete frame.
                    if (payload.get("step") != null || !blank(payload.getString("subType"))) return;
                    String type = payload.getString("type");
                    if ("complete".equals(type) && Boolean.TRUE.equals(payload.getBoolean("completed"))) {
                        publishTerminal(userId, runId, sessionId, snapshot, true, null, false);
                    } else if ("error".equals(type)) {
                        publishTerminal(userId, runId, sessionId, snapshot, false, payload.getString("content"), false);
                    }
                }
                case "plan_review_status" -> {
                    String status = payload.getString("status");
                    if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                        publishTerminal(userId, runId, sessionId, snapshot,
                                "COMPLETED".equals(status), payload.getString("lastError"), "COMPLETED".equals(status));
                    }
                }
                default -> {
                }
            }
        } catch (Exception e) {
            // Notification projection is never allowed to break the run event stream.
            log.warn("[Notification] projection failed runId={} type={}: {}", runId, eventType, e.getMessage());
        }
    }

    private void publishTerminal(String userId, String runId, String sessionId, RunSnapshot snapshot,
                                 boolean completed, String error, boolean reviewedExecutionCompleted) {
        if (blank(runId) || snapshot == null || !userId.equals(snapshot.getUserId())) return;
        // Snapshot ownership/session are authoritative. Never use a frame's claimed owner/session.
        String ownedSession = snapshot.getSessionId();
        if (blank(ownedSession) || !ownedSession.equals(sessionId)) return;
        if (RunSnapshotService.STATUS_CANCELLED.equals(snapshot.getStatus())) return;
        // A failed terminal can be followed by duplicate cleanup/complete frames. Do not turn it green.
        String id = NotificationService.terminalId(userId, runId);
        AgentNotification existing = notificationService.find(userId, id).orElse(null);
        if (completed && !reviewedExecutionCompleted && (RunSnapshotService.STATUS_FAILED.equals(snapshot.getStatus())
                || existing != null && existing.getType() == AgentNotification.Type.TASK_FAILED)) return;
        notificationService.publish(NotificationCommand.builder()
                .notificationId(id).userId(userId)
                .sessionId(ownedSession).runId(runId)
                // Keep the plan-review reference intact: its resolver also uses the plain runId.
                .referenceId("run-terminal:" + runId)
                .type(completed ? AgentNotification.Type.TASK_COMPLETED : AgentNotification.Type.TASK_FAILED)
                .status(completed ? AgentNotification.Status.TASK_COMPLETED : AgentNotification.Status.TASK_FAILED)
                .title(runTitle(snapshot, completed ? "已完成" : "执行未完成"))
                .summary(completed ? "结果已就绪，点击查看回答与产物" : failureSummary(error))
                .metadata(Map.of("executionStatus", completed ? "COMPLETED" : "FAILED"))
                .build());
    }

    /** Inbox previews must not expose provider bodies, credentials, URLs or stack traces. */
    public static String failureSummary(String error) {
        String text = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("budget") || text.contains("预算") || text.contains("额度"))
            return "任务因可用额度或上下文预算不足而停止，点击查看详情";
        if (text.contains("timeout") || text.contains("timed out") || text.contains("超时"))
            return "模型或工具等待超时，点击查看已完成部分与详情";
        if (text.contains("429") || text.contains("rate limit") || text.contains("频率"))
            return "服务调用受到频率限制，点击查看详情后重试";
        return "任务执行未完成，点击查看已完成部分与失败详情";
    }

    private void publishAskUser(String userId,
                                String runId,
                                String sessionId,
                                RunSnapshot snapshot,
                                JSONObject payload) {
        String inputId = payload.getString("inputId");
        if (blank(inputId)) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("context", payload.getString("context"));
        metadata.put("questions", payload.get("questions"));
        metadata.put("questionDetails", payload.get("questionDetails"));
        notificationService.publish(NotificationCommand.builder()
                .userId(userId)
                .sessionId(sessionId)
                .runId(runId)
                .stepId(payload.getString("step"))
                .type(AgentNotification.Type.ASK_USER)
                .referenceId(inputId)
                .status(AgentNotification.Status.WAITING)
                .title(runTitle(snapshot, "需要你补充信息"))
                .summary(firstNonBlank(firstQuestion(payload), payload.getString("context"), "Agent 正在等待你的回答"))
                .expiresAt(payload.getLong("expiresAt"))
                .metadata(metadata)
                .build());
    }

    private void publishApproval(String userId,
                                 String runId,
                                 String sessionId,
                                 RunSnapshot snapshot,
                                 JSONObject payload) {
        String approvalId = payload.getString("approvalId");
        if (blank(approvalId)) return;
        String toolName = firstNonBlank(payload.getString("toolName"), "未知工具");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", toolName);
        metadata.put("toolInput", payload.getString("toolInput"));
        notificationService.publish(NotificationCommand.builder()
                .userId(userId)
                .sessionId(sessionId)
                .runId(runId)
                .stepId(payload.getString("step"))
                .type(AgentNotification.Type.TOOL_APPROVAL)
                .referenceId(approvalId)
                .status(AgentNotification.Status.WAITING)
                .title(runTitle(snapshot, "请求调用 " + toolName))
                .summary("工具调用正在等待你的批准")
                .expiresAt(payload.getLong("expiresAt"))
                .metadata(metadata)
                .build());
    }

    private void resolve(String userId, String referenceId, String resultStatus) {
        if (blank(referenceId)) return;
        AgentNotification.Status status = "TIMEOUT".equalsIgnoreCase(resultStatus)
                ? AgentNotification.Status.EXPIRED
                : AgentNotification.Status.RESOLVED;
        notificationService.resolve(userId, referenceId, status);
    }

    private String runTitle(RunSnapshot snapshot, String suffix) {
        String original = snapshot == null ? null : snapshot.getOriginalMessage();
        if (blank(original)) return "会话" + suffix;
        String oneLine = original.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 18) oneLine = oneLine.substring(0, 18) + "…";
        return "「" + oneLine + "」" + suffix;
    }

    private String firstQuestion(JSONObject payload) {
        JSONArray questions = payload.getJSONArray("questions");
        if (questions == null || questions.isEmpty()) return null;
        Object first = questions.get(0);
        return first == null ? null : String.valueOf(first);
    }

    private boolean isSupported(String eventType) {
        return "user_input_required".equals(eventType)
                || "human_approval_required".equals(eventType)
                || "user_input_result".equals(eventType)
                || "human_approval_result".equals(eventType)
                || "message".equals(eventType)
                || "plan_review_status".equals(eventType);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return null;
    }
}
