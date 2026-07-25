package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiBackgroundTask {
    private Long id;
    private String taskId;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String name;
    private String taskType;
    private String status;
    private String triggerConfigJson;
    private String actionPrompt;
    private String actionAgentId;
    private Integer maxStep;
    private Boolean runOnce;
    private String baselineHash;
    private String lastObservedHash;
    private LocalDateTime observedChangedAt;
    private LocalDateTime nextTriggerAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastTriggeredAt;
    private String lastRunId;
    private LocalDateTime draftExpiresAt;
    private String lastError;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
