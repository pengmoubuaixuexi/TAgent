package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

public interface IFlowPlanReviewResumeService {

    void resumeReviewedPlan(FlowPlanReviewState state,
                            FlowPlanReviewValidationResult approvedPlan,
                            ResponseBodyEmitter emitter);

}
