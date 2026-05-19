package cn.bugstack.ai.domain.agent.model.entity.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step1 任务分析结构化输出（P0.3）。
 * <p>
 * 替换字符串解析（{@code result.contains("任务状态: COMPLETED")}）的脆性匹配。
 * 用 Spring AI 的 {@code .call().entity(Step1AnalysisResult.class)} 强制 LLM 返回符合 schema 的 JSON。
 * <p>
 * 旧 prompt 格式（任务状态分析: ... / 完成度评估: 80% / 任务状态: IN_PROGRESS）切换到此 Pojo 时，
 * DB 里 system prompt 也要相应改写为 "请按以下 JSON Schema 返回..."，新老不能混用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Step1AnalysisResult {

    /** 任务状态：IN_PROGRESS / COMPLETED / BLOCKED */
    @JsonProperty("status")
    private String status;

    /** 完成度（0-100） */
    @JsonProperty("progress")
    private int progress;

    /** 当前任务的状态分析（自然语言） */
    @JsonProperty("analysis")
    private String analysis;

    /** 历史执行评估 */
    @JsonProperty("history_eval")
    private String historyEval;

    /** 下一步建议策略 */
    @JsonProperty("next_strategy")
    private String nextStrategy;

    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status) || progress >= 100;
    }
}
