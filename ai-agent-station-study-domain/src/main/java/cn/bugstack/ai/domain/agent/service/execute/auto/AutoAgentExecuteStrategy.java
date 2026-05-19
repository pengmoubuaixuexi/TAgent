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
 * @author TAgent
 * 2025/8/5 09:49
 */
@Slf4j
@Service("autoAgentExecuteStrategy")
public class AutoAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    /** per-session 活跃执行上下文，用于支持 cancelExecute() */
    private final ConcurrentHashMap<String, DefaultAutoAgentExecuteStrategyFactory.DynamicContext> activeContexts = new ConcurrentHashMap<>();

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
        dynamicContext.setValue("userId", executeCommandEntity.getUserId());
        dynamicContext.setValue("tenantId", executeCommandEntity.getTenantId());
        dynamicContext.setValue("agentId", executeCommandEntity.getAiAgentId());

        // 注册到 activeContexts 以支持 cancelExecute()
        String sessionId = executeCommandEntity.getSessionId();
        if (sessionId != null) activeContexts.put(sessionId, dynamicContext);

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
            if (sessionId != null) activeContexts.remove(sessionId);
        }
    }

    @Override
    public void cancelExecute(String sessionId) {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx = activeContexts.get(sessionId);
        if (ctx != null) {
            ctx.cancel();
            log.info("[AutoAgent] cancelExecute called for sessionId={}", sessionId);
        }
    }

}
