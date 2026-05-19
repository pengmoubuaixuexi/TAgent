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
 * @author TAgent
 * 2025/10/3 12:50
 */
@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private IAgentRepository repository;

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    /** 懒加载缓存：已装配的 agentId 集合 */
    private final ConcurrentHashMap<String, Boolean> armedAgents = new ConcurrentHashMap<>();

    @Override
    public List<AiAgentVO> acceptArmoryAllAvailableAgents() {
        List<AiAgentVO> aiAgentVOS = repository.queryAvailableAgents();
        for (AiAgentVO aiAgentVO : aiAgentVOS) {
            String agentId = aiAgentVO.getAgentId();
            acceptArmoryAgent(agentId);
        }
        return aiAgentVOS;
    }

    @Override
    public void acceptArmoryAgent(String agentId) {
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
            throw new RuntimeException("装配智能体失败", e);
        }
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        return repository.queryAvailableAgents();
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
    public void reloadAll() {
        log.info("[HotReload] Reloading all agents...");
        armedAgents.clear();
        acceptArmoryAllAvailableAgents();
        log.info("[HotReload] All agents reloaded");
    }

    @Override
    public void ensureArmed(String agentId) {
        if (armedAgents.containsKey(agentId)) return;
        synchronized (this) {
            if (armedAgents.containsKey(agentId)) return;
            log.info("[LazyArmory] Agent {} 首次命中，装配中...", agentId);
            acceptArmoryAgent(agentId);
            armedAgents.put(agentId, Boolean.TRUE);
            log.info("[LazyArmory] Agent {} 装配完成", agentId);
        }
    }

    @Override
    public boolean isAgentArmed(String agentId) {
        return armedAgents.containsKey(agentId);
    }

}
