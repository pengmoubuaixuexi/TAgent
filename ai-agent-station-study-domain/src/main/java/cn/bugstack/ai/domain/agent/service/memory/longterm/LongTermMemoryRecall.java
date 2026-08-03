package cn.bugstack.ai.domain.agent.service.memory.longterm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Structured memory recall retained for prompt injection and evidence display. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryRecall {
    public static final String KIND_CORE = "core";
    public static final String KIND_RELEVANT = "relevant";

    private String memoryId;
    private String topic;
    private String content;
    private String kind;
    private Double similarity;

    public String toPromptLine() {
        return topic == null || topic.isBlank() ? content : "[" + topic + "] " + content;
    }
}
