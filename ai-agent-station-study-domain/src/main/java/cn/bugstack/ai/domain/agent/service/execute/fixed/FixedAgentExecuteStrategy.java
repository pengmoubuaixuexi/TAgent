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
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 固定执行策略
 *
 * @author TAgent
 * 2025/9/13 15:14
 */
@Slf4j
@Service("fixedAgentExecuteStrategy")
public class FixedAgentExecuteStrategy implements IExecuteStrategy {

    /** per-session 取消标志，用于支持 cancelExecute() */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Resource
    private IAgentRepository repository;

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    private AgentToolRegistry agentToolRegistry;

    /** V035 (2026-05-14)：fix 策略统一走 Prometheus / event_log / ES */
    @Resource
    private LlmObservationRecorder llmObservationRecorder;

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

    @Override
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        List<AiAgentClientFlowConfigVO> aiAgentClientList = repository.queryAiAgentClientsByAgentId(requestParameter.getAiAgentId());

        String content = "";
        String sessionId = requestParameter.getSessionId();

        // 注册取消标志以支持 cancelExecute()
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (sessionId != null) cancelFlags.put(sessionId, cancelled);
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
            userMessage = userMessage + githubRepositorySearchGuidance();

            // 注入当前 agent 真实工具清单，防止 LLM 幻觉不存在的工具
            String clientId = config.getClientId();
            if (agentToolRegistry != null && clientId != null) {
                userMessage += "\n\n**【可用工具清单】**\n" + agentToolRegistry.describeToolsForPrompt(clientId);
            }
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt(userMessage)
                    .system(s -> s.param("current_date", ""))
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                            .param("ltm_retrieval_query", buildLtmRetrievalQuery(requestParameter, "fixed"))
                            .param("memory_persist_final_turn", true)
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100));

            String stepResult;
            ChatResponse lastResponse = null;
            try {
                if (tokenStreamingEnabled) {
                    StringBuilder buf = new StringBuilder();
                    final ChatResponse[] last = new ChatResponse[1];
                    // v1.3.2：scopeSession 让 ReasoningContentFilter 按 session 隔离缓存
                    try (AutoCloseable __scope = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.scopeSession(sessionId)) {
                        Flux<ChatClientResponse> flux = spec.stream().chatClientResponse();
                        flux.map(cr -> {
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
                        }).doOnComplete(() -> sendTokenEvent(emitter, "[DONE]", stepId, sessionId)).blockLast();
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
            cn.bugstack.ai.domain.agent.service.memory.ChatMemoryContext.clear();
            if (sessionId != null) cancelFlags.remove(sessionId);
        }
    }

    @Override
    public void cancelExecute(String sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
            log.info("[FixedAgent] cancelExecute called for sessionId={}", sessionId);
        }
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
        return stage == null || stage.isBlank()
                ? message
                : message + "\n当前阶段: " + stage;
    }

    private String githubRepositorySearchGuidance() {
        return """

                [GitHub repository search guidance]
                If you need to search GitHub repositories, prefer English technical queries and GitHub qualifiers.
                Do not pass broad Chinese tutorial/resource phrases directly as the GitHub query unless the user explicitly asks to search only Chinese repositories.
                Examples: `spring-boot learning language:Java stars:>500`, `spring-boot examples language:Java stars:>500`, `spring-boot tutorial language:Java stars:>500`.
                Use page=1 and perPage<=10 for repository recommendations.
                """;
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
