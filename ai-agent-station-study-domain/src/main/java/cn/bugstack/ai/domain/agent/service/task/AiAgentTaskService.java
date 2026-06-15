package cn.bugstack.ai.domain.agent.service.task;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentTaskScheduleVO;
import cn.bugstack.ai.domain.agent.service.ITaskService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能体执行任务
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/9/13 16:09
 */
@Service
public class AiAgentTaskService implements ITaskService {

    @Resource
    private IAgentRepository repository;

    @Override
    public List<AiAgentTaskScheduleVO> queryAllValidTaskSchedule() {
        return repository.queryAllValidTaskSchedule();
    }

    @Override
    public List<Long> queryAllInvalidTaskScheduleIds() {
        return repository.queryAllInvalidTaskScheduleIds();
    }

}
