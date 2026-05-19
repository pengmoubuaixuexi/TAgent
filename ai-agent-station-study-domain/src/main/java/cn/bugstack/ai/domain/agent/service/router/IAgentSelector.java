package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;

import java.util.List;

/**
 * Agent 池选择器：意图路由确定策略后，从该策略的 agent 池中选描述最匹配的。
 */
public interface IAgentSelector {

    /**
     * 从 agent 列表中选出最匹配用户 query 的一个。
     * @param query   用户原始输入
     * @param pool    候选 agent 列表（同一策略下的启用 agent）
     * @return 最佳 agent 的 agentId；只有 1 个时直接返回该 agent，空列表返回 null
     */
    String select(String query, List<AiAgentVO> pool);
}
