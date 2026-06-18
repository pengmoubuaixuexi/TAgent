package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.working.IWorkingMemoryService;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.OutputModerationFilter;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 抽象类
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/24 14:28
 */
public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected IAgentRepository repository;

    /**
     * LLM 调用必须经 Gateway 走 Spring 代理，否则 @Retry/@CircuitBreaker 被自调用绕开。
     * 亮点 4 Part B：Resilience4j 容错在此生效。
     */
    @Resource
    protected LlmCallGateway llmCallGateway;

    /**
     * 亮点 4 Part D：每次 LLM 调用后把 latency / tokens / outcome 埋到 Micrometer，
     * 经 /actuator/prometheus 端点被 Prometheus 拉走。
     */
    @Resource
    protected LlmObservationRecorder llmObservationRecorder;

    /** P1.2.2：Working Memory 旁路镜像；默认 NoopWorkingMemoryService，开关打开后切 Redis 实现 */
    @Resource
    protected IWorkingMemoryService workingMemory;

    @Resource
    protected AgentToolRegistry dynamicAgentToolRegistry;

    @Resource
    protected McpToolCatalogService dynamicMcpToolCatalogService;

    /** P2.4 13.2 Token Budget：每步最大输出 token 数（0=不限） */
    @org.springframework.beans.factory.annotation.Value("${agent.token-budget.step1-max-tokens:0}")
    protected int step1MaxTokens;
    @org.springframework.beans.factory.annotation.Value("${agent.token-budget.step2-max-tokens:0}")
    protected int step2MaxTokens;
    @org.springframework.beans.factory.annotation.Value("${agent.token-budget.step3-max-tokens:0}")
    protected int step3MaxTokens;
    @org.springframework.beans.factory.annotation.Value("${agent.token-budget.step4-max-tokens:0}")
    protected int step4MaxTokens;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";
    public static final String LTM_RETRIEVAL_QUERY_KEY = "ltm_retrieval_query";

    protected List<ToolCallback> resolveAgentDynamicToolCallbacks(ExecuteCommandEntity requestParameter, String clientId) {
        if (requestParameter == null || clientId == null || dynamicMcpToolCatalogService == null) {
            return List.of();
        }
        return dynamicMcpToolCatalogService.resolveDynamicToolCallbacks(clientId,
                requestParameter.getDynamicMissingToolDesc(), requestParameter.getMessage(),
                dynamicAgentToolRegistry != null ? dynamicAgentToolRegistry.getTools(clientId) : List.of());
    }

    /**
     * 构造 per-request 工具回调数组 = 该 client 常驻工具 + 路由动态补充工具的并集。
     * <p>Spring AI 的 per-request toolCallbacks 非空时会整体替换常驻工具，必须把常驻工具一并带上，
     * 否则补了动态工具反而把 agent 自己的工具挤掉（见 {@link AgentToolRegistry#combineWithResident}）。
     */
    protected ToolCallback[] toRequestToolCallbacks(String clientId, List<ToolCallback> dynamicToolCallbacks) {
        List<ToolCallback> combined = dynamicAgentToolRegistry != null
                ? dynamicAgentToolRegistry.combineWithResident(clientId, dynamicToolCallbacks)
                : dynamicToolCallbacks;
        return combined.toArray(new ToolCallback[0]);
    }

    protected String buildLtmRetrievalQuery(ExecuteCommandEntity req, String stage) {
        if (req == null) return "";
        String message = req.getMessage();
        if (message == null) message = "";
        return message;
    }

    /**
     * 引导感知的 RAG/LTM 检索 query：优先用 currentTask（可能已被 {@link #foldSteerIntoCurrentTask} 折入引导），
     * 回退到原始 message。供 advisor 的 {@code ltm_retrieval_query} 参数——必须在 specBuilder lambda 里<b>每轮实时调用</b>，
     * 这样引导重跑时 RAG 才用新任务检索（修复"RAG 仍按原始问题检索"）。currentTask 始终是干净的用户任务，不含工具脚手架，
     * 契合 V041 给 RAG/LTM 喂干净问题的初衷。
     */
    protected String steerAwareRetrievalQuery(DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx, ExecuteCommandEntity req) {
        String task = ctx == null ? null : ctx.getCurrentTask();
        if (task != null && !task.isBlank()) return task;
        return buildLtmRetrievalQuery(req, null);
    }

    protected String appendCurrentTimeContext(String promptText) {
        String base = promptText == null ? "" : promptText;
        if (base.contains("【当前时间】")) return base;
        return base + "\n\n【当前时间】\n" + currentTimeForPrompt()
                + "\n涉及 today/今天/明天/最近/截止时间/时区等判断时，必须以这个时间为准。";
    }

    protected String currentTimeForPrompt() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault());
        return now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX '['VV']'"));
    }

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    /**
     * 与 auto 策略镜像的 LLM 调用指标采集：stepName / model / tokens / latencyMs 写 MDC 触发 log.info，
     * LogstashEncoder 会把 MDC 序列化为顶级 JSON 字段，Kibana / observe 面板得以按模型/会话聚合。
     */
    protected String callChatClientWithLogging(Supplier<ChatClient.CallResponseSpec> specSupplier, String stepName, String promptText) {
        long start = System.currentTimeMillis();
        // 走 Gateway：@Retry + @CircuitBreaker 在这一跳生效；全部失败后返回 null，走下方原有的空值分支
        // 传 Supplier 是因为 Spring AI 的 CallResponseSpec 非幂等：每次重试必须 specSupplier.get() 造一个全新的
        ChatResponse response = llmCallGateway.call(specSupplier);
        long latency = System.currentTimeMillis() - start;

        // v1.3.1 (2026-05-14)：token/cost/cached 提取 + Prometheus/event_log/MDC→ES 写入统一由 LlmObservationRecorder 处理。
        // 只保留 model 给 ctx.model fallback；step 也 put 一次让同 step 内后续应用日志带 step 字段（recorder 内部会 restore）。
        String model = response != null && response.getMetadata() != null && response.getMetadata().getModel() != null
                ? response.getMetadata().getModel() : "";
        MDC.put("step", stepName);

        boolean success = response != null && response.getResult() != null && response.getResult().getOutput() != null;
        if (!success) {
            // failure 路径也送一次 recorder，让 Prometheus 的 outcome=failure 计数 + ES 留痕
            llmObservationRecorder.record(buildCallContext(stepName, promptText, null, model), response, latency,
                    new IllegalStateException("empty non-streaming response"));
            return null;
        }
        // P2.5 14.2 PII 脱敏 + 14.3 输出审核
        String text = response.getResult().getOutput().getText();
        llmObservationRecorder.record(buildCallContext(stepName, promptText, text, model), response, latency, null);
        text = OutputModerationFilter.check(text);
        return text;
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    /**
     * P2.2.4 Step 取消支持：在 step 入口或 LLM 调用前检查客户端是否已断开。
     */
    protected void checkCancelled(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
            log.info("[Cancel] 客户端已断开或执行被取消，跳过后续 step 执行");
            throw new CancellationException("SSE client disconnected or execution cancelled");
        }
    }

    /**
     * 立即回答路由钩子（flow）：finalizeRequested 置位时直接跳 Step4，由其顶部 finalizeNow 分支基于
     * "计划 + 已完成产出 + 半截思考"整合作答，短路剩余步骤。未置位返 null → 原流程逐字节不变。
     */
    protected String checkFinalizeRoute(ExecuteCommandEntity req,
                                        DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx) throws Exception {
        if (!ctx.isFinalizeRequested()) return null;
        log.info("[AnswerNow][flow] finalize requested → 跳 Step4 直接整合");
        cn.bugstack.wrench.design.framework.tree.StrategyHandler<ExecuteCommandEntity,
                DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> step4 = getBean("step4ExecuteStepsNode");
        return step4.apply(req, ctx);
    }

    /**
     * 2026-05-20：把当前 step 末尾的 DynamicContext 演化写一行到独立 logger {@code step.transition}，→ ES。
     * <p>
     * 调用方约定：每个 Step 节点在 {@code return router(req, ctx)} 之前调一次，
     * 让 Kibana 按 traceId 排序就能看到 flow step1→2→3→4 之间 dataObjects 怎么接力。
     */
    protected void recordTransition(String fromStep, DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx) {
        if (ctx == null) return;
        cn.bugstack.ai.domain.agent.service.execute.common.StepTransitionRecorder.record(
                fromStep, ctx.getDataObjects(), ctx.getExecutionHistory(), ctx.getStep(), ctx.isCompleted());
    }

    /**
     * P1.2.3 多租户隔离：构建复合 conversationId = {tenantId}:{userId}:{sessionId}。
     * 与 auto 侧镜像，让不同租户/用户的对话天然隔离。
     */
    /**
     * V035 (2026-05-14)：构造事件日志 entry，自动从 MDC 提取 userId / tenantId / agentId / sessionId。
     */
    protected LlmCallContext buildCallContext(String stepName, String promptText, String resultText, String model) {
        return LlmCallContext.builder()
                .sessionId(firstNonBlank(MDC.get("sessionId"), MDC.get("requestId")))
                .userId(MDC.get("userId"))
                .tenantId(MDC.get("tenantId"))
                .agentId(MDC.get("agentId"))
                .stepName(stepName)
                .prompt(promptText)
                .resultText(resultText)
                .model(model)
                .build();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    protected String buildConversationId(ExecuteCommandEntity req) {
        if (req == null) return null;
        String tid = req.getTenantId();
        String uid = req.getUserId();
        String sid = req.getSessionId();
        if (tid == null || tid.isBlank()) {
            if (uid == null || uid.isBlank()) return sid;
            return uid + ":" + sid;
        }
        if (uid == null || uid.isBlank()) return tid + ":" + sid;
        return tid + ":" + uid + ":" + sid;
    }

    /**
     * P1.2.2 Working Memory 旁路写：把 step 关键产物镜像到 Redis（开关关闭时降级为 Noop）。
     * 与 auto 侧 {@code mirrorToWorkingMemory} 镜像；命名约定 {@code flow.step{N}.{name}}。
     */
    protected void mirrorToWorkingMemory(String sessionId, String key, Object value) {
        if (sessionId == null || sessionId.isBlank() || key == null) return;
        log.info("[WM] mirror sessionId={} key={} impl={} persistent={}",
                sessionId, key, workingMemory.getClass().getSimpleName(), workingMemory.isPersistent());
        workingMemory.put(sessionId, key, value);
    }

    /**
     * 通用的SSE结果发送方法
     * @param dynamicContext 动态上下文
     * @param result 要发送的结果实体
     */
    protected void sendSseResult(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                AutoAgentExecuteResultEntity result) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                // 过滤 <think> 思考块
                if (result.getContent() != null) {
                    result.setContent(cn.bugstack.ai.domain.agent.service.security.OutputFilter.stripThinkTags(result.getContent()));
                }
                // 发送SSE格式的数据
                String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
                // 并行 DAG 步骤共用一个 emitter，ResponseBodyEmitter.send 非线程安全 →
                // 同步写入避免两步的 SSE 帧字节交错（前端才能按 stepId 干净地分卡渲染）。
                synchronized (emitter) { emitter.send(sseData); }
            }
        } catch (IOException e) {
            log.error("发送SSE结果失败：{}", e.getMessage(), e);
        }
    }

    /** P2.7 16.2 Thinking Visualization 开关；与 auto 侧共用同一配置项 */
    @org.springframework.beans.factory.annotation.Value("${agent.thinking-vis.enabled:true}")
    protected boolean thinkingVisEnabled;

    /**
     * P2.7 16.2 Thinking Visualization：发送中间思考过程（event: thinking）。
     * 前端可监听该事件类型展示 Agent 内部推理流程。
     */
    protected void sendThinkingEvent(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                     String title, String content, String sessionId) {
        if (!thinkingVisEnabled) return;
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                Map<String, Object> thinking = new LinkedHashMap<>();
                thinking.put("title", title);
                thinking.put("content", content);
                thinking.put("sessionId", sessionId);
                thinking.put("timestamp", System.currentTimeMillis());
                emitter.send("event: thinking\ndata: " + JSON.toJSONString(thinking) + "\n\n");
            }
        } catch (IOException e) {
            log.debug("发送 thinking SSE 失败：{}", e.getMessage());
        }
    }

    // ==================== P2.7 16.1 Token-Level Streaming ====================

    @org.springframework.beans.factory.annotation.Value("${agent.token-streaming.enabled:false}")
    protected boolean tokenStreamingEnabled;

    /**
     * 发送单个 token 的 SSE 事件（event: token）。
     * stepId 区分多步流式输出归属，前端按 stepId 追加到对应 step 卡片。
     */
    protected void sendTokenEvent(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                   String token, String stepId, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("token", token);
                payload.put("stepId", stepId);
                payload.put("sessionId", sessionId);
                payload.put("timestamp", System.currentTimeMillis());
                synchronized (emitter) { emitter.send("event: token\ndata: " + JSON.toJSONString(payload) + "\n\n"); }
            }
        } catch (IOException e) {
            log.debug("发送 token SSE 失败：{}", e.getMessage());
        }
    }

    /**
     * 2026-05-07 流式 UX：标记一个 step 开始（前端展开新卡片，开始接 token 流）
     */
    protected void sendStepStart(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                 String stepId, String displayName, String sessionId) {
        sendStepStart(dynamicContext, stepId, displayName, java.util.Collections.emptySet(), sessionId);
    }

    /**
     * 2026-05-07 DAG：携带依赖关系的 step_start，前端可显示"依赖步骤 X"标签。
     * @param dependsOn 当前 step 等待哪些 stepId 完成（执行时它们已经 done，传给前端是为了展示历史关系）
     */
    protected void sendStepStart(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                 String stepId, String displayName,
                                 java.util.Set<String> dependsOn, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter == null) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stepId", stepId);
            payload.put("displayName", displayName);
            if (dependsOn != null && !dependsOn.isEmpty()) {
                payload.put("dependsOn", dependsOn);
            }
            payload.put("sessionId", sessionId);
            payload.put("timestamp", System.currentTimeMillis());
            synchronized (emitter) { emitter.send("event: step_start\ndata: " + JSON.toJSONString(payload) + "\n\n"); }
        } catch (IOException e) {
            log.debug("发送 step_start 失败：{}", e.getMessage());
        }
    }

    /**
     * 2026-05-07 流式 UX：标记一个 step 结束（前端把当前卡片折叠为 summary 一行）
     */
    protected void sendStepEnd(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                               String stepId, String summary, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter == null) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stepId", stepId);
            payload.put("summary", summary);
            payload.put("sessionId", sessionId);
            payload.put("timestamp", System.currentTimeMillis());
            synchronized (emitter) { emitter.send("event: step_end\ndata: " + JSON.toJSONString(payload) + "\n\n"); }
        } catch (IOException e) {
            log.debug("发送 step_end 失败：{}", e.getMessage());
        }
    }

    /**
     * 2026-05-07 流式 UX 包装器：step_start → 流式调用 LLM → step_end。
     * 与 auto 侧 callStepWithStreaming 镜像。
     */
    protected String callStepWithStreaming(
            ChatClient.ChatClientRequestSpec spec,
            DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName, String promptText, String sessionId) {
        return callStepWithStreaming(spec, dynamicContext, stepId, displayName,
                java.util.Collections.emptySet(), promptText, sessionId);
    }

    /**
     * 2026-05-07 DAG UX：在所有 step 真正开始执行前，先把所有步骤的"占位卡片"一次性广播给前端。
     * 前端立即把整张计划画出来，没轮到的 step 显示"等待依赖步骤完成"。
     */
    protected void sendStepPending(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                   String stepId, String displayName,
                                   java.util.Set<String> dependsOn, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter == null) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stepId", stepId);
            payload.put("displayName", displayName);
            if (dependsOn != null && !dependsOn.isEmpty()) {
                payload.put("dependsOn", dependsOn);
            }
            payload.put("sessionId", sessionId);
            payload.put("timestamp", System.currentTimeMillis());
            emitter.send("event: step_pending\ndata: " + JSON.toJSONString(payload) + "\n\n");
        } catch (IOException e) {
            log.debug("发送 step_pending 失败：{}", e.getMessage());
        }
    }

    /** 2026-05-07 DAG：带依赖关系的版本 */
    protected String callStepWithStreaming(
            ChatClient.ChatClientRequestSpec spec,
            DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName, java.util.Set<String> dependsOn,
            String promptText, String sessionId) {
        sendStepStart(dynamicContext, stepId, displayName, dependsOn, sessionId);
        // G1-C 修复：sessionId 注入 ToolContext，跟随调用流到 Reactor 工具执行线程（MDC 在那为空）。
        // MeteredToolCallback 优先从 ToolContext 取 sessionId 才能弹人工审批 / 发进度事件。
        // 用 final 局部变量而非重赋值参数：下方 spec::call 方法引用要求被捕获变量 effectively final。
        // stepLabel(displayName) 让 DAG 并行审批时前端能标注是哪个步骤要的许可。
        // 注：动态补挂的工具由各 step 节点通过 spec.options(OpenAiChatOptions.toolCallbacks(...)) 注入，这里不重复注入。
        log.info("[ToolCtxDiag][flow] step={} sessionId={} inject={}", stepId, sessionId, (sessionId != null && !sessionId.isBlank()));
        final ChatClient.ChatClientRequestSpec callSpec = (sessionId != null && !sessionId.isBlank())
                ? spec.toolContext(buildToolContext(sessionId, displayName))
                : spec;
        String result;
        try {
            if (tokenStreamingEnabled) {
                result = callChatClientWithTokenStreaming(callSpec, dynamicContext, stepId, promptText);
            } else {
                result = callChatClientWithLogging(callSpec::call, stepId, promptText);
            }
        } finally {
            sendStepEnd(dynamicContext, stepId, displayName + " 已完成", sessionId);
        }
        return result;
    }

    /**
     * 构建注入到 Spring AI ToolContext 的 map：sessionId（工具线程查 SSE 通道）+ stepLabel（DAG 并行审批区分步骤）。
     * 用 HashMap 而非 Map.of —— stepLabel 可能为 null，Map.of 不允许 null value。
     */
    protected static java.util.Map<String, Object> buildToolContext(String sessionId, String stepLabel) {
        java.util.Map<String, Object> tc = new java.util.HashMap<>();
        tc.put("sessionId", sessionId);
        if (stepLabel != null && !stepLabel.isBlank()) tc.put("stepLabel", stepLabel);
        return tc;
    }

    /**
     * 引导回复（steer）包装器（flow）：在 {@link #callStepWithStreaming} 外套"被引导打断就重做本步"的循环。
     * 语义同 auto：折入"上轮思考+上轮半截+新想法"重跑本步，思考不关、工具/模式不变。不触发时零影响。
     * flow 进 step4 执行阶段不调用本方法（前端禁引导 + 后端不消费 steerIdea），只在 step1/step2 用。
     * <p><b>basePrompt 是 {@link Supplier}，每轮重新求值</b>：引导触发时先 {@link #foldSteerIntoCurrentTask} 把引导折进
     * currentTask，再调 supplier 用<b>更新后的 currentTask</b> 重建本步 prompt。否则重跑会继续用引导前固化的旧 prompt
     * （= 本步仍按原始问题执行，下游/RAG 却已换新——用户实测到的不一致）。
     */
    protected String callStepWithSteer(
            java.util.function.Function<String, ChatClient.ChatClientRequestSpec> specBuilder,
            DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName,
            java.util.function.Supplier<String> basePromptSupplier, String sessionId) {
        String prompt = basePromptSupplier.get();
        String prevPartial = null, prevReasoning = null;
        String result = null;
        int rounds = 0;
        while (true) {
            String idea = steerEnabled ? dynamicContext.drainSteerIdea() : null;
            if (idea != null && !idea.isBlank()) {
                foldSteerIntoCurrentTask(dynamicContext, idea);
                String rebuilt = basePromptSupplier.get();
                prompt = buildSteerPrompt(rebuilt, idea, prevPartial, prevReasoning);
                writeSteerMarker(sessionId, stepId, idea, prompt);
                log.info("[Steer][flow] 重做本步 step={} round={} ideaLen={}", stepId, rounds + 1, idea.length());
            }
            // 立即回答/取消若在「引导断旧流 → 起新重做流」的空窗里被点（那一发 fireCancelTrigger 打在已消费的旧触发器上成了
            // no-op），这里在起新流前拦下：不再跑这条重做流（否则它会整条跑完，让立即回答/取消看起来没反应）。引导内容此时
            // 已折进持久 steerSupplement、半截思考在 ReasoningContentFilter，上层据 finalizeRequested 跳 finalize 时会捞回；
            // 返回上一轮已写出的半截（result 非空=已跑过至少一条流），避免覆盖成空丢内容。
            if (dynamicContext.isFinalizeRequested() || dynamicContext.isCancelled()) {
                log.info("[Steer][flow] finalize/cancel 已请求，跳过重做流 step={}", stepId);
                return result != null ? result : (prevPartial != null ? prevPartial : "");
            }
            final String fp = prompt;
            result = callStepWithStreaming(specBuilder.apply(fp), dynamicContext, stepId, displayName, fp, sessionId);
            if (!steerEnabled || !dynamicContext.hasSteerIdea() || dynamicContext.isFinalizeRequested()
                    || ++rounds >= steerMaxRounds) {
                break;
            }
            prevPartial = result;
            prevReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.getLatestReasoning(sessionId);
        }
        return result;
    }

    /** 引导：把"用户新想法 + 上轮思考 + 上轮半截输出"折进本步 prompt（思考不关）。 */
    protected String buildSteerPrompt(String basePrompt, String idea, String prevPartial, String prevReasoning) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户在本步执行途中补充了新想法，请重做本步并把它纳入考虑，不要丢弃已有进展】\n");
        sb.append("用户补充：").append(idea).append("\n\n");
        if (prevReasoning != null && !prevReasoning.isBlank()) {
            String pr = prevReasoning.length() > 3000 ? prevReasoning.substring(0, 3000) + "...(截断)" : prevReasoning;
            sb.append("你刚才在本步的思考（被打断，可能半截）：\n").append(pr).append("\n\n");
        }
        if (prevPartial != null && !prevPartial.isBlank()) {
            String pp = prevPartial.length() > 2000 ? prevPartial.substring(0, 2000) + "...(截断)" : prevPartial;
            sb.append("你刚才在本步已写出的内容（被打断，可能半截）：\n").append(pp).append("\n\n");
        }
        sb.append("----- 本步原始任务 -----\n").append(basePrompt);
        return sb.toString();
    }

    /**
     * 引导串行传递：把用户引导折进 {@code currentTask}（原任务保留 + 追加引导）。flow 模式 Step2 规划
     * （{@code userRequest=getCurrentTask()}）与 Step4 执行都读 currentTask → 折入后引导完整串行传给下游所有步骤。
     * <p>只做"原始保留 + 追加"，不改写、不分类、不跨域重路由（属后续 C 阶段）。idea/currentTask 为空时安全降级，不触发引导时永不被调用 → 零影响。
     */
    protected void foldSteerIntoCurrentTask(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, String idea) {
        if (idea == null || idea.isBlank()) return;
        String prev = dynamicContext.getCurrentTask();
        String base = prev == null ? "" : prev.trim();
        String merged = base.isEmpty() ? idea.trim() : base + "\n\n【用户追加引导】" + idea.trim();
        dynamicContext.setCurrentTask(merged);
        log.info("[Steer][flow] 引导已折入 currentTask（len {} -> {}）", base.length(), merged.length());
    }

    /** 引导可观测：写一条 ai_event_log 标记行（stepName=steer_triggered）。 */
    protected void writeSteerMarker(String sessionId, String stepId, String idea, String augmentedPrompt) {
        if (eventLogServiceForSteer == null) return;
        try {
            eventLogServiceForSteer.log(cn.bugstack.ai.domain.agent.service.execute.EventLogEntry.builder()
                    .sessionId(sessionId)
                    .userId(MDC.get("userId")).tenantId(MDC.get("tenantId")).agentId(MDC.get("agentId"))
                    .billingScope(cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE)
                    .stepName("steer_triggered")
                    .inputPrompt("【引导触发】step=" + stepId + " | 用户补充=" + idea + "\n----- 重做本步的输入 -----\n" + augmentedPrompt)
                    .model("intervention").latencyMs(0L).build());
        } catch (Exception e) {
            log.debug("[Steer][flow] marker log failed: {}", e.getMessage());
        }
    }

    /** US-018：流式调用重试最大次数，默认与 Resilience4j llmCall retry.max-attempts 对齐 */
    @org.springframework.beans.factory.annotation.Value("${agent.streaming.retry-max-attempts:3}")
    protected int streamingRetryMaxAttempts;

    /** 引导回复：开关 + 单步最大重做轮次（防止反复引导死循环）。 */
    @org.springframework.beans.factory.annotation.Value("${agent.steer.enabled:true}")
    protected boolean steerEnabled;
    @org.springframework.beans.factory.annotation.Value("${agent.steer.max-rounds:3}")
    protected int steerMaxRounds;

    /** 引导可观测：写 steer_triggered 标记行。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    protected cn.bugstack.ai.domain.agent.service.execute.IEventLogService eventLogServiceForSteer;

    /** 流式调用无 token 空闲超时（秒），连续 N 秒无新 token 判定为卡死。Flow DAG 子步骤也必须兜底，否则依赖链会一直等待。 */
    @org.springframework.beans.factory.annotation.Value("${agent.streaming.idle-timeout-seconds:120}")
    protected int streamingIdleTimeoutSeconds;

    /**
     * Token-Level Streaming：逐 token 发送 SSE event:token 事件，同时拼接完整响应返回。
     * <p>
     * US-018：添加手动重试循环，弥补 stream() 绕过 LlmCallGateway 导致的容错空白。
     * 每次重试重建 Flux（Spring AI 流式 spec 可复用），全量失败后返回部分响应或 null。
     * <p>
     * Claude 改进：与非流式路径对齐——补 metrics + OutputModerationFilter + PiiMasker。
     */
    @SuppressWarnings("unchecked")
    protected String callChatClientWithTokenStreaming(
            ChatClient.ChatClientRequestSpec spec,
            DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepName, String promptText) {
        long start = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();
        final ChatResponse[] lastResponse = new ChatResponse[1];
        // 2026-05-28：前端只展示"给用户看的回答"，检测到 === 执行结果 === 块后停止向前端推 token
        //（内部 fullResponse 仍累积完整文本，供 extractStepOutputData 抠"输出数据"传下游）。
        final String RESULT_BLOCK_MARKER = "=== 执行结果 ===";
        final int[] displaySentLen = {0};      // 已推送给前端的 fullResponse 字符数
        final boolean[] resultBlockCut = {false};

        // v1.3.2 (2026-05-14)：按 sessionId 隔离 ReasoningContentFilter 缓存。
        // flow step4 DAG 并行子步骤共享同一 filter Bean，过去用全局 AtomicReference 互相覆盖 → mimo 400。
        // 优先从 dynamicContext 拿 sessionId（execute 入口塞入），dag-step 线程读 MDC 拿不到。
        String __sidFromCtx = dynamicContext.getValue("sessionId");
        String __sid = (__sidFromCtx != null && !__sidFromCtx.isBlank()) ? __sidFromCtx : MDC.get("sessionId");
        // G1-C：写回 MDC，让 Reactor 自动上下文传播在 blockLast 订阅时捕获、恢复到 boundedElastic 工具线程，
        // MeteredToolCallback 才能拿到 sessionId 触发审批。线程池 wrap 的 finally 会统一还原 MDC。
        if (__sid != null && !__sid.isBlank()) MDC.put("sessionId", __sid);
        // 立即回答/引导 mid-stream 截断触发器：answer_now/steer emit 它 → takeUntilOther 优雅完成 Flux → 拿到半截。
        // 不触发时对原流完全透明（companion 永不 emit）；Sinks.one 的 replay 语义可处理"刚 emit 就被订阅"的竞态。
        reactor.core.publisher.Sinks.One<Object> __cancelTrigger = reactor.core.publisher.Sinks.one();
        dynamicContext.setCancelTrigger(__cancelTrigger);
        // 立即回答 finalize（stepName 含 answer_now）那一发关思考：与 scopeSession 同机制（订阅在调用线程，filter 读 ThreadLocal）。
        // 其余步骤 __noThink=false，scopeNoThinking 退化为 no-op → 零影响。
        boolean __noThink = stepName != null && stepName.contains("answer_now");
        try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(__sid);
             AutoCloseable __noThinkScope = __noThink
                     ? cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeNoThinking(__sid)
                     : (AutoCloseable) () -> {}) {
        for (int attempt = 1; attempt <= streamingRetryMaxAttempts; attempt++) {
            // 每次重试前检查取消：避免 cancel 后仍等 30s × N 次超时
            if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                log.info("[Cancel] streaming retry loop aborted before attempt {}/{} due to cancellation, step={}",
                        attempt, streamingRetryMaxAttempts, stepName);
                throw new CancellationException("execution cancelled before retry");
            }
            fullResponse.setLength(0);
            lastResponse[0] = null;
            displaySentLen[0] = 0;
            resultBlockCut[0] = false;
            try {
                Flux<ChatClientResponse> flux = spec.stream().chatClientResponse();
                flux.timeout(Duration.ofSeconds(streamingIdleTimeoutSeconds))
                .map(cr -> {
                    if (cr.chatResponse() != null) lastResponse[0] = cr.chatResponse();
                    // 2026-05-07 修：getText() 在 tool_use / metadata-only 末帧会返回 null，
                    // FluxMap 不允许 mapper 返回 null，必须兜成 ""
                    String raw = cr.chatResponse() != null
                            && cr.chatResponse().getResult() != null
                            && cr.chatResponse().getResult().getOutput() != null
                            ? cr.chatResponse().getResult().getOutput().getText()
                            : null;
                    String text = raw == null ? "" : raw;
                    fullResponse.append(text);
                    return text;
                }).doOnNext(token -> {
                    // 已进入"执行结果"块 → 不再向前端推（内部 fullResponse 仍在累积）
                    if (resultBlockCut[0]) return;
                    String full = fullResponse.toString();
                    int mi = full.indexOf(RESULT_BLOCK_MARKER);
                    if (mi >= 0) {
                        // 命中块：把 marker 前内容推完（紧邻的 ``` 代码围栏也一起藏掉），之后截断
                        int cut = mi;
                        String before = full.substring(0, mi);
                        int fence = before.lastIndexOf("```");
                        if (fence >= 0 && before.substring(fence).trim().equals("```")) {
                            cut = fence;
                        }
                        if (cut > displaySentLen[0]) {
                            sendTokenEvent(dynamicContext, full.substring(displaySentLen[0], cut), stepName, MDC.get("requestId"));
                        }
                        displaySentLen[0] = full.length();
                        resultBlockCut[0] = true;
                    } else {
                        // 未命中：推"安全段"，末尾保留 (markerLen-1) 字符，防止把正在形成的 marker 提前漏给前端
                        int safeEnd = full.length() - (RESULT_BLOCK_MARKER.length() - 1);
                        if (safeEnd > displaySentLen[0]) {
                            sendTokenEvent(dynamicContext, full.substring(displaySentLen[0], safeEnd), stepName, MDC.get("requestId"));
                            displaySentLen[0] = safeEnd;
                        }
                    }
                }).doOnComplete(() -> {
                    // 没出现块 → 把保留的尾巴补推；出现块 → 尾巴是块内容，不推给前端
                    if (!resultBlockCut[0]) {
                        String full = fullResponse.toString();
                        if (full.length() > displaySentLen[0]) {
                            sendTokenEvent(dynamicContext, full.substring(displaySentLen[0]), stepName, MDC.get("requestId"));
                        }
                    }
                    sendTokenEvent(dynamicContext, "[DONE]", stepName, MDC.get("requestId"));
                }).doOnError(e -> {
                    Throwable t = e;
                    while (t != null) {
                        if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                            String body = wcre.getResponseBodyAsString();
                            if (body.length() > 2000) body = body.substring(0, 2000) + "...(truncated)";
                            log.error("[Streaming] step={} HTTP {} gateway error body: {}", stepName, wcre.getStatusCode(), body);
                            break;
                        }
                        t = t.getCause();
                    }
                })
                // 立即回答/引导：trigger 一 emit → 优雅完成 Flux（取消上游 WebClient，LLM 真停）→ blockLast 返回半截
                .takeUntilOther(__cancelTrigger.asMono())
                .blockLast();

                // mid-stream 截断时上游被取消、doOnComplete 不触发，补发 [DONE] 收尾该 step 的 token 流
                if (dynamicContext.isFinalizeRequested() || dynamicContext.hasSteerIdea()) {
                    sendTokenEvent(dynamicContext, "[DONE]", stepName, MDC.get("requestId"));
                }

                // 流式调用返回后检查取消：如果在 blockLast() 期间被取消，丢弃过期结果
                if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                    log.info("[Cancel] streaming returned after cancellation, discarding stale result, step={}", stepName);
                    throw new CancellationException("execution cancelled during streaming");
                }

                long latency = System.currentTimeMillis() - start;
                String result = fullResponse.toString();

                long promptTokens = 0L, completionTokens = 0L;
                // v1.3.1：从 "streaming" 字面量改成 "unknown"，与 recorder 内部 fallback 对齐
                String model = "unknown";
                if (lastResponse[0] != null && lastResponse[0].getMetadata() != null) {
                    if (lastResponse[0].getMetadata().getUsage() != null) {
                        Usage u = lastResponse[0].getMetadata().getUsage();
                        promptTokens = u.getPromptTokens() != null ? u.getPromptTokens() : 0L;
                        completionTokens = u.getCompletionTokens() != null ? u.getCompletionTokens() : 0L;
                    }
                    String m = lastResponse[0].getMetadata().getModel();
                    if (m != null && !m.isBlank()) model = m;
                }

                log.info("[Streaming] step={} model={} promptTokens={} completionTokens={} latency={}ms",
                        stepName, model, promptTokens, completionTokens, latency);
                // 立即回答可观测：把本步（含被截断步）的 token 累加进本轮上下文，供 finalize marker 报告叠加值
                dynamicContext.addTokens(promptTokens, completionTokens);
                llmObservationRecorder.record(buildCallContext(stepName, promptText, result, model), lastResponse[0], latency,
                        result.isEmpty() ? new IllegalStateException("empty streaming response") : null);

                result = OutputModerationFilter.check(result);
                return result;
            } catch (Exception e) {
                // 诊断：记录 gateway 返回的错误详情（400/429/500 等）
                if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                    String body = wcre.getResponseBodyAsString();
                    if (body.length() > 2000) body = body.substring(0, 2000) + "...(truncated)";
                    log.error("[Streaming] step={} HTTP {} gateway error: body={}", stepName, wcre.getStatusCode(), body);
                }
                if (isStreamingTimeout(e)) {
                    if (fullResponse.length() > 200) {
                        log.warn("[Streaming] step={} idle timeout with {} chars, returning partial result",
                                stepName, fullResponse.length());
                        sendTokenEvent(dynamicContext, "[DONE]", stepName, MDC.get("requestId"));
                        break;
                    }
                    log.warn("[Streaming] step={} idle timeout with only {} chars, retrying",
                            stepName, fullResponse.length());
                    if (attempt >= streamingRetryMaxAttempts) {
                        break;
                    }
                    sleepBeforeStreamingRetry(stepName, attempt);
                    continue;
                }
                if (attempt < streamingRetryMaxAttempts) {
                    log.warn("[Streaming] step={} attempt {}/{} failed: {}, retrying...",
                            stepName, attempt, streamingRetryMaxAttempts, e.getMessage());
                    sleepBeforeStreamingRetry(stepName, attempt);
                } else {
                    log.warn("[Streaming] step={} all {} attempts failed: {}",
                            stepName, streamingRetryMaxAttempts, e.getMessage());
                }
            }
        }

        // 保底：异常/超时路径也要收尾，让前端卡片结束，DAG future 返回后依赖步骤才能继续进入错误兜底。
        sendTokenEvent(dynamicContext, "[DONE]", stepName, MDC.get("requestId"));

        // 全部重试耗尽
        llmObservationRecorder.record(buildCallContext(stepName, promptText, fullResponse.toString(), "unknown"),
                null, System.currentTimeMillis() - start, new IllegalStateException("streaming failed"));
        String partial = fullResponse.length() > 0 ? fullResponse.toString() : null;
        if (partial == null) return null;
        return OutputModerationFilter.check(partial);
        } catch (Exception scopeEx) {
            throw new RuntimeException("streaming wrapper failed: " + scopeEx.getMessage(), scopeEx);
        }
    }

    /**
     * 判断异常是否由 Flux idle timeout 触发（cause chain 中含 TimeoutException）。
     */
    private void sleepBeforeStreamingRetry(String stepName, int failedAttempt) {
        long baseDelayMs = 2_000L << Math.max(0, failedAttempt - 1);
        long jitterMs = ThreadLocalRandom.current().nextLong(-500L, 501L);
        long delayMs = Math.max(0L, baseDelayMs + jitterMs);
        log.info("[Streaming] step={} retry backoff after attempt {}: {}ms", stepName, failedAttempt, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("streaming retry backoff interrupted");
        }
    }

    private boolean isStreamingTimeout(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof TimeoutException) return true;
            t = t.getCause();
        }
        return false;
    }


}
