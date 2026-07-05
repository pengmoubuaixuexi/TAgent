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

    private String planningResult;

    private String mcpToolsAnalysis;

    private String mcpNeeds;

    private List<FlowPlanReviewStep> steps;

    private Long createdAt;

    private Long expiresAt;

}
