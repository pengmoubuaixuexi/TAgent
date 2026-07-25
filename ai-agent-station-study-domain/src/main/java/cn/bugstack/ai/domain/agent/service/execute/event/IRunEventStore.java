package cn.bugstack.ai.domain.agent.service.execute.event;

import java.util.List;

/** Short-lived ordered storage used to replay output lost during SSE disconnects. */
public interface IRunEventStore {

    RunEventRecord append(String runId, String sessionId, String eventType, String payloadJson);

    List<RunEventRecord> readAfter(String runId, String afterEventId);
}
