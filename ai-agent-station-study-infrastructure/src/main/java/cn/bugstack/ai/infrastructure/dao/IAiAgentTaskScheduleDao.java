package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentTaskSchedule;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 智能体任务调度配置表 DAO
 * @author bugstack虫洞栈
 * @description 智能体任务调度配置表数据访问对象
 */
@Mapper
public interface IAiAgentTaskScheduleDao {

    /**
     * 插入智能体任务调度配置
     * @param aiAgentTaskSchedule 智能体任务调度配置对象
     * @return 影响行数
     */
    int insert(AiAgentTaskSchedule aiAgentTaskSchedule);

    /**
     * 根据ID更新智能体任务调度配置
     * @param aiAgentTaskSchedule 智能体任务调度配置对象
     * @return 影响行数
     */
    int updateById(AiAgentTaskSchedule aiAgentTaskSchedule);

    /**
     * 根据ID删除智能体任务调度配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据智能体ID删除任务调度配置
     * @param agentId 智能体ID
     * @return 影响行数
     */
    int deleteByAgentId(Long agentId);

    /**
     * 根据ID查询智能体任务调度配置
     * @param id 主键ID
     * @return 智能体任务调度配置对象
     */
    AiAgentTaskSchedule queryById(Long id);

    /**
     * 根据智能体ID查询任务调度配置列表
     * @param agentId 智能体ID
     * @return 智能体任务调度配置列表
     */
    List<AiAgentTaskSchedule> queryByAgentId(Long agentId);

    /**
     * 查询所有有效的任务调度配置
     * @return 智能体任务调度配置列表
     */
    List<AiAgentTaskSchedule> queryEnabledTasks();

    /**
     * 根据任务名称查询任务调度配置
     * @param taskName 任务名称
     * @return 智能体任务调度配置对象
     */
    AiAgentTaskSchedule queryByTaskName(String taskName);

    /**
     * 查询所有智能体任务调度配置
     * @return 智能体任务调度配置列表
     */
    List<AiAgentTaskSchedule> queryAll();

    /**
     * 查询所有有效的任务调度配置
     * @return 智能体任务调度配置列表
     */
    List<AiAgentTaskSchedule> queryAllValidTaskSchedule();

    /**
     * 查询所有无效的任务调度配置ID
     * @return 无效任务调度配置ID列表
     */
    List<Long> queryAllInvalidTaskScheduleIds();

}