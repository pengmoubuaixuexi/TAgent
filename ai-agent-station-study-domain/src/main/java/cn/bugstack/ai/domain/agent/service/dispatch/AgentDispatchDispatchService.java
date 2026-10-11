package cn.bugstack.ai.domain.agent.service.dispatch;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.router.RouteDecision;
import cn.bugstack.ai.domain.agent.service.router.UnifiedAgentRouter;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import cn.bugstack.ai.types.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository runHistory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry runBudgets;
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewService flowPlanReviewService;

    @Resource
    private Map<String, IExecuteStrategy> executeStrategyMap;

    @Resource
    private IAgentRepository repository;

    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository workspaceAccess;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Autowired(required = false)
    private UnifiedAgentRouter unifiedAgentRouter;

    @Autowired(required = false)
    private ArmoryService armoryService;

    @Autowired
    private WorkspaceExecutionGuard workspaceExecutionGuard = new WorkspaceExecutionGuard();

    @Autowired(required = false)
    private SessionRefCounter sessionRefCounter;

    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService runSnapshotService;

    @Autowired
    private RunEventPublisher runEventPublisher;

    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.event.RunPreparationProgress preparationProgress;

    private final java.util.Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();

    /** A browser connection is only a subscriber; the session owns the run. */
    private final ConcurrentHashMap<String, String> activeRunBySession = new ConcurrentHashMap<>();

    /** 动态补工具能力描述(need)的统一存储入口；按 sessionId 写入，执行层读取。 */
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService mcpToolCatalogService;

    /** 路由失败时的兜底 agent_id（需确保该 agent 已启用） */
    @Value("${agent.fallback-agent-id:8011}")
    private String fallbackAgentId;

    /** M1：前端已选定 agent（不走路由）时，是否仍推断可能缺失的工具能力（一次 router-small 调用）。 */
    @Value("${agent.dynamic-tools.enabled:false}")
    private boolean dynamicToolInferenceEnabled;

    @Override
    public void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        String userId = requestParameter.getUserId();
        if (userId == null || userId.isBlank() || workspaceAccess == null) {
            throw new BizException("缺少已认证的用户身份");
        }
        String agentId = requestParameter.getAiAgentId();
        if (agentId != null && !agentId.isBlank() && !workspaceAccess.ownsAgent(userId, agentId)) {
            throw new BizException("Agent 不存在、已停用或无权访问");
        }
        String oldUser = org.slf4j.MDC.get("userId");
        String oldTenant = org.slf4j.MDC.get("tenantId");
        String oldRun = org.slf4j.MDC.get("runId");
        String oldAgentRun = org.slf4j.MDC.get("agent.run_id");
        try {
            putMdc("userId", userId);
            putMdc("tenantId", requestParameter.getTenantId());
            dispatchOwned(requestParameter, emitter);
        } finally {
            restoreMdc("userId", oldUser);
            restoreMdc("tenantId", oldTenant);
            restoreMdc("runId", oldRun);
            restoreMdc("agent.run_id", oldAgentRun);
        }
    }

    private void dispatchOwned(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        if (requestParameter.getRunId() == null || requestParameter.getRunId().isBlank()) {
            requestParameter.setRunId(UUID.randomUUID().toString());
        }
        org.slf4j.MDC.put("runId", requestParameter.getRunId());
        org.slf4j.MDC.put("agent.run_id", requestParameter.getRunId());
        String agentId = requestParameter.getAiAgentId();

        // 防串请求/泄漏：进入即清掉本 session 可能残留的旧 need（上一次请求若在"交给异步策略执行"前同步失败，
        // 策略 finally 不会跑、clearNeeds 漏掉）。本轮 need 写在这之后；若本轮也没成功交出去，由方法末尾 finally 兜底清。
        final String __needSid = requestParameter.getSessionId();
        final String __runId = requestParameter.getRunId();
        if (__needSid == null || __needSid.isBlank()) {
            throw new BizException("sessionId不能为空");
        }
        String existingRunId = activeRunBySession.putIfAbsent(__needSid, __runId);
        if (existingRunId != null) {
            if (existingRunId.equals(__runId)) {
                // Idempotent retry/double-submit: the controller-side emitter
                // is already attached to this run, so it can observe/replay the
                // existing execution. Never start the strategy a second time.
                log.info("[Dispatch] duplicate active run request joined existing execution sessionId={} runId={}",
                        __needSid, __runId);
                return;
            }
            throw RunDispatchConflictException.sessionBusy(__needSid, __runId, existingRunId);
        }
        try {
            if (runSnapshotService != null && runSnapshotService.find(__runId).isPresent()) {
                activeRunBySession.remove(__needSid, __runId);
                throw RunDispatchConflictException.duplicateRunId(__needSid, __runId);
            }
        } catch (RuntimeException failure) {
            activeRunBySession.remove(__needSid, __runId);
            throw failure;
        }
        runEventPublisher.registerRun(__runId, __needSid);
        boolean historyStarted = false;
        try {
            // Persist admission before returning ACK, including queued/cancelled/rejected runs.
            if (runHistory != null) { runHistory.start(requestParameter); historyStarted = true; }
            if (runSnapshotService != null) runSnapshotService.startRun(requestParameter, "ROUTING", null);
            // Reserve before queueing: a cancel immediately after ACK also cancels preparation.
            if (runBudgets != null) runBudgets.start(__runId, requestParameter.getUserId());
            if (preparationProgress != null) preparationProgress.start(__runId, __needSid);
            threadPoolExecutor.execute(() -> executeOwned(requestParameter, emitter));
        } catch (RuntimeException failure) {
            try {
                if (historyStarted) historyStatus(__runId, "FAILED", failure.getMessage());
                snapshotStatus(__runId, "FAILED", failure.getMessage());
                publishFailure(__runId, __needSid, failure);
            } finally {
                if (preparationProgress != null) preparationProgress.close(__runId);
                cancelledRuns.remove(__runId);
                activeRunBySession.remove(__needSid, __runId);
                runEventPublisher.finishRun(__runId);
            }
        }
    }

    /** The MVC handler can now return its emitter before routing or any MCP connection is started. */
    private void executeOwned(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) {
        String __needSid = requestParameter.getSessionId();
        String __runId = requestParameter.getRunId();
        String agentId = requestParameter.getAiAgentId();
        Map<String, String> previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        WorkspaceExecutionGuard.Lease executionLease = null;
        boolean reviewPaused = false;
        boolean strategyStarted = false;
        try {
            putMdc("runId", __runId);
            putMdc("agent.run_id", __runId);
            putMdc("agentId", agentId);
            putMdc("agentVersion", null);
            putMdc("sessionId", __needSid);
            putMdc("userId", requestParameter.getUserId());
            putMdc("tenantId", requestParameter.getTenantId());
            checkPreparationActive(__runId);
            if (mcpToolCatalogService != null) mcpToolCatalogService.clearNeeds(__needSid);
            checkPreparationActive(__runId);
            if (preparationProgress != null && ((agentId == null || agentId.isBlank()) || dynamicToolInferenceEnabled))
                preparationProgress.phase(__runId, "agent_routing");
            // 1. 如果前端没选 agent（aiAgentId 为空），走统一路由
            if (agentId == null || agentId.isBlank()) {
                if (unifiedAgentRouter != null) {
                    RouteDecision routeDecision = unifiedAgentRouter.routeDecision(requestParameter.getMessage());
                    agentId = routeDecision != null ? routeDecision.agentId() : null;
                    if (agentId == null) {
                        var allowed = workspaceAccess.ownedAgentIds(requestParameter.getUserId());
                        agentId = allowed.contains(fallbackAgentId) ? fallbackAgentId
                                : repository.queryAvailableAgents().stream()
                                        .map(AiAgentVO::getAgentId).filter(allowed::contains).findFirst().orElse(null);
                    }
                    if (routeDecision != null) {
                        requestParameter.setRouteConfidence(routeDecision.confidence());
                    }
                    if (routeDecision != null && routeDecision.hasMissingToolDesc()) {
                        // 多条 need 用换行连成单串，按 sessionId 写入统一 store（下游 resolveDynamicToolCallbacks 拆开、各取 top-k 再并集）
                        if (mcpToolCatalogService != null) {
                            mcpToolCatalogService.setNeeds(requestParameter.getSessionId(), routeDecision.missingToolDescJoined());
                        }
                    }
                    requestParameter.setAiAgentId(agentId);
                    log.info("[Dispatch] 统一路由选中 agent: {} missingTool='{}'",
                            agentId, mcpToolCatalogService != null ? mcpToolCatalogService.needsFor(requestParameter.getSessionId()) : null);
                } else {
                    throw new BizException("未配置路由器且未指定 agentId");
                }
            } else if (unifiedAgentRouter != null && dynamicToolInferenceEnabled) {
                // M1：前端已选定 agent，不走路由，但仍推断该 agent 可能缺失的工具能力（可多条），给动态补挂用
                java.util.List<String> missing = unifiedAgentRouter.inferMissingTool(agentId, requestParameter.getMessage());
                if (missing != null && !missing.isEmpty()) {
                    if (mcpToolCatalogService != null) {
                        mcpToolCatalogService.setNeeds(requestParameter.getSessionId(), String.join("\n", missing));
                    }
                    log.info("[Dispatch] 已选定 agent={} 推断 missingTools={}", agentId, missing);
                }
            }

            checkPreparationActive(__runId);
            if (preparationProgress != null) preparationProgress.phase(__runId, "agent_preparing");
            // 2. 懒加载装配
            if (agentId != null) executionLease = workspaceExecutionGuard.startRun(agentId);
            if (agentId == null || !workspaceAccess.ownsAgent(requestParameter.getUserId(), agentId)) {
                throw new BizException("请先创建或选用自己的 Agent");
            }
            requestParameter.setAgentVersion(workspaceAccess.activeAgentVersion(requestParameter.getUserId(),agentId));
            if (armoryService != null) {
                armoryService.ensureArmed(agentId);
            }

            checkPreparationActive(__runId);
            // 3. 查 agent 信息，取策略
            AiAgentVO aiAgentVO = repository.queryAiAgentByAgentId(agentId);
            if (aiAgentVO == null) {
                throw new BizException("agent 不存在: " + agentId);
            }

            // 路由命中后第一时间把 agent 透传给前端（TTFT：前端先显示"路由中…"，收到本事件后替换成 名称(id)）。
            // 发送失败（客户端断开等）不影响主流程。
            java.util.Map<String, Object> routedPayload = new java.util.LinkedHashMap<>();
            routedPayload.put("agentId", agentId);
            routedPayload.put("agentName", aiAgentVO.getAgentName() == null ? "" : aiAgentVO.getAgentName());
            routedPayload.put("runId", requestParameter.getRunId());
            routedPayload.put("agentVersion",requestParameter.getAgentVersion());
            runEventPublisher.publish(__runId, __needSid, "agent_routed", routedPayload);

            String strategy = aiAgentVO.getStrategy();
            if (runHistory != null) runHistory.bindAgent(requestParameter,strategy);

            IExecuteStrategy executeStrategy = executeStrategyMap.get(strategy);
            if (executeStrategy == null) {
                throw new BizException("不存在的执行策略: " + strategy);
            }
            if (runSnapshotService != null) {
                runSnapshotService.startRun(requestParameter, strategy, aiAgentVO.getAgentName());
            }

            checkPreparationActive(__runId);
            putMdc("agentId", requestParameter.getAiAgentId());
            putMdc("agentVersion", requestParameter.getAgentVersion() == null ? null : requestParameter.getAgentVersion().toString());
            if (preparationProgress != null) preparationProgress.ready(__runId);
            if (sessionRefCounter != null) sessionRefCounter.clear(__needSid);
            strategyStarted = true;
            executeStrategy.execute(requestParameter, emitter);
            var phaseBudget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.find(__runId);
            reviewPaused = phaseBudget != null && phaseBudget.waitingForReview();
            historyStatus(__runId, reviewPaused ? "BLOCKED" : "COMPLETED", null);
            snapshotStatus(__runId, reviewPaused ? "BLOCKED" : "COMPLETED", null);
        } catch (Exception failure) {
            boolean cancelled = failure instanceof java.util.concurrent.CancellationException
                    || failure.getCause() instanceof java.util.concurrent.CancellationException;
            String status = cancelled ? "CANCELLED" : "FAILED";
            historyStatus(__runId, status, failure.getMessage());
            snapshotStatus(__runId, status, failure.getMessage());
            if (cancelled) {
                log.info("[Dispatch] run cancelled during preparation/execution sessionId={} runId={}", __needSid, __runId);
            } else {
                log.error("[Dispatch] preparation/execution failed runId={} agentId={}", __runId, agentId, failure);
                publishFailure(__runId, __needSid, failure);
            }
        } finally {
            try {
                if (preparationProgress != null) preparationProgress.close(__runId);
                try {
                    if (executionLease != null) executionLease.close();
                    if (sessionRefCounter != null) sessionRefCounter.clear(__needSid);
                } finally {
                    try {
                        if (!strategyStarted && mcpToolCatalogService != null) {
                            mcpToolCatalogService.clearNeeds(__needSid);
                            mcpToolCatalogService.cleanupRun(__runId);
                        }
                    } finally {
                        cancelledRuns.remove(__runId);
                        activeRunBySession.remove(__needSid, __runId);
                        runEventPublisher.finishRun(__runId);
                    }
                }
                // A resumed phase must not overtake the initial phase's cleanup.
                if (reviewPaused && flowPlanReviewService != null) flowPlanReviewService.releaseInitialPhase(__runId);
            } finally {
                if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
            }
        }
    }

    private void checkPreparationActive(String runId) {
        if (cancelledRuns.contains(runId)) throw new java.util.concurrent.CancellationException("任务已取消");
        var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.find(runId);
        if (budget != null) budget.checkActive();
    }

    private void snapshotStatus(String runId, String status, String error) {
        if (runSnapshotService == null) return;
        try { runSnapshotService.markStatus(runId, status, error); }
        catch (RuntimeException unavailable) { log.warn("[Dispatch] terminal snapshot unavailable runId={}", runId); }
    }

    private void publishFailure(String runId, String sessionId, Exception failure) {
        boolean rejected = failure instanceof RejectedExecutionException;
        runEventPublisher.publish(runId, sessionId, "message", java.util.Map.of(
                "type", "error", "content", rejected ? "服务繁忙，请稍后重试。"
                        : "执行异常：" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()),
                "sessionId", sessionId));
    }

    private void historyStatus(String run,String status,String error) {
        if(runHistory==null)return;
        try {runHistory.status(run,status,error);}
        catch(RuntimeException e){log.warn("[RunHistory] status update unavailable runId={} status={}",run,status);}
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
        String runId = activeRunBySession.get(sessionId);
        if (runId == null) runId = runEventPublisher.currentRunId(sessionId);
        if (runId != null) cancelledRuns.add(runId);
        var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.find(runId);
        if (budget != null) budget.cancel();
        for (IExecuteStrategy s : executeStrategyMap.values()) {
            try {
                s.cancelExecute(sessionId);
            } catch (Exception e) {
                log.debug("[Dispatch] cancelExecute on {} failed: {}", s.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public boolean cancelExecute(String sessionId, String runId) {
        if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()) return false;
        String activeRunId = activeRunBySession.get(sessionId);
        if (activeRunId == null) activeRunId = runEventPublisher.currentRunId(sessionId);
        if (!runId.equals(activeRunId)) {
            log.info("[Dispatch] ignore stale cancel sessionId={} requestedRunId={} activeRunId={}",
                    sessionId, runId, activeRunId);
            return false;
        }
        cancelExecute(sessionId);
        return true;
    }

    @Override
    public String activeRunId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return activeRunBySession.get(sessionId);
    }

    private static void putMdc(String key, String value) {
        if (value == null || value.isBlank()) {
            org.slf4j.MDC.remove(key);
        } else {
            org.slf4j.MDC.put(key, value);
        }
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) {
            org.slf4j.MDC.remove(key);
        } else {
            org.slf4j.MDC.put(key, value);
        }
    }

}
