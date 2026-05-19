package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.context.ApplicationContext;

/**
 * 长期记忆 advisor（P1.7）。
 * <p>
 * before() 按当前用户 + query 召回 top-K 记忆前置到 user message；
 * after() 用小模型从对话中抽取用户事实/偏好/技能/决策，异步写入长期记忆。
 * <p>
 * P1.2.3 多租户隔离：用户 ID 优先从 MDC 取 {@code userId}（MdcTraceFilter 写入），
 * fallback 到 advisor context 的 {@code chat_memory_conversation_id}。
 */
@Slf4j
public class LongTermMemoryAdvisor implements BaseAdvisor {

    public static final String SESSION_CONTEXT_KEY = "chat_memory_conversation_id";
    public static final String MEMORY_PERSIST_CONTEXT_KEY = "memory_persist_final_turn";
    public static final String RETRIEVAL_QUERY_CONTEXT_KEY = "ltm_retrieval_query";

    private static final String EXTRACTION_PROMPT = """
            Extract any new facts, preferences, skills, or decisions about the user from this conversation.
            Ignore generic chitchat, greetings, or things the AI said about itself.
            Focus ONLY on what you learn about the USER.

            Categories for TOPIC:
            - preference:<subject> — what the user likes/dislikes/prefers
              e.g. "preference:answer_style", "preference:language_choice"
            - fact:<subject> — objective information about the user
              e.g. "fact:role", "fact:company", "fact:location"
            - skill:<subject> — what the user knows or uses (be specific: skill:Java, skill:PostgreSQL)
              e.g. "skill:Java", "skill:PostgreSQL", "skill:Spring_Boot"
            - decision:<subject> — a choice the user made
              e.g. "decision:caching", "decision:database_migration"

            IMPORTANT: Use granular, compound topics (e.g., "skill:Java" not just "skill") so different
            skills/facts don't collide. For each skill, tool, or technology, use a separate line with its
            own topic.

            If the user acquires a new skill or role (e.g. became a Java engineer after being Python),
            output it as a NEW fact — don't replace the old one. Only output contradictory statements
            when the user explicitly says they NO LONGER have the old attribute.

            For each extracted fact, output exactly one line:
            TOPIC: <category>:<subject> | CONTENT: <one sentence fact>

            CRITICAL — Language: Output the CONTENT in the SAME language as the user's
            last message. If the user wrote in Chinese, output the fact in Chinese.
            If English, English. Topic codes (preference/fact/skill/decision) stay English.

            If nothing new or meaningful to remember, output exactly: NONE

            Conversation:
            User: %s
            Assistant: %s""";

    private final ILongTermMemoryService ltm;
    private final int topK;
    private final int order;
    private ChatClient extractionClient;
    private ApplicationContext applicationContext;

    /** before() → after() 同线程传用户原文（Context propagation 不可靠，ThreadLocal 最稳） */
    private final ThreadLocal<String> currentUserText = new ThreadLocal<>();

    /** before() → after() 传递 userId，避免 MDC 在 advisor 链线程上丢失 */
    private final ThreadLocal<String> currentUserId = new ThreadLocal<>();
    private final ThreadLocal<Boolean> currentPersistEnabled = new ThreadLocal<>();

    public LongTermMemoryAdvisor(ILongTermMemoryService ltm, int topK) {
        this(ltm, topK, -100, null, null);
    }

    public LongTermMemoryAdvisor(ILongTermMemoryService ltm, int topK, int order) {
        this(ltm, topK, order, null, null);
    }

    public LongTermMemoryAdvisor(ILongTermMemoryService ltm, int topK, int order, ChatClient extractionClient) {
        this(ltm, topK, order, extractionClient, null);
    }

    public LongTermMemoryAdvisor(ILongTermMemoryService ltm, int topK, int order, ChatClient extractionClient, ApplicationContext applicationContext) {
        this.ltm = ltm;
        this.topK = topK > 0 ? topK : 4;
        this.order = order;
        this.extractionClient = extractionClient;
        this.applicationContext = applicationContext;
    }

    public void setExtractionClient(ChatClient extractionClient) {
        this.extractionClient = extractionClient;
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (ltm == null) {
            log.warn("[LTM] before SKIP: ltm is null (advisor created without ILongTermMemoryService)");
            return request;
        }

        Map<String, Object> ctx = request.context();
        currentPersistEnabled.set(Boolean.TRUE.equals(ctx == null ? null : ctx.get(MEMORY_PERSIST_CONTEXT_KEY)));
        String userId = MDC.get("userId");
        if (userId == null || userId.isBlank()) {
            Object uidObj = ctx == null ? null : firstNonNull(ctx.get("userId"), ctx.get("user_id"));
            userId = uidObj == null ? null : uidObj.toString();
        }
        if (userId == null || userId.isBlank()) {
            Object sidObj = ctx == null ? null : ctx.get(SESSION_CONTEXT_KEY);
            userId = extractUserIdFromConversationId(sidObj == null ? null : sidObj.toString());
        }
        if (userId == null || userId.isBlank()) {
            return request;
        }
        currentUserId.set(userId);

        UserMessage userMsg = request.prompt().getUserMessage();
        if (userMsg == null) return request;
        String userText = userMsg.getText();
        if (userText == null || userText.isBlank()) return request;

        currentUserText.set(userText);
        String retrievalQuery = userText;
        Object retrievalQueryObj = ctx == null ? null : ctx.get(RETRIEVAL_QUERY_CONTEXT_KEY);
        if (retrievalQueryObj != null && !retrievalQueryObj.toString().isBlank()) {
            retrievalQuery = retrievalQueryObj.toString();
        }

        // 混合检索：核心记忆（高频+近期）+ 语义相关记忆（向量相似度）
        List<String> profileLines;
        try {
            profileLines = ltm.retrieveForInjection(userId, retrievalQuery, 5, 5);
        } catch (Exception e) {
            log.warn("ltm.retrieveForInjection failed, fallback to retrieveProfile: {}", e.getMessage());
            try {
                profileLines = ltm.retrieveProfile(userId);
            } catch (Exception e2) {
                log.warn("ltm.retrieveProfile also failed: {}", e2.getMessage());
                return request;
            }
        }
        if (profileLines == null || profileLines.isEmpty()) return request;

        // 按 topic 分组展示，格式：[topic] content → 归类到对应区块
        StringBuilder memBlock = new StringBuilder();
        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<>();
        for (String line : profileLines) {
            String topic = "other";
            String content = line;
            if (line.startsWith("[")) {
                int close = line.indexOf("] ");
                if (close > 1) {
                    topic = line.substring(1, close);
                    content = line.substring(close + 2);
                }
            }
            grouped.computeIfAbsent(topic, k -> new ArrayList<>()).add(content);
        }
        for (Map.Entry<String, List<String>> g : grouped.entrySet()) {
            memBlock.append("[").append(g.getKey()).append("] ");
            memBlock.append(String.join("; ", g.getValue()));
            memBlock.append("\n");
        }

        String advisedUserText = "【关于用户的已知信息（来自长期记忆）】\n"
                + memBlock.toString().trim()
                + "\n\n--------\n"
                + userText;

        // 构建新消息列表：保留原始 SystemMessage + 替换 UserMessage
        // 否则 PromptChatMemoryAdvisor 后续拿不到 SystemMessage 会 NPE，导致短期记忆注入失败
        List<Message> messages = new ArrayList<>();
        Prompt originalPrompt = request.prompt();
        if (originalPrompt != null) {
            SystemMessage sysMsg = originalPrompt.getSystemMessage();
            if (sysMsg != null) {
                messages.add(sysMsg);
            }
        }
        messages.add(new UserMessage(advisedUserText));

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(messages).build())
                .context(ctx)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        boolean persistEnabled = Boolean.TRUE.equals(currentPersistEnabled.get());
        currentPersistEnabled.remove();
        if (!persistEnabled) {
            currentUserText.remove();
            currentUserId.remove();
            return response;
        }
        // 延迟获取 extractionClient：armory 阶段 RouterPool 可能还没创建 routerSmall
        if (ltm != null && extractionClient == null && applicationContext != null) {
            try {
                extractionClient = applicationContext.getBean("ai_client_router-small", ChatClient.class);
                log.info("[LTM] lazy-loaded extractionClient from ApplicationContext");
            } catch (Exception e) {
                log.warn("[LTM] lazy-load extractionClient failed: {}", e.getMessage());
            }
        }
        if (ltm != null && extractionClient != null) {
            extractAndSaveAsync(response);
        } else {
            log.warn("[LTM] after skip: ltm={} extractionClient={} applicationContext={}",
                    ltm != null, extractionClient != null, applicationContext != null);
        }
        return response;
    }

    private void extractAndSaveAsync(ChatClientResponse response) {
        String userText = currentUserText.get();
        currentUserText.remove();

        // 从 response output 取 assistant 回复
        String assistantText = null;
        try {
            if (response.chatResponse() != null && response.chatResponse().getResult() != null) {
                var output = response.chatResponse().getResult().getOutput();
                if (output != null) assistantText = output.getText();
            }
        } catch (Exception ignored) {}

        log.info("[LTM] after called userTextOk={} assistantLen={}",
                userText != null && !userText.isBlank(),
                assistantText != null ? assistantText.length() : -1);

        // 流式调用末帧 chatResponse 经常是 null/无 output。advisor.after 拿不到完整文本时，
        // 由调用节点（FixedAgentExecuteStrategy / Step4LogExecutionSummaryNode）流式聚合后
        // 直接调 triggerExtractionAsync(...)，绕开本路径。
        if (assistantText == null || assistantText.isBlank()) {
            currentUserId.remove();
            return;
        }

        // userId 优先从 MDC 取，缺失时 fallback 到 before() 设置的 ThreadLocal
        String resolvedUserId = MDC.get("userId");
        if (resolvedUserId == null || resolvedUserId.isBlank()) {
            resolvedUserId = currentUserId.get();
        }
        currentUserId.remove();
        if (resolvedUserId == null || resolvedUserId.isBlank()) {
            log.warn("[LTM] after skip: userId is null (MDC={} threadLocal={})",
                    MDC.get("userId"), "removed");
            return;
        }
        String tenantId = MDC.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        String sessionId = MDC.get("sessionId");
        String agentId = MDC.get("agentId");

        triggerExtractionAsync(ltm, extractionClient,
                userText, assistantText, resolvedUserId, tenantId, sessionId, agentId);
    }

    /**
     * 公共抽取入口：节点级流式调用聚合完后，直接调本方法触发 LTM 事实抽取，
     * 不再依赖 advisor.after()（流式末帧拿不到 ChatResponse output）。
     * <p>
     * 异步执行，对调用方 0 阻塞。任何失败被 catch 不抛出。
     *
     * @param ltm               LTM 持久化 service
     * @param extractionClient  抽取专用 ChatClient（建议 router-small）
     * @param userText          用户原始输入文本
     * @param assistantText     assistant 完整回复文本（流式聚合后的最终内容）
     * @param userId            用户 id（必填，缺失则不抽取）
     * @param tenantId          租户 id（缺失走 default）
     * @param sessionId         当前会话 id
     * @param agentId           agent id（仅日志用）
     */
    public static void triggerExtractionAsync(ILongTermMemoryService ltm, ChatClient extractionClient,
                                              String userText, String assistantText,
                                              String userId, String tenantId,
                                              String sessionId, String agentId) {
        if (ltm == null || extractionClient == null) {
            log.warn("[LTM] trigger skip: ltm={} extractionClient={}",
                    ltm != null, extractionClient != null);
            return;
        }
        if (userText == null || userText.isBlank()) return;
        if (assistantText == null || assistantText.isBlank()) return;
        if (assistantText.length() < 30) return;
        if (userId == null || userId.isBlank()) return;

        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        final String finalUserText = userText;
        final String finalAssistantText = assistantText;
        final String finalUserId = userId;
        final String finalSessionId = sessionId;
        final String finalAgentId = agentId;

        CompletableFuture.runAsync(() -> {
            if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
            try {
                String prompt = String.format(EXTRACTION_PROMPT,
                        finalUserText.length() > 600 ? finalUserText.substring(0, 600) : finalUserText,
                        finalAssistantText.length() > 800 ? finalAssistantText.substring(0, 800) : finalAssistantText);
                String raw = extractionClient.prompt(new Prompt(prompt)).call().content();
                if (raw == null || raw.isBlank() || "NONE".equalsIgnoreCase(raw.trim())) {
                    log.debug("[LTM] extraction returned no facts");
                    return;
                }

                // 剥离 <think>...</think> 思考块（部分模型会输出）
                String clean = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
                if (clean.isBlank() || "NONE".equalsIgnoreCase(clean.trim())) {
                    log.debug("[LTM] extraction clean text is empty after think removal");
                    return;
                }

                log.info("[LTM] extraction raw len={} clean len={}", raw.length(), clean.length());
                int saved = 0;
                for (String line : clean.split("\n")) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith("#")) continue;
                    // 过滤模板占位符（LLM 有时会把 prompt 格式原样输出）
                    if (line.contains("<category>") || line.contains("<subject>")
                            || line.contains("<sentence>") || line.contains("<one sentence")) continue;

                    // 兼容两种格式：
                    // A: TOPIC: topic | CONTENT: content
                    // B: TOPIC: topic content（无 | 分隔符）
                    int pipeIdx = line.indexOf('|');
                    String topic;
                    String content;

                    if (pipeIdx >= 0) {
                        String topicPart = line.substring(0, pipeIdx).trim();
                        String contentPart = line.substring(pipeIdx + 1).trim();
                        topic = extractTopic(topicPart);
                        content = extractContent(contentPart);
                    } else {
                        String afterTopic = line;
                        if (line.startsWith("TOPIC:") || line.startsWith("TOPIC：")) {
                            afterTopic = line.substring(6).trim();
                        }
                        int firstSpace = afterTopic.indexOf(' ');
                        if (firstSpace > 0) {
                            topic = afterTopic.substring(0, firstSpace).trim();
                            content = afterTopic.substring(firstSpace + 1).trim();
                        } else {
                            continue;
                        }
                    }

                    if (topic.isBlank()) topic = "general";
                    if (content.isBlank()) continue;

                    String memoryId = ltm.save(finalUserId, content, topic, "auto", finalSessionId);
                    if (memoryId != null) saved++;
                }
                log.info("[LTM] extracted facts saved={} userId={} sessionId={} agentId={}",
                        saved, finalUserId, finalSessionId, finalAgentId);
            } catch (Exception e) {
                log.warn("[LTM] extraction failed: {}", e.getMessage(), e);
            } finally {
                MDC.clear();
            }
        });
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return after(chain.nextCall(before(request, chain)), chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return BaseAdvisor.super.adviseStream(request, chain);
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /**
     * 从 "TOPIC: fact:role" / "主题：fact:role" / "fact:role" 中提取 topic。
     * 兼容 LLM 把英文标签翻译成中文（"主题"/"标签"）和中文全角冒号（：）。
     */
    private static String extractTopic(String topicPart) {
        String t = stripLabelPrefix(topicPart, new String[]{"TOPIC", "主题", "标签"});
        // 容错：如果还有以单字 "T" / "t" 起头的残留前缀，不再处理（避免误伤实际 topic 内容）
        return t;
    }

    /**
     * 从 "CONTENT: some text" / "内容：xxx" / "事实：xxx" / 直接 "some text" 中提取 content。
     * 这里曾经只识别英文 CONTENT 前缀，导致 LLM 用中文输出时把"内容：xxx"原样存进库。
     */
    private static String extractContent(String contentPart) {
        return stripLabelPrefix(contentPart, new String[]{"CONTENT", "内容", "事实", "陈述"});
    }

    /**
     * 通用的"标签：内容"前缀剥离工具：
     * 寻找首个英文/中文冒号；若冒号前的标签匹配任一已知关键字（大小写不敏感、忽略首尾空白），
     * 返回冒号后的内容；否则原样返回。同时兼容半角 ":" 和全角 "："。
     */
    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String extractUserIdFromConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        String[] parts = conversationId.split(":");
        if (parts.length >= 3) return parts[1];
        if (parts.length == 2) return parts[0];
        return conversationId;
    }

    private static String stripLabelPrefix(String s, String[] knownLabels) {
        if (s == null) return "";
        String trimmed = s.trim();
        // 找首个冒号（半角或全角，取靠前的那个）
        int half = trimmed.indexOf(':');
        int full = trimmed.indexOf('：');
        int colonIdx;
        if (half < 0) colonIdx = full;
        else if (full < 0) colonIdx = half;
        else colonIdx = Math.min(half, full);
        if (colonIdx <= 0) return trimmed;
        String beforeColon = trimmed.substring(0, colonIdx).trim();
        for (String label : knownLabels) {
            if (label.equalsIgnoreCase(beforeColon)) {
                return trimmed.substring(colonIdx + 1).trim();
            }
        }
        return trimmed;
    }
}
