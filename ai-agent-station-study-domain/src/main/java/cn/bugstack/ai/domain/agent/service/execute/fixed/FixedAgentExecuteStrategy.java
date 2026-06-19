package cn.bugstack.ai.domain.agent.service.execute.fixed;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.memory.IConversationTurnMemoryService;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 固定执行策略
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/9/13 15:14
 */
@Slf4j
@Service("fixedAgentExecuteStrategy")
public class FixedAgentExecuteStrategy implements IExecuteStrategy {

    /** per-session 取消标志，用于支持 cancelExecute() */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 立即回答：per-session finalize 标记 + 断流触发器（fixed 无 DynamicContext，用 map 承载）。 */
    private final ConcurrentHashMap<String, AtomicBoolean> finalizeFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, reactor.core.publisher.Sinks.One<Object>> cancelTriggers = new ConcurrentHashMap<>();
    /** 引导回复：per-session 新想法收件箱。 */
    private final ConcurrentHashMap<String, String> steerInbox = new ConcurrentHashMap<>();

    @Resource
    private IAgentRepository repository;

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    private AgentToolRegistry agentToolRegistry;

    @Resource
    private McpToolCatalogService mcpToolCatalogService;

    /** V035 (2026-05-14)：fix 策略统一走 Prometheus / event_log / ES */
    @Resource
    private LlmObservationRecorder llmObservationRecorder;

    /** 立即回答可观测：打断时写一条 ai_event_log 标记行。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.IEventLogService eventLogService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot longTermMemoryTurnSnapshot;

    /** P2.1 跨会话摘要记忆；为 null 时静默跳过 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService episodicMemoryService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IConversationTurnMemoryService conversationTurnMemoryService;

    /**
     * 2026-05-08：流式适配。advisor.after() 在 stream 模式下拿不到 ChatResponse output，
     * Fixed 在节点级聚合完整文本后直接调 LongTermMemoryAdvisor.triggerExtractionAsync 触发抽取。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService longTermMemoryService;

    /** 应用线程池：替代 ForkJoinPool.commonPool()，确保异步任务不被 GC 回收 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private java.util.concurrent.ThreadPoolExecutor threadPoolExecutor;

    /** 摘要触发阈值：10 轮 = 20 条消息 */
    private static final int EPISODIC_SUMMARY_THRESHOLD = 20;
    /** 节流间隔：每新增 4 条消息（= 2 轮）触发一次 */
    private static final int EPISODIC_THROTTLE_INTERVAL = 4;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_retrieve_size";

    /** 2026-05-07：流式输出开关；默认 true，关闭即回退老的 .call() 阻塞行为 */
    @Value("${agent.token-streaming.enabled:true}")
    private boolean tokenStreamingEnabled;

    /** 流式调用无 token 空闲超时（秒），与 Auto / Flow 路径保持一致，避免 Fixed 回答流挂死。 */
    @Value("${agent.streaming.idle-timeout-seconds:120}")
    private int streamingIdleTimeoutSeconds;

    @Value("${agent.streaming.retry-max-attempts:3}")
    private int streamingRetryMaxAttempts;

    /** 立即回答：关思考指令（单行，代码侧补换行）。 */
    @Value("${agent.answer-now.no-think-directive:[立即作答模式] 用户已要求立即回答，请直接基于以上已有信息给出最终答案，不要再展开额外的思考或分析过程，简明扼要。}")
    private String answerNowNoThinkDirective;

    /** 立即回答 finalize 关思考：OpenAI reasoning_effort（minimal/low/medium/high）；空=不设。设到 OpenAiChatOptions，由 Spring AI 确定性序列化进 body。 */
    @Value("${agent.no-think.reasoning-effort:}")
    private String answerNowReasoningEffort;

    /** 立即回答：finalize 是否在原有工具基础上保留工具调用（默认开）。 */
    @Value("${agent.answer-now.finalize-tools:true}")
    private boolean answerNowFinalizeTools;

    /** 第 61 轮 RAG 引用计数器；每轮入口清一次，保证引用从 [1] 起（修跨题累加成 [9][10]）。 */
    @javax.annotation.Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter sessionRefCounter;

    @Override
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        List<AiAgentClientFlowConfigVO> aiAgentClientList = repository.queryAiAgentClientsByAgentId(requestParameter.getAiAgentId());

        String content = "";
        String sessionId = requestParameter.getSessionId();

        // 注册取消标志以支持 cancelExecute()
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (sessionId != null) cancelFlags.put(sessionId, cancelled);
        // 立即回答标记注册
        AtomicBoolean finalizeFlag = new AtomicBoolean(false);
        if (sessionId != null) finalizeFlags.put(sessionId, finalizeFlag);
        // 引用计数器跨轮泄漏修复（2026-05-31）：每轮提问开始时清掉本 session 的 RAG 引用计数器，
        // 让每个回答的引用从 [1] 起（轮内多次检索仍连续累加）。在执行线程入口、用本轮 sessionId 同步清，
        // 不依赖派发器那处 @Autowired(required=false) 可能为 null 的 clear。
        if (sessionRefCounter != null && sessionId != null && !sessionId.isBlank()) {
            sessionRefCounter.clear(sessionId);
        }
        // 设置 agentId 到 ThreadLocal，供 MyBatisChatMemoryRepository.saveAll() 写入 ai_chat_memory.agent_id
        cn.bugstack.ai.domain.agent.service.memory.ChatMemoryContext.setAgentId(requestParameter.getAiAgentId());
        try {
        // 2026-05-07：fixed 策略只有一个 step（fixed），整体按 fixed_strategy 折叠
        // 多 client 场景下用 fixed_strategy_{idx} 区分
        int idx = 0;
        for (AiAgentClientFlowConfigVO config : aiAgentClientList) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                log.info("[FixedAgent] execution cancelled before step, sessionId={}", sessionId);
                break;
            }
            String stepId = aiAgentClientList.size() == 1 ? "fixed_strategy" : "fixed_strategy_" + (++idx);
            String displayName = aiAgentClientList.size() == 1 ? "回答" : ("回答 #" + idx);
            ChatClient chatClient = getChatClientByClientId(config.getClientId());

            sendStepStart(emitter, stepId, displayName, sessionId);

            long start = System.currentTimeMillis();
            // 2026-05-07 #1 Prompt Cache：current_date 从 system 占位符移到 user message 末尾，
            // 避免每天 system prompt byte 漂移导致 OpenAI 自动 cache 全失效。
            // system prompt 里 {current_date} 占位符若仍被 Spring AI 替换为空也无妨——它脱敏为空字符串
            String userMessage = requestParameter.getMessage()
                    + (content.isEmpty() ? "" : "，" + content)
                    + "\n\n（当前日期：" + LocalDate.now() + "）";

            // 注入当前 agent 真实工具清单，防止 LLM 幻觉不存在的工具
            String clientId = config.getClientId();
            List<ToolCallback> dynamicToolCallbacks = mcpToolCatalogService != null
                    ? mcpToolCatalogService.resolveDynamicToolCallbacks(clientId,
                            mcpToolCatalogService.needsFor(requestParameter.getSessionId()), requestParameter.getMessage(),
                            agentToolRegistry != null ? agentToolRegistry.getTools(clientId) : List.of())
                    : List.of();
            if (agentToolRegistry != null && clientId != null) {
                userMessage += "\n\n**【可用工具清单】**\n" + agentToolRegistry.describeToolsForPrompt(clientId, dynamicToolCallbacks);
            }
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt(userMessage)
                    .system(s -> s.param("current_date", ""))
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                            .param("ltm_retrieval_query", buildLtmRetrievalQuery(requestParameter, "fixed"))
                            .param("memory_persist_final_turn", true)
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100));
            if (!dynamicToolCallbacks.isEmpty()) {
                // 常驻工具 + 动态补充工具并集：Spring AI per-request toolCallbacks 非空会整体替换常驻工具，需自带常驻。
                List<ToolCallback> requestTools = agentToolRegistry != null
                        ? agentToolRegistry.combineWithResident(clientId, dynamicToolCallbacks)
                        : dynamicToolCallbacks;
                spec = spec.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .toolCallbacks(requestTools.toArray(new ToolCallback[0]))
                        .build());
            }
            // G1-C 修复：sessionId 注入 ToolContext，跟随调用流到 Reactor 工具执行线程（MDC 在那为空），
            // MeteredToolCallback 优先从 ToolContext 取 sessionId 才能弹人工审批 / 发进度事件。
            // stepLabel(displayName) 让前端审批弹窗标注是哪个回答步骤要的许可。
            log.info("[ToolCtxDiag][fixed] stepId={} sessionId={} inject={}", stepId, sessionId, (sessionId != null && !sessionId.isBlank()));
            if (sessionId != null && !sessionId.isBlank()) {
                java.util.Map<String, Object> tc = new java.util.HashMap<>();
                tc.put("sessionId", sessionId);
                if (displayName != null && !displayName.isBlank()) tc.put("stepLabel", displayName);
                spec = spec.toolContext(tc);
            }

            // G1-C：写回 MDC，让 Reactor 自动上下文传播在订阅时捕获、恢复到 boundedElastic 工具线程，
            // MeteredToolCallback 才能拿到 sessionId 触发审批。线程池 wrap 的 finally 会统一还原 MDC。
            if (sessionId != null && !sessionId.isBlank()) MDC.put("sessionId", sessionId);
            String stepResult;
            ChatResponse lastResponse = null;
            try {
                if (tokenStreamingEnabled) {
                    StringBuilder buf = new StringBuilder();
                    final ChatResponse[] last = new ChatResponse[1];
                    for (int attempt = 1; attempt <= streamingRetryMaxAttempts; attempt++) {
                        buf.setLength(0);
                        last[0] = null;
                    // v1.3.2：scopeSession 让 ReasoningContentFilter 按 session 隔离缓存
                    try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(sessionId)) {
                        // 立即回答 mid-stream 截断触发器：answer_now emit 它 → takeUntilOther 优雅完成 → 拿到半截
                        reactor.core.publisher.Sinks.One<Object> __trigger = reactor.core.publisher.Sinks.one();
                        if (sessionId != null) cancelTriggers.put(sessionId, __trigger);
                        Flux<ChatClientResponse> flux = spec.stream().chatClientResponse();
                        flux.timeout(Duration.ofSeconds(streamingIdleTimeoutSeconds))
                        .map(cr -> {
                            if (cr.chatResponse() != null) last[0] = cr.chatResponse();
                            // 2026-05-07 修：getText() 在 tool_use / metadata-only 末帧会返回 null，
                            // FluxMap 不允许 mapper 返回 null，必须兜成 ""
                            String raw = cr.chatResponse() != null
                                    && cr.chatResponse().getResult() != null
                                    && cr.chatResponse().getResult().getOutput() != null
                                    ? cr.chatResponse().getResult().getOutput().getText()
                                    : null;
                            String text = raw == null ? "" : raw;
                            buf.append(text);
                            return text;
                        }).doOnNext(token -> {
                            if (!token.isEmpty()) sendTokenEvent(emitter, token, stepId, sessionId);
                        }).doOnComplete(() -> sendTokenEvent(emitter, "[DONE]", stepId, sessionId))
                          .takeUntilOther(__trigger.asMono())
                          .blockLast();
                        // mid-stream 截断时补发 [DONE] 收尾 token 流
                        AtomicBoolean __fin0 = sessionId != null ? finalizeFlags.get(sessionId) : null;
                        if (__fin0 != null && __fin0.get()) {
                            sendTokenEvent(emitter, "[DONE]", stepId, sessionId);
                        }
                        break;
                    } catch (Exception e) {
                        if (isStreamingTimeout(e)) {
                            if (buf.length() <= 200 && attempt < streamingRetryMaxAttempts) {
                                log.warn("[FixedAgent] streaming idle timeout stepId={} chars={} attempt {}/{}, retrying",
                                        stepId, buf.length(), attempt, streamingRetryMaxAttempts);
                                sleepBeforeStreamingRetry(stepId, attempt);
                                continue;
                            }
                            log.warn("[FixedAgent] streaming idle timeout stepId={} chars={}, returning partial result",
                                    stepId, buf.length());
                            sendTokenEvent(emitter, "[DONE]", stepId, sessionId);
                            break;
                        } else {
                            if (attempt < streamingRetryMaxAttempts) {
                                log.warn("[FixedAgent] streaming attempt {}/{} failed stepId={} error={}, retrying",
                                        attempt, streamingRetryMaxAttempts, stepId, e.getMessage());
                                sleepBeforeStreamingRetry(stepId, attempt);
                                continue;
                            }
                            throw e;
                        }
                    }
                    }
                    stepResult = buf.toString();
                    lastResponse = last[0];
                } else {
                    lastResponse = spec.call().chatResponse();
                    stepResult = lastResponse != null && lastResponse.getResult() != null && lastResponse.getResult().getOutput() != null
                            ? lastResponse.getResult().getOutput().getText() : "";
                }
            } catch (Exception streamingEx) {
                if (streamingEx instanceof RuntimeException re) throw re;
                throw new RuntimeException(streamingEx);
            } finally {
                sendStepEnd(emitter, stepId, displayName + " 已完成", sessionId);
            }

            // 检查取消：blockLast() 或 spec.call() 返回后，如果已被取消则丢弃结果
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                log.info("[FixedAgent] execution cancelled after streaming, sessionId={}", sessionId);
                break;
            }

            long latency = System.currentTimeMillis() - start;
            content = stepResult;

            logTokenUsage(lastResponse, latency, stepId, config.getClientId(), requestParameter, userMessage, stepResult);
            log.info("智能体对话进行，客户端ID {} conversationId={}", requestParameter.getAiAgentId(), buildConversationId(requestParameter));

            // 立即回答：当前 client 关思考补一发收尾，停止后续 client
            AtomicBoolean __fin = sessionId != null ? finalizeFlags.get(sessionId) : null;
            if (__fin != null && __fin.get()) {
                // 打断前（被截断的流式回答）token，供 finalize marker 报告叠加值
                long preP = 0L, preC = 0L;
                if (lastResponse != null && lastResponse.getMetadata() != null && lastResponse.getMetadata().getUsage() != null) {
                    var u = lastResponse.getMetadata().getUsage();
                    preP = u.getPromptTokens() != null ? u.getPromptTokens() : 0L;
                    preC = u.getCompletionTokens() != null ? u.getCompletionTokens() : 0L;
                }
                content = fixedFinalizeNow(chatClient, config.getClientId(), requestParameter, stepResult, sessionId, preP, preC, emitter);
                break;
            }

            // 引导回复：折入新想法重做本回答（思考不关、工具保留），content 更新后继续 client 循环
            String __steer = sessionId != null ? steerInbox.remove(sessionId) : null;
            if (__steer != null && !__steer.isBlank()) {
                content = fixedSteerRerun(chatClient, config.getClientId(), requestParameter, stepResult, sessionId, __steer, emitter);
            }
        }

        log.info("智能体对话请求，结果 {} {}", requestParameter.getAiAgentId(), content);

        content = OutputFilter.cleanForUser(content);
        if (conversationTurnMemoryService != null) {
            conversationTurnMemoryService.saveFinalTurn(requestParameter, content);
        }

        // 2026-05-08：流式模式下 advisor.after() 拿不到完整 assistantText，节点级直触发 LTM 抽取。
        // 与 Episodic 写入并列，使用注入的 ILongTermMemoryService + router-small ChatClient。
        triggerLongTermMemoryExtraction(requestParameter, content);

        // P2.1 Episodic Memory：保存本次对话摘要
        saveEpisodicMemory(requestParameter, content);

        if (content != null && !content.trim().isEmpty()) {
            // P2.5 14.2 PII 脱敏：仅在最终输出给用户时脱敏
            sendFinalResult(emitter, OutputFilter.cleanForUser(cn.bugstack.ai.domain.agent.service.security.PiiMasker.mask(content)), requestParameter.getSessionId());
        }
        sendCompleteResult(emitter, requestParameter.getSessionId());
        } finally {
            longTermMemoryTurnSnapshot.clearSession(sessionId);
            cn.bugstack.ai.domain.agent.service.memory.ChatMemoryContext.clear();
            if (sessionId != null) cancelFlags.remove(sessionId);
            if (sessionId != null) finalizeFlags.remove(sessionId);
            if (sessionId != null) cancelTriggers.remove(sessionId);
            if (sessionId != null) steerInbox.remove(sessionId);
            if (sessionId != null && mcpToolCatalogService != null) mcpToolCatalogService.clearNeeds(sessionId);
            cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.clearLatestReasoning(sessionId);
        }
    }

    @Override
    public void cancelExecute(String sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
            // 立即截断在飞流式调用；line 297 流式返回后检查 cancelled→break，不走 finalize
            reactor.core.publisher.Sinks.One<Object> t = cancelTriggers.get(sessionId);
            if (t != null) t.tryEmitValue(Boolean.TRUE);
            log.info("[FixedAgent] cancelExecute called for sessionId={}", sessionId);
        }
    }

    /** 立即回答：置标记 + 截断当前在飞流式 call；流式返回后 fixedFinalizeNow 关思考补一发收尾。 */
    @Override
    public void finalizeExecute(String sessionId) {
        AtomicBoolean flag = finalizeFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
            reactor.core.publisher.Sinks.One<Object> t = cancelTriggers.get(sessionId);
            if (t != null) t.tryEmitValue(Boolean.TRUE);
            log.info("[FixedAgent] finalizeExecute (answer-now) for sessionId={}", sessionId);
        }
    }

    /** 引导回复：写入新想法 + 截断当前流式;流式返回后 fixedSteerRerun 折入想法重做(思考不关、工具保留)。 */
    @Override
    public void steerExecute(String sessionId, String idea) {
        if (sessionId == null || idea == null || idea.isBlank()) return;
        steerInbox.put(sessionId, idea);
        reactor.core.publisher.Sinks.One<Object> t = cancelTriggers.get(sessionId);
        if (t != null) t.tryEmitValue(Boolean.TRUE);
        log.info("[FixedAgent] steerExecute sessionId={} ideaLen={}", sessionId, idea.length());
    }

    private void sendStepStart(ResponseBodyEmitter emitter, String stepId, String displayName, String sessionId) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("stepId", stepId);
            p.put("displayName", displayName);
            p.put("sessionId", sessionId);
            p.put("timestamp", System.currentTimeMillis());
            emitter.send("event: step_start\ndata: " + JSON.toJSONString(p) + "\n\n");
        } catch (IOException e) {
            log.debug("fixed sendStepStart failed: {}", e.getMessage());
        }
    }

    private void sendStepEnd(ResponseBodyEmitter emitter, String stepId, String summary, String sessionId) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("stepId", stepId);
            p.put("summary", summary);
            p.put("sessionId", sessionId);
            p.put("timestamp", System.currentTimeMillis());
            emitter.send("event: step_end\ndata: " + JSON.toJSONString(p) + "\n\n");
        } catch (IOException e) {
            log.debug("fixed sendStepEnd failed: {}", e.getMessage());
        }
    }

    private void sendTokenEvent(ResponseBodyEmitter emitter, String token, String stepId, String sessionId) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("token", token);
            p.put("stepId", stepId);
            p.put("sessionId", sessionId);
            p.put("timestamp", System.currentTimeMillis());
            emitter.send("event: token\ndata: " + JSON.toJSONString(p) + "\n\n");
        } catch (IOException e) {
            log.debug("fixed sendTokenEvent failed: {}", e.getMessage());
        }
    }

    /**
     * 2026-05-08：流式聚合后触发 LTM 事实抽取，绕开 advisor.after() 的流式末帧 null 限制。
     * 抽取本身是异步的（CompletableFuture.runAsync），对主链路 0 阻塞。
     */
    private void triggerLongTermMemoryExtraction(ExecuteCommandEntity req, String assistantText) {
        if (longTermMemoryService == null) return;
        if (assistantText == null || assistantText.isBlank()) return;
        // 仅在流式模式下由节点接管：非流式下 advisor.after() 自己能抽取，节点再触发会重复。
        if (!tokenStreamingEnabled) return;
        String userId = req.getUserId() != null ? req.getUserId() : MDC.get("userId");
        if (userId == null || userId.isBlank()) return;
        String tenantId = req.getTenantId() != null ? req.getTenantId() : MDC.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";

        // router-small 做事实抽取（轻量、便宜）
        ChatClient extractionClient;
        try {
            extractionClient = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName("router-small"), ChatClient.class);
        } catch (Exception e) {
            log.warn("[FixedLTM] router-small not found, skip extraction: {}", e.getMessage());
            return;
        }
        cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor
                .triggerExtractionAsync(longTermMemoryService, extractionClient,
                        req.getMessage(), assistantText,
                        userId, tenantId, req.getSessionId(), req.getAiAgentId());
    }

    /**
     * 异步渐进式摘要：
     * - 首次：msgCount >= 20 且当前 session 无 episodic 记录 → 全量摘要 → INSERT
     * - 后续：每新增 4 条消息（= 2 轮）→ "已有摘要 + 最近 4 条" 重新摘要 → UPDATE（覆盖）
     */
    private void saveEpisodicMemory(ExecuteCommandEntity req, String assistantResponse) {
        log.info("[FixedSTM] saveEpisodicMemory called, episodicMemoryService={} contentLen={} threadPoolExecutor={}",
                episodicMemoryService != null, assistantResponse != null ? assistantResponse.length() : -1, threadPoolExecutor != null);
        if (episodicMemoryService == null) return;
        if (assistantResponse == null || assistantResponse.isBlank()) return;
        String userId = req.getUserId();
        if (userId == null || userId.isBlank()) userId = MDC.get("userId");
        if (userId == null || userId.isBlank()) return;
        String tenantId = req.getTenantId();
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        String sessionId = req.getSessionId();

        final String finalUserId = userId;
        final String finalTenantId = tenantId;
        final String finalSessionId = sessionId;

        Runnable task = () -> {
            try {
                log.info("[FixedSTM] task STARTED on thread={}", Thread.currentThread().getName());
                String convId = buildConversationId(req);
                // 直接查 repository 拿真实消息总数，绕过 SummarizingChatMemory 的滑动窗口截断
                int msgCount = repository.countChatMemoryByConversationId(convId);
                log.info("[FixedSTM] real msgCount={} (bypassing window)", msgCount);
                if (msgCount < EPISODIC_SUMMARY_THRESHOLD) return;

                // 节流：已有摘要时，delta 必须是 4 的倍数才触发
                int lastSummarized = episodicMemoryService.getLastSummarizedMsgCount(finalSessionId);
                int delta = msgCount - lastSummarized;
                log.info("[FixedSTM] episodic check: msgCount={} lastSummarized={} delta={} threshold={} interval={}",
                        msgCount, lastSummarized, delta, EPISODIC_SUMMARY_THRESHOLD, EPISODIC_THROTTLE_INTERVAL);
                if (lastSummarized >= 0) {
                    if (delta < EPISODIC_THROTTLE_INTERVAL || delta % EPISODIC_THROTTLE_INTERVAL != 0) {
                        log.info("[FixedSTM] episodic throttle: delta={} not a multiple of {}", delta, EPISODIC_THROTTLE_INTERVAL);
                        return;
                    }
                }

                ChatClient summarizer = null;
                String summarizerBeanName = cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.AI_CLIENT.getBeanName("router-small");
                try {
                    summarizer = applicationContext.getBean(summarizerBeanName, ChatClient.class);
                } catch (Exception e) {
                    log.warn("[FixedSTM] summarizer bean '{}' not found: {}", summarizerBeanName, e.getMessage());
                }
                if (summarizer == null) {
                    log.warn("[FixedSTM] summarizer is null, skipping episodic memory update. msgCount={} lastSummarized={}", msgCount, lastSummarized);
                    return;
                }

                // 从 repository 取全部消息文本用于摘要 prompt
                List<String> allTexts = repository.findChatMemoryTextsByConversationId(convId);
                String prompt;
                if (lastSummarized < 0) {
                    // 首次：全量历史做摘要
                    prompt = buildSummaryPromptFromTexts(null, allTexts, allTexts.size());
                } else {
                    // 后续：已有摘要 + 最近 delta 条消息 → 重新摘要
                    String previousSummary = episodicMemoryService.findBySessionId(finalSessionId);
                    log.info("[FixedSTM] merging: previousSummaryLen={} delta={} totalTexts={}",
                            previousSummary != null ? previousSummary.length() : 0, delta, allTexts.size());
                    int from = Math.max(0, allTexts.size() - delta);
                    List<String> recentTexts = allTexts.subList(from, allTexts.size());
                    prompt = buildSummaryPromptFromTexts(previousSummary, recentTexts, recentTexts.size());
                }

                String summary = summarizer.prompt().user(prompt).call().content();
                if (summary == null || summary.isBlank()) return;
                summary = summary.replaceAll("(?s)<think>.*?</think>", "").trim();
                if (summary.isBlank()) return;
                if (summary.length() > 500) summary = summary.substring(0, 500);

                String topic = finalUserId + "-" + finalSessionId;
                if (topic.length() > 64) topic = topic.substring(0, 64);
                episodicMemoryService.upsert(finalUserId, finalTenantId, finalSessionId, topic, summary, msgCount);
                log.info("[FixedSTM] episodic summarized msgCount={} summaryLen={} lastSummarized={} isFirst={}",
                        msgCount, summary.length(), lastSummarized, lastSummarized < 0);
            } catch (Exception e) {
                log.warn("[FixedSTM] episodic summarize failed: {}", e.getMessage(), e);
            }
        };

        if (threadPoolExecutor != null) {
            threadPoolExecutor.execute(task);
            log.info("[FixedSTM] task submitted to threadPoolExecutor, pool activeCount={} queueSize={}",
                    threadPoolExecutor.getActiveCount(), threadPoolExecutor.getQueue().size());
        } else {
            java.util.concurrent.CompletableFuture.runAsync(task);
            log.info("[FixedSTM] task submitted to CompletableFuture.runAsync (fallback)");
        }
    }

    /** 构建摘要 prompt：传入已有摘要（可选）+ 本轮对话消息 */
    private String buildSummaryPrompt(String previousSummary, List<org.springframework.ai.chat.messages.Message> msgs, int msgCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话摘要器。将以下对话压缩为1-3句中文摘要，涵盖主题、关键结论、用户意图。\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【此会话之前的摘要】\n").append(previousSummary).append("\n\n");
            sb.append("【本轮新增对话】\n");
        }
        sb.append("（共").append(msgCount).append("条消息）\n");
        int shown = 0;
        for (var m : msgs) {
            if (shown >= 20) break;
            String text = m.getText();
            if (text == null || text.isBlank()) continue;
            String role = m.getMessageType() != null ? m.getMessageType().name() : "?";
            String shortened = text.length() > 300 ? text.substring(0, 300) + "..." : text;
            sb.append("[").append(role).append("] ").append(shortened).append("\n");
            shown++;
        }
        sb.append("\n只输出摘要文本，不要前缀，不要解释。");
        return sb.toString();
    }

    /** 构建摘要 prompt（文本列表版）：从 repository 取出的 [role] content 格式 */
    private String buildSummaryPromptFromTexts(String previousSummary, List<String> texts, int msgCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话摘要器。将以下内容压缩为1-3句中文摘要，涵盖主题、关键结论、用户意图。\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【此会话之前的摘要】（必须保留其中的关键信息，与新增对话合并）\n").append(previousSummary).append("\n\n");
            sb.append("【本轮新增对话】\n");
            sb.append("（共").append(msgCount).append("条新消息）\n");
        } else {
            sb.append("（共").append(msgCount).append("条消息）\n");
        }
        int shown = 0;
        for (String line : texts) {
            if (shown >= 20) break;
            if (line == null || line.isBlank()) continue;
            String shortened = line.length() > 350 ? line.substring(0, 350) + "..." : line;
            sb.append(shortened).append("\n");
            shown++;
        }
        sb.append("\n要求：输出的摘要必须包含【之前的摘要】中的关键信息和【新增对话】的内容，两者缺一不可。只输出摘要文本，不要前缀，不要解释。");
        return sb.toString();
    }

    private ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    private String buildLtmRetrievalQuery(ExecuteCommandEntity req, String stage) {
        if (req == null) return "";
        String message = req.getMessage();
        if (message == null) message = "";
        return message;
    }

    private String buildConversationId(ExecuteCommandEntity req) {
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

    private void logTokenUsage(ChatResponse response, long latency, String stepName, String clientId,
                               ExecuteCommandEntity req, String promptText, String resultText) {
        llmObservationRecorder.record(LlmCallContext.builder()
                .stepName(stepName)
                .prompt(promptText)
                .resultText(resultText)
                .sessionId(req != null ? req.getSessionId() : null)
                .userId(req != null ? req.getUserId() : null)
                .tenantId(req != null ? req.getTenantId() : null)
                .agentId(req != null ? req.getAiAgentId() : null)
                .clientId(clientId)
                .build(), response, latency, null);
    }

    private <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    /**
     * 判断异常是否由 Flux idle timeout 触发（cause chain 中含 TimeoutException）。
     */
    private void sleepBeforeStreamingRetry(String stepId, int failedAttempt) {
        long baseDelayMs = 2_000L << Math.max(0, failedAttempt - 1);
        long jitterMs = ThreadLocalRandom.current().nextLong(-500L, 501L);
        long delayMs = Math.max(0L, baseDelayMs + jitterMs);
        log.info("[FixedAgent] streaming retry backoff stepId={} after attempt {}: {}ms", stepId, failedAttempt, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("fixed streaming retry backoff interrupted");
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
    
    /**
     * 立即回答（fixed）：用当前 client 关思考补一发收尾，基于"用户问题 + 半截答案 + 半截思考"直接作答。
     * 非流式 .call()（思考已关，答案短）；finalize-tools 开 + 原 agent 有工具时保留工具。失败退回半截答案。
     */
    private String fixedFinalizeNow(ChatClient client, String clientId, ExecuteCommandEntity req, String partialAnswer, String sessionId,
                                   long prePromptTokens, long preCompletionTokens, ResponseBodyEmitter emitter) {
        String partialReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.getLatestReasoning(sessionId);
        // 工具：常驻 + 补充（用于 prompt 工具清单 + 回调挂载）
        java.util.List<ToolCallback> dyn = (answerNowFinalizeTools && mcpToolCatalogService != null && clientId != null)
                ? mcpToolCatalogService.resolveDynamicToolCallbacks(clientId, mcpToolCatalogService.needsFor(sessionId), req.getMessage(),
                        agentToolRegistry != null ? agentToolRegistry.getTools(clientId) : java.util.List.of())
                : java.util.List.of();
        boolean attachTools = !dyn.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("用户在你回答过程中点击了【立即回答】，要求立刻给出最终答案。\n\n");
        sb.append("**用户原始问题:**\n").append(req.getMessage()).append("\n\n");
        if (partialAnswer != null && !partialAnswer.isBlank()) {
            sb.append("**你已经写出的部分回答（可能为半截）:**\n").append(partialAnswer).append("\n\n");
        }
        if (partialReasoning != null && !partialReasoning.isBlank()) {
            String pr = partialReasoning.length() > 4000 ? partialReasoning.substring(0, 4000) + "...(截断)" : partialReasoning;
            sb.append("**你刚才正在进行的思考（被中断，可能为半截）:**\n").append(pr).append("\n\n");
        }
        if (attachTools && agentToolRegistry != null) {
            sb.append("**【可用工具清单】**\n").append(agentToolRegistry.describeToolsForPrompt(clientId, dyn))
              .append("\n如需补足信息可调用上述工具后再作答；不需要则直接作答。\n\n");
        }
        sb.append("请基于以上内容直接给出最终答案。\n").append(answerNowNoThinkDirective);
        String prompt = sb.toString();

        // 立即回答可观测：LLM 调用前写标记行（中断点 + 改写后的完整 finalize 输入），LLM 失败也留痕。
        if (eventLogService != null) {
            try {
                String header = "【立即回答触发·fixed】中断点=回答流式中"
                        + " | partialAnswerChars=" + (partialAnswer != null ? partialAnswer.length() : 0)
                        + " | partialReasoningChars=" + (partialReasoning != null ? partialReasoning.length() : 0)
                        + " | 打断前累计token(prompt+completion)=" + prePromptTokens + "+" + preCompletionTokens
                        + "\n----- 改写后的 finalize 输入 -----\n";
                eventLogService.log(cn.bugstack.ai.domain.agent.service.execute.EventLogEntry.builder()
                        .sessionId(sessionId)
                        .userId(req.getUserId())
                        .tenantId(req.getTenantId())
                        .agentId(req.getAiAgentId())
                        .billingScope(LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE)
                        .stepName("answer_now_triggered")
                        .inputPrompt(header + prompt)
                        .model("intervention")
                        .latencyMs(0L)
                        .build());
                log.info("[AnswerNow][fixed] event_log marker written, partialAnswerChars={}", partialAnswer != null ? partialAnswer.length() : 0);
            } catch (Exception ex) {
                log.debug("[AnswerNow][fixed] marker log failed: {}", ex.getMessage());
            }
        }

        long start = System.currentTimeMillis();
        final String fStepId = "fixed_answer_now";
        sendStepStart(emitter, fStepId, "立即回答", sessionId);
        ChatClient.ChatClientRequestSpec spec = client.prompt().user(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(req))
                        .param("ltm_retrieval_query", buildLtmRetrievalQuery(req, "fixed-answer-now"))
                        .param("memory_persist_final_turn", true)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100));
        // 保留工具（常驻+补充）：finalize-tools 开 + 原 agent 有工具时（dyn 已在上方解析）
        boolean hasEffort = answerNowReasoningEffort != null && !answerNowReasoningEffort.isBlank();
        if (attachTools || hasEffort) {
            org.springframework.ai.openai.OpenAiChatOptions.Builder ob = org.springframework.ai.openai.OpenAiChatOptions.builder();
            if (attachTools) {
                java.util.List<ToolCallback> reqTools = agentToolRegistry != null
                        ? agentToolRegistry.combineWithResident(clientId, dyn) : dyn;
                ob.toolCallbacks(reqTools.toArray(new ToolCallback[0]));
            }
            // 立即回答关思考：reasoning_effort 设到 options，由 Spring AI 序列化进 body（确定性，绕开 filter 跨线程）。
            if (hasEffort) ob.reasoningEffort(answerNowReasoningEffort);
            spec = spec.options(ob.build());
        }
        StringBuilder buf = new StringBuilder();
        final ChatResponse[] last = new ChatResponse[1];
        // 立即回答 finalize：流式逐 token 推（与正常路径同协议）+ 真·关思考（reasoning_effort 设到 options）。
        // 思考已关、答案短，体验是"半截 → 无缝接着流出收尾"，不再是 .call() 卡几十秒后整段蹦出。
        try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(sessionId);
             AutoCloseable __noThink = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeNoThinking(sessionId)) {
            spec.stream().chatClientResponse()
                .map(cr -> {
                    if (cr.chatResponse() != null) last[0] = cr.chatResponse();
                    String raw = cr.chatResponse() != null && cr.chatResponse().getResult() != null
                            && cr.chatResponse().getResult().getOutput() != null
                            ? cr.chatResponse().getResult().getOutput().getText() : null;
                    String text = raw == null ? "" : raw;
                    buf.append(text);
                    return text;
                })
                .doOnNext(token -> { if (!token.isEmpty()) sendTokenEvent(emitter, token, fStepId, sessionId); })
                .doOnComplete(() -> sendTokenEvent(emitter, "[DONE]", fStepId, sessionId))
                .blockLast();
            String ans = buf.toString();
            // 走 recorder：写 ai_event_log（stepName=fixed_answer_now）+ Prometheus + ES
            logTokenUsage(last[0], System.currentTimeMillis() - start, fStepId, clientId, req, prompt, ans);
            sendStepEnd(emitter, fStepId, "立即回答 已完成", sessionId);
            log.info("[AnswerNow][fixed] 立即回答收尾完成(流式) len={}", ans.length());
            return (!ans.isBlank()) ? ans : partialAnswer;
        } catch (Exception e) {
            log.warn("[AnswerNow][fixed] 立即回答收尾失败，退回半截: {}", e.getMessage());
            logTokenUsage(last[0], System.currentTimeMillis() - start, fStepId, clientId, req, prompt, null);
            sendTokenEvent(emitter, "[DONE]", fStepId, sessionId);
            sendStepEnd(emitter, fStepId, "立即回答 失败", sessionId);
            return partialAnswer;
        }
    }

    /**
     * 引导回复（fixed）：折入"新想法 + 半截答案 + 半截思考"重做本回答；思考不关、保留工具。非流式 .call()。
     */
    private String fixedSteerRerun(ChatClient client, String clientId, ExecuteCommandEntity req, String partialAnswer, String sessionId, String idea, ResponseBodyEmitter emitter) {
        String partialReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.getLatestReasoning(sessionId);
        java.util.List<ToolCallback> dyn = (mcpToolCatalogService != null && clientId != null)
                ? mcpToolCatalogService.resolveDynamicToolCallbacks(clientId, mcpToolCatalogService.needsFor(sessionId), req.getMessage(),
                        agentToolRegistry != null ? agentToolRegistry.getTools(clientId) : java.util.List.of())
                : java.util.List.of();
        boolean attachTools = !dyn.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("【用户在你回答途中补充了新想法，请重做回答并把它纳入考虑，不要丢弃已有进展】\n");
        sb.append("用户补充：").append(idea).append("\n\n");
        sb.append("**用户原始问题:**\n").append(req.getMessage()).append("\n\n");
        if (partialAnswer != null && !partialAnswer.isBlank()) {
            sb.append("**你刚才已写出的部分回答（可能半截）:**\n").append(partialAnswer).append("\n\n");
        }
        if (partialReasoning != null && !partialReasoning.isBlank()) {
            String pr = partialReasoning.length() > 4000 ? partialReasoning.substring(0, 4000) + "...(截断)" : partialReasoning;
            sb.append("**你刚才的思考（可能半截）:**\n").append(pr).append("\n\n");
        }
        if (attachTools && agentToolRegistry != null) {
            sb.append("**【可用工具清单】**\n").append(agentToolRegistry.describeToolsForPrompt(clientId, dyn))
              .append("\n如需补足信息可调用上述工具。\n\n");
        }
        sb.append("请结合新想法重做回答。");
        String prompt = sb.toString();

        if (eventLogService != null) {
            try {
                eventLogService.log(cn.bugstack.ai.domain.agent.service.execute.EventLogEntry.builder()
                        .sessionId(sessionId).userId(req.getUserId()).tenantId(req.getTenantId()).agentId(req.getAiAgentId())
                        .billingScope(LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE)
                        .stepName("steer_triggered")
                        .inputPrompt("【引导触发·fixed】用户补充=" + idea + "\n----- 重做输入 -----\n" + prompt)
                        .model("intervention").latencyMs(0L).build());
            } catch (Exception ex) { log.debug("[Steer][fixed] marker failed: {}", ex.getMessage()); }
        }

        long start = System.currentTimeMillis();
        final String fStepId = "fixed_steer";
        sendStepStart(emitter, fStepId, "引导重做", sessionId);
        ChatClient.ChatClientRequestSpec spec = client.prompt().user(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(req))
                        .param("ltm_retrieval_query", buildLtmRetrievalQuery(req, "fixed-steer"))
                        .param("memory_persist_final_turn", true)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100));
        if (attachTools) {
            java.util.List<ToolCallback> reqTools = agentToolRegistry != null
                    ? agentToolRegistry.combineWithResident(clientId, dyn) : dyn;
            spec = spec.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .toolCallbacks(reqTools.toArray(new ToolCallback[0])).build());
        }
        StringBuilder buf = new StringBuilder();
        final ChatResponse[] last = new ChatResponse[1];
        // 引导重做：流式逐 token 推；思考不关（与设计一致，引导要让模型重新想）。
        try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(sessionId)) {
            spec.stream().chatClientResponse()
                .map(cr -> {
                    if (cr.chatResponse() != null) last[0] = cr.chatResponse();
                    String raw = cr.chatResponse() != null && cr.chatResponse().getResult() != null
                            && cr.chatResponse().getResult().getOutput() != null
                            ? cr.chatResponse().getResult().getOutput().getText() : null;
                    String text = raw == null ? "" : raw;
                    buf.append(text);
                    return text;
                })
                .doOnNext(token -> { if (!token.isEmpty()) sendTokenEvent(emitter, token, fStepId, sessionId); })
                .doOnComplete(() -> sendTokenEvent(emitter, "[DONE]", fStepId, sessionId))
                .blockLast();
            String ans = buf.toString();
            logTokenUsage(last[0], System.currentTimeMillis() - start, fStepId, clientId, req, prompt, ans);
            sendStepEnd(emitter, fStepId, "引导重做 已完成", sessionId);
            log.info("[Steer][fixed] 引导重做完成(流式) len={}", ans.length());
            return (!ans.isBlank()) ? ans : partialAnswer;
        } catch (Exception e) {
            log.warn("[Steer][fixed] 引导重做失败，退回原答案: {}", e.getMessage());
            logTokenUsage(last[0], System.currentTimeMillis() - start, fStepId, clientId, req, prompt, null);
            sendTokenEvent(emitter, "[DONE]", fStepId, sessionId);
            sendStepEnd(emitter, fStepId, "引导重做 失败", sessionId);
            return partialAnswer;
        }
    }

    /**
     * 发送最终结果到流式输出
     */
    private void sendFinalResult(ResponseBodyEmitter emitter, String content, String sessionId) {
        try {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(content, sessionId);
            String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
            emitter.send(sseData);
            log.info("✅ 已发送最终结果");
        } catch (Exception e) {
            log.error("发送最终结果失败：{}", e.getMessage(), e);
        }
    }
    
    /**
     * 发送完成标识到流式输出
     */
    private void sendCompleteResult(ResponseBodyEmitter emitter, String sessionId) {
        try {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
            String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
            emitter.send(sseData);
            log.info("✅ 已发送完成标识");
        } catch (Exception e) {
            log.error("发送完成标识失败：{}", e.getMessage(), e);
        }
    }

}
