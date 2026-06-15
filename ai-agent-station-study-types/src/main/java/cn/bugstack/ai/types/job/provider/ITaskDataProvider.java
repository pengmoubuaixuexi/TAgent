package cn.bugstack.ai.types.job.provider;

import cn.bugstack.ai.types.job.model.TaskScheduleVO;

import java.util.List;

/**
 * 任务数据提供者接口，用户需要实现此接口来提供任务调度数据
 *
 * @author @小傅哥
 */
public interface ITaskDataProvider {

    /**
     * 查询所有有效的任务调度配置
     * @return 任务调度配置列表
     */
    List<TaskScheduleVO> queryAllValidTaskSchedule();
    
    /**
     * 查询所有无效的任务ID
     * @return 无效任务ID列表
     */
    List<Long> queryAllInvalidTaskScheduleIds();

}