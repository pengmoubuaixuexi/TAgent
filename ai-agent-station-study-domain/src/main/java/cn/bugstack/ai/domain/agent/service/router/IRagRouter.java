package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.RagRouterDecision;

/**
 * RAG 检索路由（P0.1.4）。
 * <p>
 * RagAnswerAdvisor 在 before() 阶段先问一句：当前 query 真的需要查知识库吗？
 * 闲聊 / 致谢 / 简短问候等 query 检索没意义反而引入噪声，跳过省一次双库 + rerank 调用。
 * <p>
 * 实现策略：
 * <ul>
 *   <li>{@code HeuristicRagRouter}：纯规则，零成本，命中率覆盖典型闲聊</li>
 *   <li>{@code LlmRagRouter}：小模型判断，一次调用同时给出检索路径 + 子查询/变体</li>
 * </ul>
 */
public interface IRagRouter {

    /**
     * @return true = 走完整 RAG 检索；false = 跳过检索直接让模型作答
     */
    boolean shouldRetrieve(String query);

    /**
     * 完整决策：是否检索 + 推荐路径 + 子查询（分解）/ 变体（Fusion）。
     * LlmRagRouter 覆写以一次 LLM 调用完成所有判断；
     * HeuristicRagRouter 使用默认实现（纯规则 + path=HYBRID）。
     */
    default RagRouterDecision decide(String query) {
        return shouldRetrieve(query)
                ? RagRouterDecision.retrieve()
                : RagRouterDecision.skip("heuristic");
    }
}
