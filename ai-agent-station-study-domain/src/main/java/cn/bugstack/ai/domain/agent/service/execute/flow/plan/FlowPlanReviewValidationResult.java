package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowPlanReviewValidationResult {

    private boolean valid;

    private List<String> errors;

    private List<String> warnings;

    private Map<String, String> stepsMap;

    private Map<Integer, Set<Integer>> stepDependencies;

}
