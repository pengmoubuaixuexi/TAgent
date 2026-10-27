package cn.bugstack.ai.domain.agent.service.model.failover;

import org.springframework.ai.retry.NonTransientAiException;

/**
 * A transient failure that the request layer already retried. Non-transient on purpose: no outer
 * layer (Spring AI, Resilience4j, the streaming step) may retry it again.
 */
public class ModelRetriesExhaustedException extends NonTransientAiException {
    private final transient ModelFailure failure;

    public ModelRetriesExhaustedException(ModelFailure failure, int attempts) {
        super("模型 " + failure.target().model() + " 暂时不可用（" + failure.describe() + "），已重试 " + attempts + " 次，请稍后再试");
        this.failure = failure;
    }

    public ModelFailure failure() { return failure; }
}
