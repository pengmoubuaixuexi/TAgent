package cn.bugstack.ai.domain.agent.service.trace;

import java.util.List;

/** Durable storage of run trace nodes. Writes are asynchronous and never fail a run. */
public interface TraceSink {

    /**
     * One model request, tool call or run event.
     *
     * @param payload JSON; strings over the inline limit are replaced by {@code {"$blob": hash}}
     */
    record Node(String runId, int seq, String owner, String kind, String name, String step, Integer parentSeq,
                String status, String apiId, Long inputTokens, Long outputTokens, long startedAt, long durationMs,
                String payload) { }

    /** Text stored once per run: system prompts, tool definitions and large results. */
    record Blob(String runId, String hash, String content) { }

    void write(Node node, List<Blob> blobs);

    /** The highest stored or queued sequence number of a run, so a resumed run continues after it. */
    default int lastSeq(String runId) { return 0; }
}
