package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体任务调度配置表
 * @author bugstack虫洞栈
 * @description 智能体任务调度配置表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentTaskSchedule {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 任务名称
     */
    private String taskName;

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

    /**
     * 状态(0:无效,1:有效)
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}