package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;

import java.util.List;

/**
 * 装配接口
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/10/3 12:48
 */
public interface IArmoryService {

    List<AiAgentVO> acceptArmoryAllAvailableAgents();

    void acceptArmoryAgent(String agentId);

    List<AiAgentVO> queryAvailableAgents();

    void acceptArmoryAgentClientModelApi(String apiId);

    /** P2.8 17.3 配置热加载：查询配置表最大 update_time（epoch millis） */
    Long getMaxConfigUpdateTime();

    /** P2.8 17.3 配置热加载：重建所有已装配 agent */
    void reloadAll();

    /** 懒加载：确保 agent 已装配（首次调用时装配，后续命中缓存） */
    void ensureArmed(String agentId);

    /** 懒加载：检查 agent 是否已装配 */
    boolean isAgentArmed(String agentId);

    default void invalidateAgent(String agentId) {}

    default void invalidateAll() {}

    default void evictResources(java.util.Map<String, java.util.Set<String>> resources) {}

    default void invalidateAgent(String agentId, java.util.Map<String, java.util.Set<String>> obsoleteResources) {
        evictResources(obsoleteResources);
        invalidateAgent(agentId);
    }

    /** Resource invalidation must run after any in-flight assembly has released its old configuration. */
    default void invalidateAgents(java.util.Set<String> agentIds, Runnable resourceInvalidation) {
        resourceInvalidation.run();
        agentIds.forEach(this::invalidateAgent);
    }

}
