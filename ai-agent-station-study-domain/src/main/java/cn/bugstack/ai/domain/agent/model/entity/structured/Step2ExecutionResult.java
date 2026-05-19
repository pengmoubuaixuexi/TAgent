package cn.bugstack.ai.domain.agent.model.entity.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Step2 精确执行结构化输出（P0.3）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Step2ExecutionResult {

    /** 本步要执行的具体动作描述 */
    @JsonProperty("action")
    private String action;

    /** 动作执行结果（自然语言；如果调用了工具则是工具返回值的浓缩） */
    @JsonProperty("output")
    private String output;

    /** 已经调用的工具及参数（便于 audit；可空） */
    @JsonProperty("tools_called")
    private List<ToolCall> toolsCalled;

    /** 是否需要继续执行下一步 */
    @JsonProperty("needs_more_steps")
    private boolean needsMoreSteps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        @JsonProperty("name")        private String name;
        @JsonProperty("arguments")   private String arguments;
        @JsonProperty("result")      private String result;
    }
}
