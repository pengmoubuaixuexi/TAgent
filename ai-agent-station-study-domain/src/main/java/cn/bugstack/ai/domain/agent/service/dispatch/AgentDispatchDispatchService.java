package cn.bugstack.ai.domain.agent.service.dispatch;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.router.UnifiedAgentRouter;
import cn.bugstack.ai.types.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 调度服务
 *
 * @author TAgent
 * 2025/9/6 06:55
 */
@Slf4j
@Service
public class AgentDispatchDispatchService implements IAgentDispatchService {

    @Resource
    private Map<String, IExecuteStrategy> executeStrategyMap;

    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Autowired(required = false)
    private UnifiedAgentRouter unifiedAgentRouter;

    @Autowired(required = false)
    private ArmoryService armoryService;

    /** 路由失败时的兜底 agent_id（需确保该 agent 已启用） */
    @Value("${agent.fallback-agent-id:8011}")
    private String fallbackAgentId;

    @Override
    public void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        String agentId = requestParameter.getAiAgentId();

        // 1. 如果前端没选 agent（aiAgentId 为空），走统一路由
        if (agentId == null || agentId.isBlank()) {
            if (unifiedAgentRouter != null) {
                agentId = unifiedAgentRouter.route(requestParameter.getMessage());
                if (agentId == null) {
                    agentId = fallbackAgentId;
                    log.info("统一路由未命中，fallback 到 {}", fallbackAgentId);
                }
                requestParameter.setAiAgentId(agentId);
                log.info("统一路由选中 agent: {}", agentId);
            } else {
                throw new BizException("未配置路由器且未指定 agentId");
            }
        }

        // 2. 懒加载装配
        if (armoryService != null && !armoryService.isAgentArmed(agentId)) {
            armoryService.ensureArmed(agentId);
        }

        // 3. 查 agent 信息，取策略
        AiAgentVO aiAgentVO = repository.queryAiAgentByAgentId(agentId);
        if (aiAgentVO == null) {
            throw new BizException("agent 不存在: " + agentId);
        }
        String strategy = aiAgentVO.getStrategy();

        IExecuteStrategy executeStrategy = executeStrategyMap.get(strategy);
        if (executeStrategy == null) {
            throw new BizException("不存在的执行策略: " + strategy);
        }

        // 4. 异步执行（用 final 变量供 lambda 引用）
        final String finalAgentId = agentId;
        final String finalStrategy = strategy;
        final IExecuteStrategy finalStrategy1 = executeStrategy;
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    finalStrategy1.execute(requestParameter, emitter);
                } catch (Exception e) {
                    log.error("Agent执行异常：agentId={} strategy={} error={}", finalAgentId, finalStrategy, e.getMessage(), e);
                    try {
                        emitter.send("执行异常：" + e.getMessage());
                    } catch (Exception ex) {
                        log.error("发送异常信息失败：{}", ex.getMessage(), ex);
                    }
                } finally {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("完成流式输出失败：{}", e.getMessage(), e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("线程池已满，拒绝执行 agent={} strategy={}", finalAgentId, finalStrategy);
            emitter.send("{\"error\":\"service_unavailable\",\"message\":\"Server too busy, please retry later\",\"status\":503}");
            emitter.complete();
        }
    }

}
