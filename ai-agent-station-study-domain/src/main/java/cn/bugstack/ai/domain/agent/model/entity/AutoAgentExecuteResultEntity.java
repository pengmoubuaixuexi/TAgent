package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AutoAgent 执行结果实体
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AutoAgentExecuteResultEntity {

    /**
     * 数据类型：analysis(分析阶段), execution(执行阶段), supervision(监督阶段), summary(总结阶段), error(错误信息), complete(完成标识)
     * 细分类型：analysis_status(任务状态分析), analysis_history(执行历史评估), analysis_strategy(下一步策略), analysis_progress(完成度评估)
     *          execution_target(执行目标), execution_process(执行过程), execution_result(执行结果), execution_quality(质量检查)
     *          supervision_assessment(质量评估), supervision_issues(问题识别), supervision_suggestions(改进建议), supervision_score(质量评分)
     */
    private String type;

    /**
     * 子类型标识，用于前端细粒度展示
     */
    private String subType;

    /**
     * 当前步骤
     */
    private Integer step;

    /**
     * 数据内容
     */
    private String content;

    /**
     * 是否完成
     */
    private Boolean completed;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 创建分析阶段结果
     */
    public static AutoAgentExecuteResultEntity createAnalysisResult(Integer step, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("analysis")
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建分析阶段细分结果
     */
    public static AutoAgentExecuteResultEntity createAnalysisSubResult(Integer step, String subType, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("analysis")
                .subType(subType)
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建执行阶段结果
     */
    public static AutoAgentExecuteResultEntity createExecutionResult(Integer step, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("execution")
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建执行阶段细分结果
     */
    public static AutoAgentExecuteResultEntity createExecutionSubResult(Integer step, String subType, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("execution")
                .subType(subType)
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建监督阶段结果
     */
    public static AutoAgentExecuteResultEntity createSupervisionResult(Integer step, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("supervision")
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建监督阶段细分结果
     */
    public static AutoAgentExecuteResultEntity createSupervisionSubResult(Integer step, String subType, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("supervision")
                .subType(subType)
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建总结阶段细分的结果
     */
    public static AutoAgentExecuteResultEntity createSummarySubResult(String subType, String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("summary")
                .subType(subType)
                .step(4)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建总结阶段结果
     */
    public static AutoAgentExecuteResultEntity createSummaryResult(String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("summary")
                .step(null)
                .content(content)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建错误结果
     */
    public static AutoAgentExecuteResultEntity createErrorResult(String content, String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("error")
                .step(null)
                .content(content)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建完成标识
     */
    public static AutoAgentExecuteResultEntity createCompleteResult(String sessionId) {
        return AutoAgentExecuteResultEntity.builder()
                .type("complete")
                .step(null)
                .content("执行完成")
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

}