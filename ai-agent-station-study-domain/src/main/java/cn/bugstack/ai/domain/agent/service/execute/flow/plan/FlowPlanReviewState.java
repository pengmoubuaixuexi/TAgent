package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowPlanReviewState implements Serializable {

    private String runId;

    private String sessionId;

    private String agentId;

    private String userId;

    private String tenantId;

    private String originalMessage;

    private String sourceRunId;

    private Integer redoFromStep;

    private String redoContextPrompt;

    private String redoTargetStepContextPrompt;

    private String planningResult;

    private String mcpToolsAnalysis;

    private String mcpNeeds;

    private List<FlowPlanReviewStep> steps;

    private String status;

    private List<FlowPlanReviewStep> approvedSteps;

    private Integer attemptCount;

    private String lastError;

    private Long createdAt;

    private Long updatedAt;

    private Long expiresAt;

}
