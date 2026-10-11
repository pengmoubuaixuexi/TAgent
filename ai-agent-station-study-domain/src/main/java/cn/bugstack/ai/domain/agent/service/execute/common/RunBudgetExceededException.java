package cn.bugstack.ai.domain.agent.service.execute.common;

/** Terminal control flow: never retry or convert into a normal tool response. */
public final class RunBudgetExceededException extends IllegalStateException {
    public RunBudgetExceededException(String reason) { super(reason); }
}
