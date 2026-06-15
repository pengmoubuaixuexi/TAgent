package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentTaskScheduleVO;

import java.util.List;

/**
 * 智能体执行任务
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/9/13 16:08
 */
public interface ITaskService {

    List<AiAgentTaskScheduleVO> queryAllValidTaskSchedule();

    List<Long> queryAllInvalidTaskScheduleIds();

}
