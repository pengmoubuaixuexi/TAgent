package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import java.time.Duration;
import java.util.Optional;

public interface FlowPlanReviewStateStore {

    void save(FlowPlanReviewState state, Duration ttl);

    Optional<FlowPlanReviewState> find(String runId);

    void delete(String runId);

}
