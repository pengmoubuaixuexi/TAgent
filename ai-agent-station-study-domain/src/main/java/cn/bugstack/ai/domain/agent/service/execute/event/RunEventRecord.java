package cn.bugstack.ai.domain.agent.service.execute.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A user-visible run event. Its event id is also the reconnect cursor. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunEventRecord {

    private String eventId;
    private String runId;
    private String sessionId;
    private String eventType;
    private String payloadJson;
    private Long createdAt;
}
