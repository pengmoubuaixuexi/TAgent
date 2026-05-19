package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.memory.working.IWorkingMemoryService;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import cn.bugstack.ai.domain.agent.service.router.ModelTierRegistry;
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
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * @author TAgent
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

    /** P0.1.3：按 tier 反查 clientId 用 */
    @Resource
    protected ModelTierRegistry modelTierRegistry;

    /** P1.2.2：Working Memory 旁路镜像；默认 NoopWorkingMemoryService，开关打开后切 Redis 实现 */
    @Resource
    protected IWorkingMemoryService workingMemory;

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

    protected String buildLtmRetrievalQuery(ExecuteCommandEntity req, String stage) {
        if (req == null) return "";
        String message = req.getMessage();
        if (message == null) message = "";
        return stage == null || stage.isBlank()
                ? message
                : message + "\n当前阶段: " + stage;
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
     * P0.1.3 Model Router：按 tier 选 ChatClient，命中失败则回退到 fallbackClientId。
     * <p>
     * 适用场景：
     * <ul>
     *   <li>step1 分类/解析这种轻量任务 → SMALL（haiku/mini）</li>
     *   <li>step3 评审这种中等推理 → MEDIUM（sonnet）</li>
     *   <li>step2 精确执行这种深度推理 → LARGE（opus/4-turbo）</li>
     * </ul>
     * 注册表为空（TAgent扳手未跑或没贴 tier）时直接走 fallback，安全无破坏性。
     */
    protected ChatClient getChatClientByTier(ModelTierEnumVO preferredTier, String fallbackClientId) {
        String picked = modelTierRegistry.pickClientIdByTier(preferredTier);
        if (picked != null) {
            try {
                return getChatClientByClientId(picked);
            } catch (Exception e) {
                log.warn("model-router tier={} picked clientId={} but bean missing, fallback to {}",
                        preferredTier, picked, fallbackClientId);
            }
        }
        return getChatClientByClientId(fallbackClientId);
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
        sendStepStart(dynamicContext, stepId, displayName, sessionId);
        String result;
        try {
            if (tokenStreamingEnabled) {
                result = callChatClientWithTokenStreaming(spec, dynamicContext, stepId, promptText);
            } else {
                result = callChatClientWithLogging(spec::call, stepId, promptText);
            }
        } finally {
            sendStepEnd(dynamicContext, stepId, displayName + " 已完成", sessionId);
        }
        return result;
    }

    /** US-018：流式调用重试最大次数，默认与 Resilience4j llmCall retry.max-attempts 对齐 */
    @org.springframework.beans.factory.annotation.Value("${agent.streaming.retry-max-attempts:3}")
    protected int streamingRetryMaxAttempts;

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
        try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(__sid)) {
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
                }).blockLast();

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

                log.info("[Streaming] step={} model={} promptTokens={} completionTokens={} latency={}ms",
                        stepName, model, promptTokens, completionTokens, latency);

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
                    continue;
                }
                if (attempt < streamingRetryMaxAttempts) {
                    log.warn("[Streaming] step={} attempt {}/{} failed: {}, retrying...",
                            stepName, attempt, streamingRetryMaxAttempts, e.getMessage());
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
    private boolean isStreamingTimeout(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof TimeoutException) return true;
            t = t.getCause();
        }
        return false;
    }

}
