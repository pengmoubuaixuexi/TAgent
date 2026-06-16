package cn.bugstack.ai.domain.agent.service.dispatch;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter;
import cn.bugstack.ai.domain.agent.service.router.RouteDecision;
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
 * @author xiaofuge bugstack.cn @小傅哥
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

    @Autowired(required = false)
    private SessionRefCounter sessionRefCounter;

    /** 路由失败时的兜底 agent_id（需确保该 agent 已启用） */
    @Value("${agent.fallback-agent-id:8011}")
    private String fallbackAgentId;

    /** M1：前端已选定 agent（不走路由）时，是否仍推断可能缺失的工具能力（一次 router-small 调用）。 */
    @Value("${agent.dynamic-tools.infer-on-selected-agent:true}")
    private boolean inferMissingToolOnSelectedAgent;

    @Override
    public void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        String agentId = requestParameter.getAiAgentId();

        // 1. 如果前端没选 agent（aiAgentId 为空），走统一路由
        if (agentId == null || agentId.isBlank()) {
            if (unifiedAgentRouter != null) {
                RouteDecision routeDecision = unifiedAgentRouter.routeDecision(requestParameter.getMessage());
                agentId = routeDecision != null ? routeDecision.agentId() : null;
                if (agentId == null) {
                    agentId = fallbackAgentId;
                    log.info("统一路由未命中，fallback 到 {}", fallbackAgentId);
                }
                if (routeDecision != null && routeDecision.hasMissingToolDesc()) {
                    // 多条 need 用换行连成单串透传（下游 resolveDynamicToolCallbacks 拆开、各取 top-k 再并集）
                    requestParameter.setDynamicMissingToolDesc(routeDecision.missingToolDescJoined());
                    requestParameter.setRouteConfidence(routeDecision.confidence());
                }
                requestParameter.setAiAgentId(agentId);
                log.info("[Dispatch] 统一路由选中 agent: {} missingTool='{}'",
                        agentId, requestParameter.getDynamicMissingToolDesc());
            } else {
                throw new BizException("未配置路由器且未指定 agentId");
            }
        } else if (unifiedAgentRouter != null && inferMissingToolOnSelectedAgent) {
            // M1：前端已选定 agent，不走路由，但仍推断该 agent 可能缺失的工具能力（可多条），给动态补挂用
            java.util.List<String> missing = unifiedAgentRouter.inferMissingTool(agentId, requestParameter.getMessage());
            if (missing != null && !missing.isEmpty()) {
                requestParameter.setDynamicMissingToolDesc(String.join("\n", missing));
                log.info("[Dispatch] 已选定 agent={} 推断 missingTools={}", agentId, missing);
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

        // 路由命中后第一时间把 agent 透传给前端（TTFT：前端先显示"路由中…"，收到本事件后替换成 名称(id)）。
        // 发送失败（客户端断开等）不影响主流程。
        try {
            String safeName = aiAgentVO.getAgentName() == null ? ""
                    : aiAgentVO.getAgentName().replace("\\", "\\\\").replace("\"", "\\\"");
            emitter.send("event: agent_routed\ndata: {\"agentId\":\"" + agentId + "\",\"agentName\":\"" + safeName + "\"}\n\n");
        } catch (Exception ignore) {
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
                String sessionId = requestParameter.getSessionId();
                try {
                    if (sessionRefCounter != null) sessionRefCounter.clear(sessionId);
                    finalStrategy1.execute(requestParameter, emitter);
                } catch (Exception e) {
                    // 取消（用户点取消 / 客户端断开）会抛 CancellationException，属正常中止，静默结束不报错给前端
                    if (e instanceof java.util.concurrent.CancellationException
                            || e.getCause() instanceof java.util.concurrent.CancellationException) {
                        log.info("[Dispatch] 执行已取消 agentId={} strategy={} sessionId={}", finalAgentId, finalStrategy, sessionId);
                    } else {
                        log.error("Agent执行异常：agentId={} strategy={} error={}", finalAgentId, finalStrategy, e.getMessage(), e);
                        try {
                            emitter.send("执行异常：" + e.getMessage());
                        } catch (Exception ex) {
                            log.error("发送异常信息失败：{}", ex.getMessage(), ex);
                        }
                    }
                } finally {
                    if (sessionRefCounter != null) sessionRefCounter.clear(sessionId);
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

    /** 立即回答：广播给所有策略；持有该 session 的策略生效，其余 no-op（activeContexts 查不到）。 */
    @Override
    public void finalizeExecute(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        for (IExecuteStrategy s : executeStrategyMap.values()) {
            try {
                s.finalizeExecute(sessionId);
            } catch (Exception e) {
                log.debug("[Dispatch] finalizeExecute on {} failed: {}", s.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** 引导回复：广播新想法给所有策略。 */
    @Override
    public void steerExecute(String sessionId, String idea) {
        if (sessionId == null || sessionId.isBlank()) return;
        for (IExecuteStrategy s : executeStrategyMap.values()) {
            try {
                s.steerExecute(sessionId, idea);
            } catch (Exception e) {
                log.debug("[Dispatch] steerExecute on {} failed: {}", s.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** 取消：广播给所有策略；持有该 session 的策略中止剩余执行并截断在飞流式调用，其余 no-op。 */
    @Override
    public void cancelExecute(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        for (IExecuteStrategy s : executeStrategyMap.values()) {
            try {
                s.cancelExecute(sessionId);
            } catch (Exception e) {
                log.debug("[Dispatch] cancelExecute on {} failed: {}", s.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

}
