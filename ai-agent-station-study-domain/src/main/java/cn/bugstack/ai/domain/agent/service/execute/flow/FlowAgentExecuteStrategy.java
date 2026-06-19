package cn.bugstack.ai.domain.agent.service.execute.flow;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程执行策略
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/5 09:56
 */
@Slf4j
@Service("flowAgentExecuteStrategy")
public class FlowAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultFlowAgentExecuteStrategyFactory defaultFlowAgentExecuteStrategyFactory;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot longTermMemoryTurnSnapshot;

    /** 动态补工具 need 的会话级存储；执行结束按 sessionId 清理（退休 dynamicMissingToolDesc 后必须显式清，否则泄漏+串请求）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService mcpToolCatalogService;

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
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
        dynamicContext.setValue("emitter", emitter);
        // v1.3.2：sessionId 显式放进 dynamicContext，避免 dag-step 线程读 MDC 拿不到
        // ReasoningContentFilter.scopeSession 从这里取，按 session 隔离 reasoning_content 缓存
        dynamicContext.setValue("sessionId", executeCommandEntity.getSessionId());
        dynamicContext.setValue("userId", executeCommandEntity.getUserId());
        dynamicContext.setValue("tenantId", executeCommandEntity.getTenantId());
        dynamicContext.setValue("agentId", executeCommandEntity.getAiAgentId());

        // 注册到 activeContexts 以支持 cancelExecute()
        String sessionId = executeCommandEntity.getSessionId();
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
            if (sessionId != null) activeContexts.remove(sessionId);
            if (sessionId != null && mcpToolCatalogService != null) mcpToolCatalogService.clearNeeds(sessionId);
        }
    }

    @Override
    public void cancelExecute(String sessionId) {
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null) {
            ctx.cancel();
            ctx.fireCancelTrigger();  // 立即截断在飞流式调用，不等当前 LLM 调用跑完才在下个 checkpoint 生效
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
