package cn.bugstack.ai.domain.agent.service.execute.snapshot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RunStepSnapshot {

    private Integer ordinal;
    private Integer stepNo;
    private String stepId;
    private String title;
    private String displayLabel;
    private String type;
    private String status;
    private Boolean inherited;
    private String preview;
    private String content;
    private Long createdAt;
    private Long updatedAt;
}
