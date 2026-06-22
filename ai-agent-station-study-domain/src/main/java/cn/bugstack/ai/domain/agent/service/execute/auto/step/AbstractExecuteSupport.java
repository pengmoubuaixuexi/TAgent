package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.memory.working.IWorkingMemoryService;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
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
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:48
 */
public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> {

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

    /** P0-B2b-O1：Auto 状态机 shadow 指标（含 finish reason）；可选注入，未装配时跳过打点 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    protected cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics autoAgentMetrics;

    @Resource
    protected AgentToolRegistry dynamicAgentToolRegistry;

    @Resource
    protected McpToolCatalogService dynamicMcpToolCatalogService;

    /** ask_user 人工补充 gate；用于判断「非执行步元工具豁免」提示是否提及 ask_user（功能关则不提，免幻觉）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    protected cn.bugstack.ai.domain.agent.service.security.UserInputGate userInputGate;

    /** request_tool 开关；与 {@link #dynamicMcpToolCatalogService} 同时具备才在豁免提示里提及 request_tool。 */
    @org.springframework.beans.factory.annotation.Value("${agent.request-tool.enabled:false}")
    protected boolean requestToolEnabled;

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

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    protected List<ToolCallback> resolveAgentDynamicToolCallbacks(ExecuteCommandEntity requestParameter, String clientId) {
        if (requestParameter == null || clientId == null || dynamicMcpToolCatalogService == null) {
            return List.of();
        }
        return dynamicMcpToolCatalogService.resolveDynamicToolCallbacks(requestParameter.getRunId(), requestParameter.getSessionId(), clientId,
                dynamicMcpToolCatalogService.needsFor(requestParameter.getSessionId()), requestParameter.getMessage(),
                dynamicAgentToolRegistry != null ? dynamicAgentToolRegistry.getTools(clientId) : List.of());
    }

    /**
     * 非执行步「除元工具外不执行」豁免提示：仅在对应功能<b>实际开启</b>时才提及该元工具，
     * 避免功能在配置里被关闭时仍提示模型可用 → 诱发对未广播工具的幻觉调用。两者都关返回空串（零注入）。
     * <p>用法：拼到分析等非执行步 prompt 末尾即可，与 {@code RobustToolCallingManager.resolveToolDefinitions}
     * 实际广播 ask_user/request_tool 的条件保持同源（gate.enabled / requestToolEnabled）。
     */
    protected String metaToolPromptHint(String sessionId) {
        return metaToolPromptHint(cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile.ALL, sessionId);
    }

    /** P1-A1：提示层与结构门读取同一 profile，禁止宣传本阶段未授权的元工具。 */
    protected String metaToolPromptHint(
            cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile, String sessionId) {
        // ask_user 的提示条件必须与实际广播条件一致：广播除 isEnabled() 外还看本会话剩余额度 remainingFor>0
        //（见 RobustToolCallingManager.askUserAvailable），否则额度用尽后 prompt 仍说"可用"但请求里不广播该工具 → 幻觉调用。
        boolean askOn = profile != null
                && profile.allows(cn.bugstack.ai.domain.agent.service.execute.common.ToolCapability.ASK_USER)
                && userInputGate != null && userInputGate.isEnabled()
                && userInputGate.remainingFor(sessionId) > 0;
        boolean reqOn = profile != null
                && profile.allows(cn.bugstack.ai.domain.agent.service.execute.common.ToolCapability.REQUEST_TOOL)
                && requestToolEnabled && dynamicMcpToolCatalogService != null;
        if (!askOn && !reqOn) return "";
        return cn.bugstack.ai.domain.agent.service.prompt.RuntimeToolPromptComposer
                .renderMetaToolHint(askOn, reqOn);
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
     * 回退到原始 message。必须在 specBuilder lambda 里<b>每轮实时调用</b>，引导重跑时 RAG 才用新任务检索。
     */
    protected String steerAwareRetrievalQuery(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx, ExecuteCommandEntity req) {
        // 用"有效用户问题"(原始 message + 引导补充)做检索，而非 currentTask——currentTask 会被 Step3 覆写成"重新执行"指令，是糟糕的检索词
        String q = effectiveUserQuestion(req, ctx);
        return (q == null || q.isBlank()) ? buildLtmRetrievalQuery(req, null) : q;
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

    /**
     * P0.3 Structured Output 帮手：让 LLM 返回严格 JSON 并直接反序列化为 Pojo。
     * <p>
     * 适用场景：替代 {@code result.contains("任务状态: COMPLETED")} 这种字符串硬匹配。
     * <ul>
     *   <li>失败 fallback：捕获解析异常返回 null，让调用方走兼容分支（仍可调老 prompt 走字符串解析）</li>
     *   <li>同样经 Spring AI 的 {@code .entity(Class)} API；Spring AI 内部会拼 JSON Schema 提示</li>
     * </ul>
     * <p>
     * <b>Prompt 适配</b>：使用前确认 DB 里对应 system prompt 已经引导模型返回 JSON
     * （Spring AI 的 entity() 默认会 append "Format response as JSON" 类的指令，但不保证 100%）。
     */
    protected <T> T callChatClientStructured(java.util.function.Supplier<org.springframework.ai.chat.client.ChatClient.CallResponseSpec> specSupplier,
                                             Class<T> entityType, String stepName) {
        long start = System.currentTimeMillis();
        T entity = null;
        try {
            entity = specSupplier.get().entity(entityType);
        } catch (Exception e) {
            log.warn("structured-output parse failed for step={} entity={}: {}",
                    stepName, entityType.getSimpleName(), e.getMessage());
        }
        long latency = System.currentTimeMillis() - start;
        // 结构化路径暂不抓 token（Spring AI 的 entity() 没暴露 ChatResponse），
        // 等 P0.3 全量切换时考虑改造为 stream + 末尾收割
        llmObservationRecorder.record(LlmCallContext.builder()
                .stepName(stepName)
                .model("structured")
                .resultText(entity != null ? entity.toString() : null)
                .sessionId(MDC.get("sessionId"))
                .userId(MDC.get("userId"))
                .tenantId(MDC.get("tenantId"))
                .agentId(MDC.get("agentId"))
                .build(), null, latency, entity == null ? new IllegalStateException("structured output empty") : null);
        return entity;
    }


    /**
     * 执行 LLM 调用并打印结构化指标（stepName / tokens / latencyMs）。
     * <p>
     * 为什么不让调用方自己 log：每个 Step 都在同一层调 `call().content()`，指标采集必须集中，
     * 否则结构化字段名/格式会各写各的，Kibana 上无法做聚合分析。
     * <p>
     * 实现手法：把 token/latency 写入 MDC 后触发 log.info，logback 的 LogstashEncoder
     * 会把 MDC 顺势序列化为顶级 JSON 字段；try/finally 立刻清理，避免污染后续日志。
     */
    protected String callChatClientWithLogging(Supplier<ChatClient.CallResponseSpec> specSupplier, String stepName, String promptText) {
        long start = System.currentTimeMillis();
        // 走 Gateway：@Retry + @CircuitBreaker 在这一跳生效；全部失败后返回 null，走下方原有的空值分支
        // 传 Supplier 是因为 Spring AI 的 CallResponseSpec 非幂等：每次重试必须 specSupplier.get() 造一个全新的
        ChatResponse response = llmCallGateway.call(specSupplier);
        long latency = System.currentTimeMillis() - start;

        // v1.3.1 (2026-05-14)：token/cost/cached 提取 + Prometheus/event_log/MDC→ES 写入统一由 LlmObservationRecorder 处理。
        // 这里只保留 model 用于 buildCallContext 的 ctx.model fallback；step 也 put 一次让同 step 内后续应用日志带 step 字段。
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
        String resultText = response.getResult().getOutput().getText();
        llmObservationRecorder.record(buildCallContext(stepName, promptText, resultText, model), response, latency, null);
        // P2.5 14.3 输出审核（PII 脱敏已移至最终输出层，避免中间步骤级联脱敏）
        resultText = OutputFilter.cleanForUser(OutputModerationFilter.check(resultText));
        return resultText;
    }

    /**
     * 执行需要保留机器字段原文的内部 LLM 调用，同时沿用统一 Gateway 与观测账本。
     * <p>
     * 与 {@link #callChatClientWithLogging(Supplier, String, String)} 唯一的返回语义差异是：本方法不经过
     * {@link OutputModerationFilter}/{@link OutputFilter}，以免 HTML comment 等机器契约被用户输出过滤器删除。
     * Retry/CircuitBreaker、token/cost/latency/outcome、event_log 与 failure→null 语义保持一致。
     * </p>
     * <p><b>安全边界：</b>返回值是未经 moderation/filter 的 raw assistant text，只允许立即解析内部机器字段后丢弃；
     * 禁止复用于任何 user-facing、SSE、Working Memory、历史或最终交付路径。</p>
     */
    protected String callChatClientRawWithLogging(Supplier<ChatClient.CallResponseSpec> specSupplier,
                                                  String stepName, String promptText) {
        long start = System.currentTimeMillis();
        ChatResponse response = llmCallGateway.call(specSupplier);
        long latency = System.currentTimeMillis() - start;

        String model = response != null && response.getMetadata() != null && response.getMetadata().getModel() != null
                ? response.getMetadata().getModel() : "";
        MDC.put("step", stepName);

        boolean success = response != null && response.getResult() != null && response.getResult().getOutput() != null;
        if (!success) {
            llmObservationRecorder.record(buildCallContext(stepName, promptText, null, model), response, latency,
                    new IllegalStateException("empty non-streaming raw response"));
            return null;
        }
        String rawText = response.getResult().getOutput().getText();
        llmObservationRecorder.record(buildCallContext(stepName, promptText, rawText, model), response, latency, null);
        return rawText;
    }

    // v1.3.1：原 extractCachedPromptTokens(Usage) 已迁移到 LlmObservationRecorder，本类不再持有反射逻辑。

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    /**
     * P2.2.4 Step 取消支持：在 step 入口或 LLM 调用前检查客户端是否已断开。
     * SSE 关闭时 emitter.onCompletion 回调会把 dynamicContext.cancelled 置 true。
     * 抛 CancellationException 中断 step 链，由 dispatch 层 catch 并静默结束。
     */
    protected void checkCancelled(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
            log.info("[Cancel] 客户端已断开或执行被取消，跳过后续 step 执行");
            throw new CancellationException("SSE client disconnected or execution cancelled");
        }
    }

    /**
     * 立即回答路由钩子：finalizeRequested 置位时直接跳汇总节点 Step4，返回其结果短路剩余步骤。
     * <p>各非汇总节点在 doApply 入口调用：{@code String r = checkFinalizeRoute(req, ctx); if (r != null) return r;}
     * 未置位返 null → 原流程逐字节不变（零影响保证，见设计文档 §7）。
     */
    protected String checkFinalizeRoute(ExecuteCommandEntity req,
                                        DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) throws Exception {
        if (!ctx.isFinalizeRequested()) return null;
        log.info("[AnswerNow] finalize requested → 直接跳 Step4 汇总（基于半成品尽力作答）");
        cn.bugstack.wrench.design.framework.tree.StrategyHandler<ExecuteCommandEntity,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> step4 = getBean("step4LogExecutionSummaryNode");
        return step4.apply(req, ctx);
    }

    /**
     * 2026-05-20：把当前 step 末尾的 DynamicContext 演化写一行到独立 logger {@code step.transition}，→ ES。
     * <p>
     * 调用方约定：每个 Step 节点在 {@code return router(req, ctx)} 之前调一次，
     * 让 Kibana 按 traceId 排序就能看到 step1→2→3→4 之间 dataObjects 怎么接力。
     */
    protected void recordTransition(String fromStep, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {
        if (ctx == null) return;
        cn.bugstack.ai.domain.agent.service.execute.common.StepTransitionRecorder.record(
                fromStep, ctx.getDataObjects(), ctx.getExecutionHistory(), ctx.getStep(), ctx.isCompleted());
    }

    /**
     * P1.2.3 多租户隔离：构建复合 conversationId = {tenantId}:{userId}:{sessionId}。
     * <p>
     * 用于 ChatMemory advisor context，让不同租户/用户的对话天然隔离。
     * userId/tenantId 缺失时回退到纯 sessionId（向后兼容未传 header 的旧客户端）。
     */
    /**
     * V035 (2026-05-14)：构造事件日志 entry，自动从 MDC 提取 userId / tenantId / agentId / sessionId。
     * MdcTraceFilter 在请求入口写入这些字段；同步链路里能直接读到。
     * 异步链路（boundedElastic / DAG 线程池）若没有 MDC 传播，这些字段为 null，DB 列允许 null。
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
     * <p>
     * 设计上不替换 dynamicContext，主链路仍走 JVM Map（低延迟、单请求生命周期）；
     * WM 仅作"产物轨迹"，未来 v2 可在 step 入口 read-back 实现断点续传。
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
    protected void sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                AutoAgentExecuteResultEntity result) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                // 过滤 <think> 思考块
                if (result.getContent() != null) {
                    result.setContent(OutputFilter.cleanForUser(result.getContent()));
                }
                // 发送SSE格式的数据
                String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
                emitter.send(sseData);
            }
        } catch (IOException e) {
            log.error("发送SSE结果失败：{}", e.getMessage(), e);
        }
    }

    /** P2.7 16.2 Thinking Visualization 开关；关掉可省 SSE 流量与前端渲染开销 */
    @org.springframework.beans.factory.annotation.Value("${agent.thinking-vis.enabled:true}")
    protected boolean thinkingVisEnabled;

    /**
     * P2.7 16.2 Thinking Visualization：发送中间思考过程（event: thinking）。
     * 前端可监听该事件类型展示 Agent 内部推理流程。
     */
    protected void sendThinkingEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                     String title, String content, String sessionId) {
        if (!thinkingVisEnabled) return;
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                java.util.LinkedHashMap<String, Object> thinking = new java.util.LinkedHashMap<>();
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
    protected void sendTokenEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                   String token, String stepId, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("token", token);
                payload.put("stepId", stepId);
                payload.put("sessionId", sessionId);
                payload.put("timestamp", System.currentTimeMillis());
                emitter.send("event: token\ndata: " + JSON.toJSONString(payload) + "\n\n");
            }
        } catch (IOException e) {
            log.debug("发送 token SSE 失败：{}", e.getMessage());
        }
    }

    /**
     * 2026-05-07 流式 UX：标记一个 step 开始（前端展开新卡片，开始接 token 流）
     */
    protected void sendStepStart(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                 String stepId, String displayName, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter == null) return;
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("stepId", stepId);
            payload.put("displayName", displayName);
            payload.put("sessionId", sessionId);
            payload.put("timestamp", System.currentTimeMillis());
            emitter.send("event: step_start\ndata: " + JSON.toJSONString(payload) + "\n\n");
        } catch (IOException e) {
            log.debug("发送 step_start 失败：{}", e.getMessage());
        }
    }

    /**
     * 2026-05-07 流式 UX：标记一个 step 结束（前端把当前卡片折叠为 summary 一行）
     */
    protected void sendStepEnd(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                               String stepId, String summary, String sessionId) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter == null) return;
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("stepId", stepId);
            payload.put("summary", summary);
            payload.put("sessionId", sessionId);
            payload.put("timestamp", System.currentTimeMillis());
            emitter.send("event: step_end\ndata: " + JSON.toJSONString(payload) + "\n\n");
        } catch (IOException e) {
            log.debug("发送 step_end 失败：{}", e.getMessage());
        }
    }

    /**
     * 2026-05-07 流式 UX 包装器：step_start → 流式调用 LLM → step_end。
     * <p>
     * 每个 Step 节点把原本的 callChatClientWithLogging(...) 替换成本方法即可获得：
     * <ul>
     *   <li>前端按 step 折叠的可视化卡片</li>
     *   <li>逐 token 流式输出（tokenStreamingEnabled=true 时）</li>
     *   <li>fallback 到非流式（tokenStreamingEnabled=false 时）</li>
     * </ul>
     * @param spec        Spring AI 已构造好的 spec（不要先 .call() 或 .stream()）
     * @param stepId      唯一 step 标识（推荐 stepName，如 "step1_analyzer"）
     * @param displayName 折叠后展示的中文名（如 "需求分析"）
     */
    protected String callStepWithStreaming(
            ChatClient.ChatClientRequestSpec spec,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName, String promptText, String sessionId) {
        return callStepWithStreaming(spec, dynamicContext, stepId, displayName, null, promptText, sessionId);
    }

    protected String callStepWithStreaming(
            ChatClient.ChatClientRequestSpec spec,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName,
            cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile,
            String promptText, String sessionId) {
        sendStepStart(dynamicContext, stepId, displayName, sessionId);
        // G1-C 修复：把 sessionId 塞进 ToolContext，让它跟着调用流到工具执行线程。
        // 流式（.stream()）的工具调用跑在 Reactor 线程上、MDC（ThreadLocal）为空，MeteredToolCallback 优先从
        // ToolContext 读 sessionId 才能查到 SSE 通道弹出人工审批 / 进度事件（详见 MeteredToolCallback.resolveSessionId）。
        // 用 final 局部变量而非重赋值参数：下方 spec::call 方法引用要求被捕获变量 effectively final。
        // stepLabel(displayName) 让 DAG 并行审批时前端能标注是哪个步骤要的许可。
        // 注：动态补挂的工具由各 step 节点通过 spec.options(OpenAiChatOptions.toolCallbacks(...)) 注入，这里不重复注入。
        log.info("[ToolCtxDiag][auto] step={} sessionId={} inject={}", stepId, sessionId, (sessionId != null && !sessionId.isBlank()));
        String runId = dynamicContext != null ? dynamicContext.getValue("runId") : null;
        java.util.Map<String, Object> toolContext = buildToolContext(sessionId, displayName, profile, runId);
        final ChatClient.ChatClientRequestSpec callSpec = toolContext.isEmpty() ? spec : spec.toolContext(toolContext);
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
        return buildToolContext(sessionId, stepLabel, null);
    }

    protected static java.util.Map<String, Object> buildToolContext(
            String sessionId, String stepLabel,
            cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile) {
        return buildToolContext(sessionId, stepLabel, profile, null);
    }

    protected static java.util.Map<String, Object> buildToolContext(
            String sessionId, String stepLabel,
            cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile,
            String runId) {
        java.util.Map<String, Object> tc = new java.util.HashMap<>();
        if (sessionId != null && !sessionId.isBlank()) tc.put("sessionId", sessionId);
        if (runId != null && !runId.isBlank()) tc.put("agent.run_id", runId);
        if (stepLabel != null && !stepLabel.isBlank()) tc.put("stepLabel", stepLabel);
        cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities.put(tc, profile);
        return tc;
    }

    /**
     * 引导回复（steer）包装器：在 {@link #callStepWithStreaming} 外套一层"被引导打断就重做本步"的循环。
     * <p>语义：用户在本步流式中途点【引导】→ steerExecute 置 steerIdea + fire 断流 → 本方法把"上轮思考+上轮半截输出+
     * 新想法"折进 prompt，**用同一 client/advisor/工具、思考不关**重跑本步；执行模式/后续流程不变。
     * <p>不触发引导时与直接 {@link #callStepWithStreaming} 行为一致（drain 为 null，循环只跑一轮）→ 零影响。
     * 立即回答优先：若循环中检测到 finalizeRequested，立即跳出交由上层路由到 finalize。
     *
     * @param specBuilder 以"最终 prompt 文本"为入参构造 spec（每轮重建，因为 prompt 会被引导改写）
     * @param basePromptSupplier 本步 prompt 的构造器，<b>每轮重新求值</b>：引导触发时先 {@link #foldSteerIntoCurrentTask}
     *        把引导折进 currentTask，再调 supplier 用<b>更新后的 currentTask</b> 重建本步 prompt（否则重跑仍用引导前固化的旧 prompt）。
     */
    protected String callStepWithSteer(
            java.util.function.Function<String, ChatClient.ChatClientRequestSpec> specBuilder,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName,
            java.util.function.Supplier<String> basePromptSupplier, String sessionId) {
        return callStepWithSteer(specBuilder, dynamicContext, stepId, displayName, null, basePromptSupplier, sessionId);
    }

    protected String callStepWithSteer(
            java.util.function.Function<String, ChatClient.ChatClientRequestSpec> specBuilder,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepId, String displayName,
            cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile,
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
                log.info("[Steer] 重做本步 step={} round={} ideaLen={}", stepId, rounds + 1, idea.length());
            }
            // 立即回答/取消若在「引导断旧流 → 起新重做流」的空窗里被点（那一发 fireCancelTrigger 打在已消费的旧触发器上成了
            // no-op），这里在起新流前拦下：不再跑这条重做流（否则它会整条跑完，让立即回答/取消看起来没反应）。引导内容此时
            // 已折进持久 steerSupplement、半截思考在 ReasoningContentFilter，上层据 finalizeRequested 跳 Step4 finalize 时会捞回；
            // 返回上一轮已写出的半截（result 非空=已跑过至少一条流），避免覆盖成空丢内容。
            if (dynamicContext.isFinalizeRequested() || dynamicContext.isCancelled()) {
                log.info("[Steer] finalize/cancel 已请求，跳过重做流 step={}", stepId);
                return result != null ? result : (prevPartial != null ? prevPartial : "");
            }
            final String fp = prompt;
            result = callStepWithStreaming(specBuilder.apply(fp), dynamicContext, stepId, displayName, profile, fp, sessionId);
            if (!steerEnabled || !dynamicContext.hasSteerIdea() || dynamicContext.isFinalizeRequested()
                    || ++rounds >= steerMaxRounds) {
                break;
            }
            // 本轮被引导打断：记下半截，循环重跑
            prevPartial = result;
            prevReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.getLatestReasoning(sessionId);
        }
        return result;
    }

    /** 引导：把"用户新想法 + 上轮思考 + 上轮半截输出"折进本步 prompt（思考不关，故保留思考引导）。 */
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
     * 引导串行传递：把用户引导折进 {@code currentTask}（原任务保留 + 追加引导），让所有读 {@code getCurrentTask()}
     * 的下游 step 拿到引导（auto：下一轮 Step1 分析 / Step4 主题；flow 那份则直达规划+执行）。
     * <p>当轮 Step2 在 auto 模式不读 currentTask（用 message+analysisResult），故引导对当轮 Step2 经"Step1 重跑结果"间接生效。
     * <p>只做"原始保留 + 追加"，不改写、不分类、不跨域重路由（属后续 C 阶段）。idea/currentTask 为空时安全降级，不触发引导时本方法永不被调用 → 零影响。
     */
    protected void foldSteerIntoCurrentTask(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String idea) {
        if (idea == null || idea.isBlank()) return;
        // auto 真正的载体：steerSupplement（持久、Step3 不覆写），由 Step1/Step2 追加进"用户问题"
        dynamicContext.appendSteerSupplement(idea);
        // currentTask 也折一份：auto 里它被 Step3 覆写、step1 模板又不引用，仅影响 Step4 记忆主题；保留无害
        String prev = dynamicContext.getCurrentTask();
        String base = prev == null ? "" : prev.trim();
        String merged = base.isEmpty() ? idea.trim() : base + "\n\n【用户追加引导】" + idea.trim();
        dynamicContext.setCurrentTask(merged);
        log.info("[Steer] 引导已折入 steerSupplement + currentTask（len {} -> {}）", base.length(), merged.length());
    }

    /**
     * auto 的"有效用户问题" = 不可变原始 message + 持久引导补充（steerSupplement）。
     * Step1/Step2 用它替代裸 getMessage() 作为"用户问题"，让引导持久贯穿且不破坏 reflexion（原问题保留）。
     * 无引导时 == 原始 message，零影响。
     */
    protected static String effectiveUserQuestion(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {
        String base = (req == null || req.getMessage() == null) ? "" : req.getMessage().trim();
        String supp = ctx == null ? null : ctx.getSteerSupplement();
        if (supp == null || supp.isBlank()) return base;
        return base + "\n\n【用户后续补充/纠正（以此为准，与上面原始问题冲突时优先）】\n" + supp.trim();
    }

    /** 引导可观测：写一条 ai_event_log 标记行（stepName=steer_triggered，记录想法 + 重做本步的输入）。 */
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
            log.debug("[Steer] marker log failed: {}", e.getMessage());
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

    /** 流式调用无 token 空闲超时（秒），连续 N 秒无新 token 判定为卡死 */
    @org.springframework.beans.factory.annotation.Value("${agent.streaming.idle-timeout-seconds:120}")
    protected int streamingIdleTimeoutSeconds;

    /**
     * Token-Level Streaming：用 Spring AI stream() 返回 Flux<ChatClientResponse>，
     * 逐 token 发送 SSE event:token 事件，同时拼接完整响应返回。
     * <p>
     * US-018：添加手动重试循环，弥补 stream() 绕过 LlmCallGateway 导致的容错空白。
     * 每次重试重建 Flux（Spring AI 流式 spec 可复用），全量失败后返回部分响应或 null。
     * <p>
     * Claude 改进：流式路径补回非流式同款的指标 + 安全过滤：
     * <ul>
     *   <li>从 Flux 末帧 ChatResponse.metadata.usage 抓 token 用量（Spring AI 流式末帧带聚合 usage）</li>
     *   <li>llmMetrics.record() 走通，Prometheus / Cost Dashboard 不再漏数</li>
     *   <li>OutputModerationFilter + PiiMasker 在 return 前生效（流式聚合后再过一遍，避免敏感数据吐给前端）</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    protected String callChatClientWithTokenStreaming(
            ChatClient.ChatClientRequestSpec spec,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String stepName, String promptText) {
        long start = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();
        final ChatResponse[] lastResponse = new ChatResponse[1];

        // v1.3.2 (2026-05-14)：用 ReasoningContentFilter.scopeSession 把整段 streaming（含 retry）包起来。
        // filter 入口在调用方同步线程上（blockLast 触发 subscribe）读 ThreadLocal，按 sessionId 隔离 reasoning_content 缓存，
        // 避免 DAG 并行子步骤共用全局 AtomicReference 互相踩。
        // 优先从 dynamicContext 拿 sessionId（execute 入口塞入），dag-step 线程读 MDC 拿不到。
        String __sidFromCtx = dynamicContext.getValue("sessionId");
        String __sid = (__sidFromCtx != null && !__sidFromCtx.isBlank()) ? __sidFromCtx : MDC.get("sessionId");
        // G1-C：把 sessionId 写回 MDC，让 Reactor 自动上下文传播（ReactorContextPropagationConfig）在 blockLast 订阅时
        // 捕获它，再恢复到 boundedElastic 工具执行线程上 —— MeteredToolCallback 才能 MDC.get("sessionId") 拿到、触发审批。
        // 线程池 wrap 的 finally 会在任务结束统一还原 MDC，不会泄漏到下个任务。
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
            fullResponse.setLength(0);
            lastResponse[0] = null;
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
                    if (!token.isEmpty()) {
                        sendTokenEvent(dynamicContext, token, stepName, MDC.get("requestId"));
                    }
                }).doOnComplete(() -> {
                    sendTokenEvent(dynamicContext, "[DONE]", stepName, MDC.get("requestId"));
                }).doOnError(e -> {
                    // 在 Flux 内部捕获 WebClientResponseException，记录响应体（MessageAggregator 会吞掉原始异常）
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
                // 末帧没有 metadata.model 时统一 unknown，避免 Grafana model 维度多一条 "streaming" 系列污染聚合
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

                // P0-B2b-O1 shadow：记录末帧 finish reason（区分 length=截断 vs stop=正常收尾），归一低基数；只打点
                if (autoAgentMetrics != null) {
                    String __fr = "unknown";
                    try {
                        if (lastResponse[0] != null && lastResponse[0].getResult() != null
                                && lastResponse[0].getResult().getMetadata() != null) {
                            __fr = cn.bugstack.ai.domain.agent.service.execute.common.FinishReasonNormalizer.normalize(
                                    lastResponse[0].getResult().getMetadata().getFinishReason());
                        }
                    } catch (Exception __ignore) {
                    }
                    autoAgentMetrics.recordFinishReason(stepName, __fr);
                }

                log.info("[Streaming] step={} model={} promptTokens={} completionTokens={} latency={}ms",
                        stepName, model, promptTokens, completionTokens, latency);

                // 立即回答可观测：把本步（含被截断步）的 token 累加进本轮上下文，供 finalize marker 报告叠加值
                dynamicContext.addTokens(promptTokens, completionTokens);

                llmObservationRecorder.record(buildCallContext(stepName, promptText, result, model), lastResponse[0], latency,
                        result.isEmpty() ? new IllegalStateException("empty streaming response") : null);

                // 2026-05-08：LLM 调完工具就结束 stream 不输出文本（mimo-v2.5-pro 等小模型常见行为）→
                // result 为空 → Step2 拿到 "执行当前任务步骤" 兜底 prompt 失指引。
                // 零成本修复：从 lastResponse 提取 ToolCalls 列表转成简短文本作 stepResult，
                // tool_result 本身已被 Spring AI 写入 ChatMemory，Step2 通过 advisor 注入仍可见。
                if ((result == null || result.isEmpty()) && lastResponse[0] != null
                        && lastResponse[0].getResult() != null
                        && lastResponse[0].getResult().getOutput() != null
                        && lastResponse[0].getResult().getOutput().hasToolCalls()) {
                    StringBuilder sb = new StringBuilder("[已自动调用以下工具收集信息，详见对话历史]\n");
                    for (var tc : lastResponse[0].getResult().getOutput().getToolCalls()) {
                        String args = tc.arguments();
                        if (args != null && args.length() > 200) args = args.substring(0, 200) + "...";
                        sb.append("- ").append(tc.name()).append(": ").append(args).append("\n");
                    }
                    result = sb.toString();
                    log.info("[Streaming] step={} text empty but {} tool calls captured → fallback summary",
                            stepName, lastResponse[0].getResult().getOutput().getToolCalls().size());
                }

                result = OutputFilter.cleanForUser(OutputModerationFilter.check(result));
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

        // 保底：确保 [DONE] 已发送
        sendTokenEvent(dynamicContext, "[DONE]", stepName, MDC.get("requestId"));

        // 全部重试耗尽
        llmObservationRecorder.record(buildCallContext(stepName, promptText, fullResponse.toString(), "unknown"),
                null, System.currentTimeMillis() - start, new IllegalStateException("streaming failed"));
        String partial = fullResponse.length() > 0 ? fullResponse.toString() : null;
        if (partial == null) return null;
        return OutputFilter.cleanForUser(OutputModerationFilter.check(partial));
        } catch (Exception scopeEx) {
            // ReasoningContentFilter.scopeSession 返回的 AutoCloseable.close() 不会抛异常（实现是 ThreadLocal.remove）
            // 但 try-with-resources 语法要求 catch Exception；这里转成 RuntimeException 让上层正常处理流式失败
            throw new RuntimeException("streaming wrapper failed: " + scopeEx.getMessage(), scopeEx);
        }
    }

    /**
     * 判断异常是否由 Flux idle timeout 触发（cause chain 中含 TimeoutException）
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
