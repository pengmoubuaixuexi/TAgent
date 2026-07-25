package cn.bugstack.ai.trigger.background;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundTaskCommand {
    private boolean matched;
    private double confidence;
    private String operation;
    private boolean needsClarification;
    @Builder.Default
    private List<String> clarifyingQuestions = new ArrayList<>();
    private String taskReference;
    private TaskDraft taskDraft;
    private String rawResponse;
    private String error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskDraft {
        private String taskType;
        private String name;
        @Builder.Default
        private Map<String, Object> trigger = new LinkedHashMap<>();
        private String actionPrompt;
        private String actionAgentId;
        private Integer maxStep;
        private Boolean runOnce;
    }
}
