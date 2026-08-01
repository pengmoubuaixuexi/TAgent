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
                default -> {
                }
            }
        } catch (Exception e) {
            // Notification projection is never allowed to break the run event stream.
            log.warn("[Notification] projection failed runId={} type={}: {}", runId, eventType, e.getMessage());
        }
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
                || "human_approval_result".equals(eventType);
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
