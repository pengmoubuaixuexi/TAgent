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
    /** Flow/redo 使用：执行该步骤时的原始步骤内容，随 Redis run snapshot TTL 自动过期。 */
    private String stepContent;
    private Long createdAt;
    private Long updatedAt;
}
