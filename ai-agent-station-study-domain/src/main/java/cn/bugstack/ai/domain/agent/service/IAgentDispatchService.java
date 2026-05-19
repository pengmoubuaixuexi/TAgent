package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Agent 策略调度器接口
 * @author TAgent
 * 2025/9/6 06:54
 */
public interface IAgentDispatchService {

    void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception;

}
