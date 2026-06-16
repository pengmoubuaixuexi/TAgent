package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Agent 策略调度器接口
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/9/6 06:54
 */
public interface IAgentDispatchService {

    void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception;

    /** 立即回答：广播给所有策略，持有该 session 的策略中止剩余执行、跳 finalize。 */
    default void finalizeExecute(String sessionId) {}

    /** 引导回复：广播新想法给持有该 session 的策略。 */
    default void steerExecute(String sessionId, String idea) {}

    /** 取消执行：广播给所有策略，持有该 session 的策略中止剩余执行并截断在飞流式调用（不产出答案）。 */
    default void cancelExecute(String sessionId) {}

}
