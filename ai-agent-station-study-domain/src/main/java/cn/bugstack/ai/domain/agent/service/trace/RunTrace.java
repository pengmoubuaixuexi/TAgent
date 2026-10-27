package cn.bugstack.ai.domain.agent.service.trace;

import java.util.List;
import java.util.Map;

/**
 * Complete run trace: every model request (failed ones too), tool call and key run event, kept in
 * MySQL. Static wire guards and tool callbacks reach the recorder through this bridge, like
 * {@code ModelUsageLedger}. Recording never throws into the run.
 */
public final class RunTrace {

    /** One HTTP request to a chat model, as sent and as answered. */
    public record ModelExchange(String runId, String owner, String step, String apiId, String purpose,
                                long startedAt, long durationMs, Map<String, Object> request, int httpStatus,
                                ModelReply reply, String outcome, String error) { }

    /** The assistant message of a reply; a stream is reassembled before it is recorded. */
    public record ModelReply(String model, String content, String reasoning, List<Map<String, Object>> toolCalls,
                             String finishReason, Long inputTokens, Long outputTokens) { }

    /**
     * One tool execution.
     *
     * @param source BUSINESS for MCP and other registered tools, META for the platform's own tools
     * @param result the complete result before any projection for the model
     */
    public record ToolExchange(String runId, String owner, String step, String stepLabel, String callId, String name,
                               String arguments, String result, int returnedChars, String status, String error,
                               long startedAt, long durationMs, String source) { }

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_ERROR = "ERROR";
    public static final String OUTCOME_CANCELLED = "CANCELLED";

    private static volatile TraceRecorder recorder;

    private RunTrace() { }

    public static void install(TraceRecorder value) { recorder = java.util.Objects.requireNonNull(value); }

    public static void uninstall(TraceRecorder value) { if (recorder == value) recorder = null; }

    public static boolean enabled() { return recorder != null; }

    public static void model(ModelExchange exchange) {
        TraceRecorder current = recorder;
        if (current != null) current.model(exchange);
    }

    public static void tool(ToolExchange exchange) {
        TraceRecorder current = recorder;
        if (current != null) current.tool(exchange);
    }

    public static void event(String runId, String eventType, String payloadJson) {
        TraceRecorder current = recorder;
        if (current != null) current.event(runId, eventType, payloadJson);
    }

    /** Releases the in-memory state of a finished run; its nodes are already queued. */
    public static void finishRun(String runId) {
        TraceRecorder current = recorder;
        if (current != null) current.finishRun(runId);
    }
}
