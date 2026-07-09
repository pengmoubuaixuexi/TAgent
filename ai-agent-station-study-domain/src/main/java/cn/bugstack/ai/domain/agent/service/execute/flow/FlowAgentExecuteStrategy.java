package cn.bugstack.ai.domain.agent.service.execute.flow;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewService;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewState;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewValidationResult;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.IFlowPlanReviewResumeService;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step4ExecuteStepsNode;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 流程执行策略
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/5 09:56
 */
@Slf4j
@Service("flowAgentExecuteStrategy")
public class FlowAgentExecuteStrategy implements IExecuteStrategy, IFlowPlanReviewResumeService {

    @Resource
    private DefaultFlowAgentExecuteStrategyFactory defaultFlowAgentExecuteStrategyFactory;

    @Resource
    private IAgentRepository repository;

    @Resource
    private Step4ExecuteStepsNode step4ExecuteStepsNode;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Autowired(required = false)
    private FlowPlanReviewService flowPlanReviewService;

    @Autowired(required = false)
    private IArmoryService armoryService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot longTermMemoryTurnSnapshot;

    /** 动态补工具 need 的会话级存储；执行结束按 sessionId 清理（退休 dynamicMissingToolDesc 后必须显式清，否则泄漏+串请求）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService mcpToolCatalogService;

    /** P0（Codex #2）工具调用实证台账；每轮 execute 结束按 runId 清理防泄漏。可选注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolCallLedger toolCallLedger;

    /** per-session 活跃执行上下文，用于支持 cancelExecute() */
    private final ConcurrentHashMap<String, DefaultFlowAgentExecuteStrategyFactory.DynamicContext> activeContexts = new ConcurrentHashMap<>();

    /** 第 61 轮 RAG 引用计数器；每轮入口清一次，保证引用从 [1] 起（修跨题累加成 [9][10]）。 */
    @javax.annotation.Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter sessionRefCounter;

    @Override
    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
        StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultFlowAgentExecuteStrategyFactory.armoryStrategyHandler();

        // 创建动态上下文并初始化必要字段
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(buildInitialTask(executeCommandEntity));
        dynamicContext.setValue("emitter", emitter);
        // v1.3.2：sessionId 显式放进 dynamicContext，避免 dag-step 线程读 MDC 拿不到
        // ReasoningContentFilter.scopeSession 从这里取，按 session 隔离 reasoning_content 缓存
        dynamicContext.setValue("sessionId", executeCommandEntity.getSessionId());
        // 2026-06-23 修跨题串扰：runId 必须每次执行唯一（ReasoningContentFilter 注入缓存按它隔离）；未带则生成。
        String effectiveRunId = (executeCommandEntity.getRunId() != null && !executeCommandEntity.getRunId().isBlank())
                ? executeCommandEntity.getRunId()
                : (executeCommandEntity.getSessionId() + "-run-" + java.util.UUID.randomUUID());
        executeCommandEntity.setRunId(effectiveRunId);
        MDC.put("runId", effectiveRunId);
        MDC.put("agent.run_id", effectiveRunId);
        dynamicContext.setValue("runId", effectiveRunId);
        dynamicContext.setValue("userId", executeCommandEntity.getUserId());
        dynamicContext.setValue("tenantId", executeCommandEntity.getTenantId());
        dynamicContext.setValue("agentId", executeCommandEntity.getAiAgentId());

        // 注册到 activeContexts 以支持 cancelExecute()
        String sessionId = executeCommandEntity.getSessionId();
        String runId = effectiveRunId;
        if (sessionId != null) activeContexts.put(sessionId, dynamicContext);
        // 引用计数器跨轮泄漏修复（2026-05-31）：每轮入口清一次，让引用从 [1] 起；
        // 轮内多步检索仍连续累加。
        if (sessionRefCounter != null && sessionId != null && !sessionId.isBlank()) {
            sessionRefCounter.clear(sessionId);
        }

        // P2.2.4 Step 取消：SSE 客户端断开 / 超时 → 设 cancelled 标记
        emitter.onCompletion(dynamicContext::cancel);
        emitter.onTimeout(dynamicContext::cancel);
        emitter.onError(e -> dynamicContext.cancel());

        try {
            String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
            log.info("流程执行结果:{}", apply);

            // 发送完成标识
            try {
                AutoAgentExecuteResultEntity completeResult = AutoAgentExecuteResultEntity.createCompleteResult(executeCommandEntity.getSessionId());
                // 发送SSE格式的数据
                String sseData = "data: " + JSON.toJSONString(completeResult) + "\n\n";
                emitter.send(sseData);
            } catch (Exception e) {
                log.error("发送完成标识失败：{}", e.getMessage(), e);
            }
        } finally {
            longTermMemoryTurnSnapshot.clearSession(sessionId);
            boolean removedActiveContext = sessionId != null && activeContexts.remove(sessionId, dynamicContext);
            if (sessionId != null && mcpToolCatalogService != null && removedActiveContext) mcpToolCatalogService.clearNeeds(sessionId);
            if (runId != null && mcpToolCatalogService != null) mcpToolCatalogService.cleanupRun(runId);
            // 2026-06-23：清掉本次 runId 的 reasoning_content 注入缓存（按 runId 隔离后只增不减，须在此清理防泄漏）
            cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.clearRun(runId);
            // P0（Codex #2）：清掉本次 runId 的工具调用实证台账（按 runId 隔离，须在此清理防泄漏）
            if (runId != null && toolCallLedger != null) toolCallLedger.clear(runId);
        }
    }

    @Override
    public void resumeReviewedPlan(FlowPlanReviewState state,
                                   FlowPlanReviewValidationResult approvedPlan,
                                   ResponseBodyEmitter emitter) {
        if (state == null || approvedPlan == null || !approvedPlan.isValid()) {
            sendSseObject(emitter, "message", AutoAgentExecuteResultEntity.createErrorResult(
                    "Invalid reviewed plan", state != null ? state.getSessionId() : null));
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            return;
        }

        threadPoolExecutor.execute(() -> {
            ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                    .aiAgentId(state.getAgentId())
                    .message(state.getOriginalMessage())
                    .sessionId(state.getSessionId())
                    .runId(state.getRunId())
                    .sourceRunId(state.getSourceRunId())
                    .redoFromStep(state.getRedoFromStep())
                    .redoContextPrompt(state.getRedoContextPrompt())
                    .redoTargetStepContextPrompt(state.getRedoTargetStepContextPrompt())
                    .userId(state.getUserId())
                    .tenantId(state.getTenantId())
                    .maxStep(4)
                    .build();
            DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                    new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
            Integer planReviewAttemptCount = state.getAttemptCount();
            dynamicContext.setExecutionHistory(new StringBuilder());
            dynamicContext.setCurrentTask(buildInitialTask(request));
            dynamicContext.setStep(4);
            dynamicContext.setValue("emitter", emitter);
            dynamicContext.setValue("sessionId", state.getSessionId());
            dynamicContext.setValue("runId", state.getRunId());
            dynamicContext.setValue("userId", state.getUserId());
            dynamicContext.setValue("tenantId", state.getTenantId());
            dynamicContext.setValue("agentId", state.getAgentId());
            dynamicContext.setValue("planningResult", state.getPlanningResult());
            dynamicContext.setValue("mcpToolsAnalysis", state.getMcpToolsAnalysis());
            dynamicContext.setValue("stepsMap", approvedPlan.getStepsMap());
            dynamicContext.setValue("stepDependencies", approvedPlan.getStepDependencies());
            dynamicContext.setValue("planReviewResumed", Boolean.TRUE);
            dynamicContext.setValue("planReviewAttemptCount", planReviewAttemptCount);
            dynamicContext.setAiAgentClientFlowConfigVOMap(repository.queryAiAgentClientFlowConfig(state.getAgentId()));

            String sessionId = state.getSessionId();
            String runId = state.getRunId();
            if (sessionId != null) {
                activeContexts.put(sessionId, dynamicContext);
            }
            if (sessionRefCounter != null && sessionId != null && !sessionId.isBlank()) {
                sessionRefCounter.clear(sessionId);
            }
            emitter.onCompletion(dynamicContext::cancel);
            emitter.onTimeout(dynamicContext::cancel);
            emitter.onError(e -> dynamicContext.cancel());

            boolean[] completed = {false};
            boolean[] cancelled = {false};
            String[] failureMessage = {null};

            try {
                putMdc("agentId", state.getAgentId());
                putMdc("sessionId", state.getSessionId());
                putMdc("runId", runId);
                putMdc("agent.run_id", runId);
                putMdc("userId", state.getUserId());
                putMdc("tenantId", state.getTenantId());
                ensureAgentArmedForPlanResume(state.getAgentId(), runId, sessionId);
                if (mcpToolCatalogService != null) {
                    mcpToolCatalogService.setNeeds(sessionId, state.getMcpNeeds());
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", runId);
                payload.put("sessionId", sessionId);
                payload.put("status", FlowPlanReviewService.STATUS_RUNNING);
                payload.put("stepCount", approvedPlan.getStepsMap() != null ? approvedPlan.getStepsMap().size() : 0);
                sendSseObject(emitter, "plan_review_resumed", payload);

                String apply = step4ExecuteStepsNode.apply(request, dynamicContext);
                log.info("[FlowPlanReview] resumed Step4 result runId={} result={}", runId, apply);
                if (dynamicContext.isCancelled()) {
                    cancelled[0] = true;
                    failureMessage[0] = "execution cancelled";
                } else {
                    completed[0] = true;
                    sendSseObject(emitter, "message", AutoAgentExecuteResultEntity.createCompleteResult(sessionId));
                }
            } catch (CancellationException e) {
                cancelled[0] = true;
                failureMessage[0] = e.getMessage();
                log.info("[FlowPlanReview] resume cancelled runId={} sessionId={} msg={}", runId, sessionId, e.getMessage());
            } catch (Exception e) {
                if (dynamicContext.isCancelled()) {
                    cancelled[0] = true;
                }
                failureMessage[0] = e.getMessage();
                log.error("[FlowPlanReview] resume failed runId={} sessionId={}", runId, sessionId, e);
                sendSseObject(emitter, "message", AutoAgentExecuteResultEntity.createErrorResult(e.getMessage(), sessionId));
            } finally {
                if (flowPlanReviewService != null) {
                    if (completed[0]) {
                        flowPlanReviewService.markExecutionCompleted(runId, planReviewAttemptCount);
                        sendPlanReviewStatus(emitter, runId, sessionId, FlowPlanReviewService.STATUS_COMPLETED, null);
                    } else if (cancelled[0] || dynamicContext.isCancelled()) {
                        flowPlanReviewService.markExecutionCancelled(runId, failureMessage[0], planReviewAttemptCount);
                        sendPlanReviewStatus(emitter, runId, sessionId, FlowPlanReviewService.STATUS_CANCELLED, failureMessage[0]);
                    } else {
                        flowPlanReviewService.markExecutionFailed(runId, failureMessage[0], planReviewAttemptCount);
                        sendPlanReviewStatus(emitter, runId, sessionId, FlowPlanReviewService.STATUS_FAILED, failureMessage[0]);
                    }
                }
                longTermMemoryTurnSnapshot.clearSession(sessionId);
                boolean removedActiveContext = sessionId != null && activeContexts.remove(sessionId, dynamicContext);
                if (sessionId != null && mcpToolCatalogService != null && removedActiveContext) mcpToolCatalogService.clearNeeds(sessionId);
                if (removedActiveContext) {
                    if (runId != null && mcpToolCatalogService != null) mcpToolCatalogService.cleanupRun(runId);
                    cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.clearRun(runId);
                    if (runId != null && toolCallLedger != null) toolCallLedger.clear(runId);
                }
                clearMdc("agentId", "sessionId", "runId", "agent.run_id", "userId", "tenantId");
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void sendPlanReviewStatus(ResponseBodyEmitter emitter,
                                      String runId,
                                      String sessionId,
                                      String status,
                                      String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("sessionId", sessionId);
        payload.put("status", status);
        payload.put("lastError", error);
        payload.put("timestamp", System.currentTimeMillis());
        sendSseObject(emitter, "plan_review_status", payload);
    }

    private String buildInitialTask(ExecuteCommandEntity request) {
        if (request == null) return "";
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String redoContext = request.getRedoContextPrompt();
        if (redoContext == null || redoContext.isBlank()) {
            return message;
        }
        return redoContext.trim() + "\n\n【用户本次修订指令】\n" + message;
    }

    private void ensureAgentArmedForPlanResume(String agentId, String runId, String sessionId) {
        if (armoryService == null || agentId == null || agentId.isBlank()) {
            return;
        }
        if (armoryService.isAgentArmed(agentId)) {
            return;
        }
        log.info("[FlowPlanReview] resume armory ensure agentId={} runId={} sessionId={}", agentId, runId, sessionId);
        armoryService.ensureArmed(agentId);
    }

    private void sendSseObject(ResponseBodyEmitter emitter, String event, Object payload) {
        if (emitter == null || payload == null) {
            return;
        }
        try {
            StringBuilder frame = new StringBuilder();
            if (event != null && !"message".equals(event)) {
                frame.append("event: ").append(event).append('\n');
            }
            frame.append("data: ").append(JSON.toJSONString(payload)).append("\n\n");
            synchronized (emitter) {
                emitter.send(frame.toString());
            }
        } catch (Exception e) {
            log.debug("[FlowPlanReview] send SSE failed event={} err={}", event, e.getMessage());
        }
    }

    private void putMdc(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private void clearMdc(String... keys) {
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    @Override
    public void cancelExecute(String sessionId) {
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null) {
            ctx.cancel();
            ctx.fireCancelTrigger();  // 立即截断在飞流式调用，不等当前 LLM 调用跑完才在下个 checkpoint 生效
            if (flowPlanReviewService != null && Boolean.TRUE.equals(ctx.getValue("planReviewResumed"))) {
                String runId = ctx.getValue("runId");
                Integer attemptCount = ctx.getValue("planReviewAttemptCount");
                flowPlanReviewService.markExecutionCancelled(runId, "execution cancelled by user", attemptCount);
            }
            log.info("[FlowAgent] cancelExecute called for sessionId={}", sessionId);
        }
    }

    /** 立即回答：置 finalize 标记 + 截断当前在飞流式 call；step4 据标记停止调度剩余子步、直接整合。 */
    @Override
    public void finalizeExecute(String sessionId) {
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null) {
            ctx.requestFinalize();
            ctx.fireCancelTrigger();
            log.info("[FlowAgent] finalizeExecute (answer-now) for sessionId={}", sessionId);
        }
    }

    /** 引导回复：写入新想法 + 截断当前步（flow 进 step4 后前端已禁引导，此处仍兜底）。 */
    @Override
    public void steerExecute(String sessionId, String idea) {
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null && idea != null && !idea.isBlank()) {
            // flow 进入 step4 执行阶段后禁止引导（不消费 steerIdea），忽略以免无意义断流
            if (Boolean.TRUE.equals(ctx.getValue("flowInExecution"))) {
                log.info("[FlowAgent] steer ignored: in step4 execution phase, sessionId={}", sessionId);
                return;
            }
            ctx.setSteerIdea(idea);
            ctx.fireCancelTrigger();
            log.info("[FlowAgent] steerExecute for sessionId={} ideaLen={}", sessionId, idea.length());
        }
    }

}
