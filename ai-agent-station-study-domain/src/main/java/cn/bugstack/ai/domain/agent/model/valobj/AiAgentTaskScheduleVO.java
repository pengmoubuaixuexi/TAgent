package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能体任务
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/9/13 16:08
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentTaskScheduleVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 时间表达式(如: 0/3 * * * * *)
     */
    private String cronExpression;

    /**
     * 任务入参配置(JSON格式)
     */
    private String taskParam;

}
