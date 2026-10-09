package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 装配服务
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/10/3 12:50
 */
@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private IAgentRepository repository;

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository workspaceAccess;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @org.springframework.beans.factory.annotation.Autowired
    private cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry agentToolRegistry;

    private void requireOwned(String agentId) {
        String userId = org.slf4j.MDC.get("userId");
        if (userId == null || userId.isBlank() || workspaceAccess == null || !workspaceAccess.ownsAgent(userId, agentId)) {
            throw new IllegalArgumentException("Agent 不存在、已停用或无权访问");
        }
    }

    /** 懒加载缓存：已装配的 agentId 集合 */
    private final ConcurrentHashMap<String, Boolean> armedAgents = new ConcurrentHashMap<>();

    @Override
    public synchronized List<AiAgentVO> acceptArmoryAllAvailableAgents() {
        List<AiAgentVO> aiAgentVOS = repository.queryAvailableAgents();
        for (AiAgentVO aiAgentVO : aiAgentVOS) {
            String agentId = aiAgentVO.getAgentId();
            assembleAgent(agentId);
        }
        return aiAgentVOS;
    }

    @Override
    public synchronized void acceptArmoryAgent(String agentId) {
        requireOwned(agentId);
        assembleAgent(agentId);
    }

    private void assembleAgent(String agentId) {
        List<AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOS = repository.queryAiAgentClientsByAgentId(agentId);
        if (aiAgentClientFlowConfigVOS.isEmpty()) return;

        // 获取客户端集合
        List<String> commandIdList = aiAgentClientFlowConfigVOS.stream()
                .map(AiAgentClientFlowConfigVO::getClientId)
                .collect(Collectors.toList());

        try {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                    defaultArmoryStrategyFactory.armoryStrategyHandler();

            armoryStrategyHandler.apply(
                    ArmoryCommandEntity.builder()
                            .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                            .commandIdList(commandIdList)
                            .build(),
                    new DefaultArmoryStrategyFactory.DynamicContext());
        } catch (Exception e) {
            if (e instanceof cn.bugstack.ai.domain.agent.service.execute.common.McpToolNameGuard.CollisionException collision) {
                throw collision;
            }
            throw new RuntimeException("装配智能体失败", e);
        }
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        String userId = org.slf4j.MDC.get("userId");
        if (userId == null || userId.isBlank() || workspaceAccess == null) return List.of();
        java.util.Set<String> allowed = workspaceAccess.ownedAgentIds(userId);
        return repository.queryAvailableAgents().stream().filter(agent -> allowed.contains(agent.getAgentId())).toList();
    }

    @Override
    public void acceptArmoryAgentClientModelApi(String apiId) {
        try {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                    defaultArmoryStrategyFactory.armoryStrategyHandler();

            armoryStrategyHandler.apply(
                    ArmoryCommandEntity.builder()
                            .commandType(AiAgentEnumVO.AI_CLIENT_API.getCode())
                            .commandIdList(Collections.singletonList(apiId))
                            .build(),
                    new DefaultArmoryStrategyFactory.DynamicContext());
        } catch (Exception e) {
            throw new RuntimeException("装配智能体失败", e);
        }
    }

    @Override
    public Long getMaxConfigUpdateTime() {
        return repository.getMaxConfigUpdateTime();
    }

    @Override
    public synchronized void reloadAll() {
        log.info("[HotReload] Reloading all agents...");
        armedAgents.clear();
        acceptArmoryAllAvailableAgents();
        log.info("[HotReload] All agents reloaded");
    }

    @Override
    public synchronized void ensureArmed(String agentId) {
        requireOwned(agentId);
        if (armedAgents.containsKey(agentId)) return;
        log.info("[LazyArmory] Agent {} 首次命中，装配中...", agentId);
        acceptArmoryAgent(agentId);
        armedAgents.put(agentId, Boolean.TRUE);
        log.info("[LazyArmory] Agent {} 装配完成", agentId);
    }

    @Override
    public boolean isAgentArmed(String agentId) {
        return armedAgents.containsKey(agentId);
    }

    @Override
    public synchronized void invalidateAgent(String agentId) {
        if (agentId != null) armedAgents.remove(agentId);
    }

    @Override
    public synchronized void invalidateAll() {
        armedAgents.clear();
    }

    @Override
    public synchronized void invalidateAgent(String agentId, java.util.Map<String, java.util.Set<String>> obsoleteResources) {
        evictResources(obsoleteResources);
        invalidateAgent(agentId);
    }

    @Override
    public synchronized void invalidateAgents(java.util.Set<String> agentIds, Runnable resourceInvalidation) {
        try {
            resourceInvalidation.run();
        } finally {
            agentIds.forEach(this::invalidateAgent);
        }
    }

    /** Call only with old graph IDs captured before a committed workspace update. */
    @Override
    public synchronized void evictResources(java.util.Map<String, java.util.Set<String>> resources) {
        if (resources == null) return;
        var factory = (org.springframework.beans.factory.support.DefaultListableBeanFactory)
                applicationContext.getAutowireCapableBeanFactory();
        for (var entry : resources.entrySet()) {
            // Shared providers and reusable MCP connections have separate lifecycles.
            if (!java.util.Set.of("client", "model", "advisor", "prompt").contains(entry.getKey())) continue;
            AiAgentEnumVO type = AiAgentEnumVO.getByCode(entry.getKey());
            for (String id : entry.getValue()) {
                if ("client".equals(entry.getKey())) agentToolRegistry.unregister(id);
                String name = type.getBeanName(id);
                if (factory.containsBeanDefinition(name)) factory.removeBeanDefinition(name);
                else if (factory.containsSingleton(name)) factory.destroySingleton(name);
            }
        }
    }

}
