package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowPlanReviewConfirmRequestDTO {

    private String runId;

    private String sessionId;

    private List<Step> steps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {

        private Integer stepNo;

        private String title;

        private String content;

        private List<Integer> dependsOn;

    }

}
