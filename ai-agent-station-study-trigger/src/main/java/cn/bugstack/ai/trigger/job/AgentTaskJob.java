package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentTaskScheduleVO;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.ITaskService;
import cn.bugstack.ai.types.job.model.TaskScheduleVO;
import cn.bugstack.ai.types.job.provider.ITaskDataProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体任务
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/9/13 15:52
 */
@Slf4j
@Service
public class AgentTaskJob implements ITaskDataProvider {

    @Resource
    private ITaskService taskService;

    @Resource
    private IAgentDispatchService dispatchService;

    @Override
    public List<TaskScheduleVO> queryAllValidTaskSchedule() {
        List<AiAgentTaskScheduleVO> aiAgentTaskScheduleVOS = taskService.queryAllValidTaskSchedule();
        List<TaskScheduleVO> result = new ArrayList<>();
        for (AiAgentTaskScheduleVO aiAgentTaskScheduleVO : aiAgentTaskScheduleVOS) {
            TaskScheduleVO taskScheduleVO = new TaskScheduleVO();
            taskScheduleVO.setId(aiAgentTaskScheduleVO.getId());
            taskScheduleVO.setDescription(aiAgentTaskScheduleVO.getDescription());
            taskScheduleVO.setCronExpression(aiAgentTaskScheduleVO.getCronExpression());
            taskScheduleVO.setTaskParam(aiAgentTaskScheduleVO.getTaskParam());
            taskScheduleVO.setTaskLogic(() -> {
                try {
                    dispatchService.dispatch(
                            ExecuteCommandEntity.builder()
                                    .aiAgentId(aiAgentTaskScheduleVO.getAgentId())
                                    .sessionId(String.valueOf(System.nanoTime()))
                                    .maxStep(1)
                                    .build(), new ResponseBodyEmitter());
                } catch (Exception e) {
                    log.error("任务执行失败", e);
                }

            });

            result.add(taskScheduleVO);
        }
        return result;
    }

    @Override
    public List<Long> queryAllInvalidTaskScheduleIds() {
        return taskService.queryAllInvalidTaskScheduleIds();
    }

}
