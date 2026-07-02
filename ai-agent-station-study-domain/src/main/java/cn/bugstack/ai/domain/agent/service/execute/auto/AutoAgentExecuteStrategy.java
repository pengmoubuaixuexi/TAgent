package cn.bugstack.ai.domain.agent.service.execute.auto;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动执行策略
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/5 09:49
 */
@Slf4j
@Service("autoAgentExecuteStrategy")
public class AutoAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot longTermMemoryTurnSnapshot;

    /** 动态补工具 need 的会话级存储；执行结束按 sessionId 清理（退休 dynamicMissingToolDesc 后必须显式清，否则泄漏+串请求）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService mcpToolCatalogService;

    /** P0（Codex #2）工具调用实证台账；每轮 execute 结束按 runId 清理防泄漏。可选注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolCallLedger toolCallLedger;

    /** per-session 活跃执行上下文，用于支持 cancelExecute() */
    private final ConcurrentHashMap<String, DefaultAutoAgentExecuteStrategyFactory.DynamicContext> activeContexts = new ConcurrentHashMap<>();

    /** 第 61 轮 RAG 引用计数器；每轮入口清一次，保证引用从 [1] 起（修跨题累加成 [9][10]）。 */
    @javax.annotation.Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter sessionRefCounter;

    @Override
    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler();

        // 创建动态上下文并初始化必要字段
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : 3);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
        dynamicContext.setValue("emitter", emitter);
        // v1.3.2：sessionId 显式放进 dynamicContext，避免 dag-step 线程读 MDC 拿不到（MdcTaskDecorator 未启用）
        // ReasoningContentFilter.scopeSession 从这里取，按 session 隔离 reasoning_content 缓存
        dynamicContext.setValue("sessionId", executeCommandEntity.getSessionId());
        // 2026-06-23 修跨题串扰：runId 必须每次执行唯一（ReasoningContentFilter 注入缓存按它隔离，避免同 session
        // 多题共用 reasoning_content 缓存把别题思考注入本题）；调用方（如 E2E/未走网关）未带则在此生成。
        String effectiveRunId = (executeCommandEntity.getRunId() != null && !executeCommandEntity.getRunId().isBlank())
                ? executeCommandEntity.getRunId()
                : (executeCommandEntity.getSessionId() + "-run-" + java.util.UUID.randomUUID());
        dynamicContext.setValue("runId", effectiveRunId);
        dynamicContext.setValue("userId", executeCommandEntity.getUserId());
        dynamicContext.setValue("tenantId", executeCommandEntity.getTenantId());
        dynamicContext.setValue("agentId", executeCommandEntity.getAiAgentId());

        // 注册到 activeContexts 以支持 cancelExecute()
        String sessionId = executeCommandEntity.getSessionId();
        String runId = effectiveRunId;
        if (sessionId != null) activeContexts.put(sessionId, dynamicContext);
        // 引用计数器跨轮泄漏修复（2026-05-31）：每轮入口清一次，让引用从 [1] 起；
        // 轮内 Step2 多 client 检索仍连续累加（[1][2] / [3][4]）。
        if (sessionRefCounter != null && sessionId != null && !sessionId.isBlank()) {
            sessionRefCounter.clear(sessionId);
        }

        // P2.2.4 Step 取消：SSE 客户端断开 / 超时 → 设 cancelled 标记，后续 step 跳过 LLM 调用省 token
        emitter.onCompletion(dynamicContext::cancel);
        emitter.onTimeout(dynamicContext::cancel);
        emitter.onError(e -> dynamicContext.cancel());

        try {
            String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
            log.info("测试结果:{}", apply);

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
            if (sessionId != null) activeContexts.remove(sessionId);
            if (sessionId != null && mcpToolCatalogService != null) mcpToolCatalogService.clearNeeds(sessionId);
            if (runId != null && mcpToolCatalogService != null) mcpToolCatalogService.cleanupRun(runId);
            // 2026-06-23：清掉本次 runId 的 reasoning_content 注入缓存（按 runId 隔离后只增不减，须在此清理防泄漏）
            cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.clearRun(runId);
            // P0（Codex #2）：清掉本次 runId 的工具调用实证台账（按 runId 隔离，须在此清理防泄漏）
            if (runId != null && toolCallLedger != null) toolCallLedger.clear(runId);
        }
    }

    @Override
    public void cancelExecute(String sessionId) {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null) {
            ctx.cancel();
            ctx.fireCancelTrigger();  // 立即截断在飞流式调用，不等当前 LLM 调用跑完才在下个 checkpoint 生效
            log.info("[AutoAgent] cancelExecute called for sessionId={}", sessionId);
        }
    }

    /** 立即回答：置 finalize 标记 + 截断当前在飞流式 call；各 step 入口/汇总节点据标记跳 finalize。 */
    @Override
    public void finalizeExecute(String sessionId) {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null) {
            ctx.requestFinalize();
            ctx.fireCancelTrigger();
            log.info("[AutoAgent] finalizeExecute (answer-now) for sessionId={}", sessionId);
        }
    }

    /** 引导回复：写入新想法 + 截断当前步，重跑时折进 idea。 */
    @Override
    public void steerExecute(String sessionId, String idea) {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null && idea != null && !idea.isBlank()) {
            ctx.setSteerIdea(idea);
            ctx.fireCancelTrigger();
            log.info("[AutoAgent] steerExecute for sessionId={} ideaLen={}", sessionId, idea.length());
        }
    }

}
