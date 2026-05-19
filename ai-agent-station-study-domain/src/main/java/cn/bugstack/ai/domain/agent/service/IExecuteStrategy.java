package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 执行策略接口
 *
 * @author TAgent
 * 2025/8/5 09:48
 */
public interface IExecuteStrategy {

    void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception;

    /** 取消当前正在执行的任务（由超时或客户端断开触发） */
    default void cancelExecute(String sessionId) {}

}
