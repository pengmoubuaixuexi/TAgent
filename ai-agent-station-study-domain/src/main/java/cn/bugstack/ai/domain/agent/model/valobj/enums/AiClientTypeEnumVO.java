package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 17:25
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AiClientTypeEnumVO {

    DEFAULT("DEFAULT", "通用的"),
    TASK_ANALYZER_CLIENT("TASK_ANALYZER_CLIENT", "任务分析和状态判断"),
    PRECISION_EXECUTOR_CLIENT("PRECISION_EXECUTOR_CLIENT", "具体任务执行"),
    QUALITY_SUPERVISOR_CLIENT("QUALITY_SUPERVISOR_CLIENT", "质量检查和优化"),
    RESPONSE_ASSISTANT("RESPONSE_ASSISTANT", "智能响应助手"),

    TOOL_MCP_CLIENT("TOOL_MCP_CLIENT", "工具分析"),
    PLANNING_CLIENT("PLANNING_CLIENT","任务规划"),
    EXECUTOR_CLIENT("EXECUTOR_CLIENT", "任务执行")

    ;

    private String code;
    private String info;

}
