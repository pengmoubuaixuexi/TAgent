package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowPlanReviewPreparedPlan {

    private boolean ready;

    private String errorCode;

    private List<String> errors;

    private List<String> warnings;

    private FlowPlanReviewState state;

    private FlowPlanReviewValidationResult validation;

    public static FlowPlanReviewPreparedPlan failed(String errorCode, List<String> errors) {
        return FlowPlanReviewPreparedPlan.builder()
                .ready(false)
                .errorCode(errorCode)
                .errors(errors)
                .warnings(List.of())
                .build();
    }

}
