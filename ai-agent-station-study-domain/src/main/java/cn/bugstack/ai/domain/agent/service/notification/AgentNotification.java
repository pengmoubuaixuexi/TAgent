package cn.bugstack.ai.domain.agent.service.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable user-facing projection of an Agent event.
 *
 * <p>The notification is deliberately not the authority for a pending
 * approval or question. Callers must still revalidate the underlying run gate
 * before accepting an action.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentNotification {

    public enum Type {
        ASK_USER,
        TOOL_APPROVAL,
        PLAN_REVIEW,
        TASK_FAILED,
        TASK_COMPLETED,
        /** A platform model (to administrators) or a personal model (to its owner) stopped answering. */
        MODEL_UNAVAILABLE,
        MODEL_RECOVERED
    }

    public enum Status {
        WAITING,
        RESOLVED,
        EXPIRED,
        TASK_FAILED,
        TASK_COMPLETED,
        PLAN_REVIEW,
        MODEL_UNAVAILABLE,
        MODEL_RECOVERED
    }

    public enum ReadStatus {
        UNREAD,
        ACKNOWLEDGED,
        ARCHIVED
    }

    private String notificationId;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String runId;
    private String stepId;
    private Type type;
    /** inputId, approvalId, runId or background-task executionId. */
    private String referenceId;
    private Status status;
    private ReadStatus readStatus;
    private String title;
    private String summary;
    private Long createdAt;
    private Long updatedAt;
    private Long expiresAt;
    private Long acknowledgedAt;
    private Long archivedAt;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
