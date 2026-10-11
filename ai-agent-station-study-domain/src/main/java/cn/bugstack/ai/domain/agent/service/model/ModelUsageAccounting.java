package cn.bugstack.ai.domain.agent.service.model;

/** Durable attempt accounting. Inputs contain identifiers only, never prompts or credentials. */
public interface ModelUsageAccounting {
    record Attempt(String userId,String runId,String apiId,String model,String purpose,String providerHost,
                   long inputEstimate,long outputLimit) {}
    interface Ticket {
        /** Null usage remains unknown and conservatively consumes the reservation. Settlement is idempotent. */
        void settle(Long actualTokens,String outcome);
    }
    Ticket begin(Attempt attempt);
    void openRun(String runId,String userId,long deadlineEpochMillis,boolean system);
    void changeRunState(String runId,String userId,String state);
}
