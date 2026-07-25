package cn.bugstack.ai.domain.agent.service.dispatch;

/**
 * A dispatch request that conflicts with an already reserved run.
 *
 * <p>The reason is typed so background scheduling can defer a temporary
 * session-busy conflict without treating it as an execution failure.</p>
 */
public final class RunDispatchConflictException extends RuntimeException {

    public enum Reason {
        SESSION_BUSY,
        DUPLICATE_RUN_ID
    }

    private final Reason reason;
    private final String sessionId;
    private final String requestedRunId;
    private final String existingRunId;

    private RunDispatchConflictException(Reason reason,
                                         String message,
                                         String sessionId,
                                         String requestedRunId,
                                         String existingRunId) {
        super(message);
        this.reason = reason;
        this.sessionId = sessionId;
        this.requestedRunId = requestedRunId;
        this.existingRunId = existingRunId;
    }

    public static RunDispatchConflictException sessionBusy(String sessionId,
                                                           String requestedRunId,
                                                           String existingRunId) {
        return new RunDispatchConflictException(
                Reason.SESSION_BUSY,
                "Session already has an active run: " + existingRunId,
                sessionId,
                requestedRunId,
                existingRunId);
    }

    public static RunDispatchConflictException duplicateRunId(String sessionId, String runId) {
        return new RunDispatchConflictException(
                Reason.DUPLICATE_RUN_ID,
                "runId has already been used: " + runId,
                sessionId,
                runId,
                runId);
    }

    public Reason getReason() {
        return reason;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRequestedRunId() {
        return requestedRunId;
    }

    public String getExistingRunId() {
        return existingRunId;
    }
}
