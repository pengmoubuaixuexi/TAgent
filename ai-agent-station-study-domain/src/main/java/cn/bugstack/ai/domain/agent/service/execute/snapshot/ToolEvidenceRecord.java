package cn.bugstack.ai.domain.agent.service.execute.snapshot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A tool result that was actually returned to the model during one run.
 * Stored outside the SSE timeline because the output may be much larger than a progress card.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolEvidenceRecord {

    private String evidenceId;
    private String callId;
    private String toolName;
    private String step;
    private String status;
    private String input;
    private String output;
    private String outputType;
    private Integer resultChars;
    private Integer rawResultChars;
    private Long latencyMs;
    private Long createdAt;
}
