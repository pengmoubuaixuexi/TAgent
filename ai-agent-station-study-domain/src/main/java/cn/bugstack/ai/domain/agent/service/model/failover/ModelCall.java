package cn.bugstack.ai.domain.agent.service.model.failover;

/** The run a request belongs to, captured at the request boundary; either id may be null. */
public record ModelCall(String runId, String sessionId) {
    public static final ModelCall NONE = new ModelCall(null, null);
}
