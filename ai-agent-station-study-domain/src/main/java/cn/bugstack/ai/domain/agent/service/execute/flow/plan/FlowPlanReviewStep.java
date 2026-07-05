package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowPlanReviewStep implements Serializable {

    private Integer stepNo;

    private String title;

    private String content;

    private List<Integer> dependsOn;

}
