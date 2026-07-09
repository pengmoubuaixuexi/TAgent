package cn.bugstack.ai.domain.agent.service.execute.snapshot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RunSnapshot {

    private String runId;
    private String sessionId;
    private String agentId;
    private String agentName;
    private String agentType;
    private String originalMessage;
    private String sourceRunId;
    private Integer redoFromStep;
    private String status;
    private String lastError;
    private Long createdAt;
    private Long updatedAt;
    private Long expiresAt;

    @Builder.Default
    private List<RunStepSnapshot> steps = new ArrayList<>();
}
