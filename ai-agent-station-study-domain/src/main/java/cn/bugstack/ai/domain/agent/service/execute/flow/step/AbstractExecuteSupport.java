package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.working.IWorkingMemoryService;
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
import reactor.core.publisher.Flux;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 抽象类
 *
 * @author TAgent
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

    protected String buildLtmRetrievalQuery(ExecuteCommandEntity req, String stage) {
        if (req == null) return "";
        String message = req.getMessage();
        if (message == null) message = "";
        return stage == null || stage.isBlank()
                ? message
                : message + "\n当前阶段: " + stage;
    }

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    /**
     * P0.1.3 Model Router：按 tier 选 ChatClient，命中失败回退到 fallbackClientId。
     * 与 auto 侧 {@code AbstractExecuteSupport.getChatClientByTier} 镜像。
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
                emitter.send(sseData);
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
                emitter.send("event: token\ndata: " + JSON.toJSONString(payload) + "\n\n");
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
            emitter.send("event: step_start\ndata: " + JSON.toJSONString(payload) + "\n\n");
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
            emitter.send("event: step_end\ndata: " + JSON.toJSONString(payload) + "\n\n");
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

        // v1.3.2 (2026-05-14)：按 sessionId 隔离 ReasoningContentFilter 缓存。
        // flow step4 DAG 并行子步骤共享同一 filter Bean，过去用全局 AtomicReference 互相覆盖 → mimo 400。
        // 优先从 dynamicContext 拿 sessionId（execute 入口塞入），dag-step 线程读 MDC 拿不到。
        String __sidFromCtx = dynamicContext.getValue("sessionId");
        String __sid = (__sidFromCtx != null && !__sidFromCtx.isBlank()) ? __sidFromCtx : MDC.get("sessionId");
        try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(__sid)) {
        for (int attempt = 1; attempt <= streamingRetryMaxAttempts; attempt++) {
            // 每次重试前检查取消：避免 cancel 后仍等 30s × N 次超时
            if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                log.info("[Cancel] streaming retry loop aborted before attempt {}/{} due to cancellation, step={}",
                        attempt, streamingRetryMaxAttempts, stepName);
                throw new CancellationException("execution cancelled before retry");
            }
            fullResponse.setLength(0);
            lastResponse[0] = null;
            try {
                Flux<ChatClientResponse> flux = spec.stream().chatClientResponse();
                flux.map(cr -> {
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

                result = OutputModerationFilter.check(result);
                return result;
            } catch (Exception e) {
                // 诊断：记录 gateway 返回的错误详情（400/429/500 等）
                if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                    String body = wcre.getResponseBodyAsString();
                    if (body.length() > 2000) body = body.substring(0, 2000) + "...(truncated)";
                    log.error("[Streaming] step={} HTTP {} gateway error: body={}", stepName, wcre.getStatusCode(), body);
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
     * GitHub 仓库搜索软引导（与 Fixed/Auto 策略保持一致的文案）。
     * 中文宽泛 query 容易命中投毒仓库导致 raw result 暴涨；
     * 这里只是 prompt 软建议，硬护栏在 MeteredToolCallback 的 normalizeToolInput 里。
     */
    protected String githubRepositorySearchGuidance() {
        return """

                [GitHub repository search guidance]
                If you need to search GitHub repositories, prefer English technical queries and GitHub qualifiers.
                Do not pass broad Chinese tutorial/resource phrases directly as the GitHub query unless the user explicitly asks to search only Chinese repositories.
                Examples: `spring-boot learning language:Java stars:>500`, `spring-boot examples language:Java stars:>500`, `spring-boot tutorial language:Java stars:>500`.
                Use page=1 and perPage<=10 for repository recommendations.
                """;
    }

}
