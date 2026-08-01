package cn.bugstack.ai.domain.agent.service.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Command consumed by Agent event sources to create/update a notification. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationCommand {
    private String notificationId;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String runId;
    private String stepId;
    private AgentNotification.Type type;
    private String referenceId;
    private AgentNotification.Status status;
    private String title;
    private String summary;
    private Long expiresAt;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
