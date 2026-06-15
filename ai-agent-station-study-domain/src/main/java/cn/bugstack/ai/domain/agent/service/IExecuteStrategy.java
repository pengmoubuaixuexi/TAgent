package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 执行策略接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/5 09:48
 */
public interface IExecuteStrategy {

    void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception;

    /** 取消当前正在执行的任务（由超时或客户端断开触发） */
    default void cancelExecute(String sessionId) {}

    /**
     * 立即回答：中止剩余执行，跳各模式 finalize 例程基于半成品作答。
     * 持有该 session 的策略生效，其余 no-op（与 {@link #cancelExecute} 同款 null-check）。
     * 设计见 docs/INTERVENTION_立即回答与引导回复_设计.md。
     */
    default void finalizeExecute(String sessionId) {}

    /** 引导回复：注入新想法，不丢弃进度继续当前步。持有该 session 的策略生效，其余 no-op。 */
    default void steerExecute(String sessionId, String idea) {}

}
