package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryRecall;
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
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ApplicationContext;

/**
 * 长期记忆 advisor（P1.7）。
 * <p>
 * before() 按当前用户 + query 召回 top-K 记忆前置到 user message；
 * after() 用小模型从对话中抽取用户事实/偏好/技能/决策，异步写入长期记忆。
 * <p>
 * 用户身份来自服务端 context / MDC，冲突时不读取或写入记忆。
 * 抽取状态随本次请求传递，避免 Reactor 线程切换及线程复用造成串用户。
 */
@Slf4j
public class LongTermMemoryAdvisor implements BaseAdvisor {

    public static final String SESSION_CONTEXT_KEY = "chat_memory_conversation_id";
    public static final String MEMORY_PERSIST_CONTEXT_KEY = "memory_persist_final_turn";
    public static final String RETRIEVAL_QUERY_CONTEXT_KEY = "ltm_retrieval_query";

    private static final String EXTRACTION_PROMPT = """
            你是用户长期记忆的抽取器。从下面这轮对话中，抽取关于【用户本人】的、长期有价值的信息。
            只关注你了解到的关于用户的信息；忽略寒暄、闲聊、以及 AI 自己说的话。

            【事实来源】只抽取用户主动陈述、在提问中透露、或明确同意采纳的信息。
            助手的建议、推测、方案、评价，若用户没有明确采纳，绝不要当成用户的事实或决定。
            下面的"助手"文本仅用于帮你理解用户在指什么，它本身不是事实来源。

            【必须丢弃】一次性或当下状态，不要抽：今天的心情、今天吃什么、本回合的临时请求、
            临时请假、此刻正在做的单次操作——这些不是长期记忆。

            每条信息输出一行，格式严格为：
            TOPIC: <主题> | CONTENT: <一句话事实>

            主题(TOPIC)必须用中文，只能从下面的受控词表里选，逐字照抄；
            不许自创主题、不许把数值/状态/姓名写进主题（必须"画像:姓名"而不是"画像:姓名:张伟"，值一律放 CONTENT）、不许中英混用。
            每条事实独立成一行，绝不把多条挤在一行；TOPIC 只放"类别:主体"，CONTENT 只放一句话事实。

            【一、画像槽位】用户的长期背景，会跨领域影响回答。单值：只陈述用户当前的真实值，
            系统会自动用新值覆盖旧值。只有信息确实落在以下某槽位时才用"画像:"，不要新增槽位：
              画像:姓名 / 画像:年龄 / 画像:常驻城市 / 画像:职业 / 画像:工作年限 /
              画像:税前月收入 / 画像:身高 / 画像:当前体重 / 画像:健身目标 /
              画像:伴侣状况 / 画像:家庭赡养 / 画像:职业目标 / 画像:风险偏好
            画像:职业 必须写完整头衔（如"Java后端开发工程师"），不要泛化成"程序员"/"开发"；
            同一句话重复出现不代表职业变了，只有用户明确换了工作才更新。

            【二、情景信息】只在相关话题才有用。主题为"类别:主体"，类别只能是这四种之一，主体用简短名词：
              技能:<技术名>   只放用户【已掌握】的具体技术能力点（语言/框架/工具/方法，技术专名保留英文如 技能:Java、技能:SQL优化）；"想学/想了解/打算学"某技术是【意图】不是技能→归 计划:；职业头衔/岗位归 画像:职业，绝不写进技能；同一技能只用一个主体，不同技能可多条并存
              偏好:<主体>     用户长期稳定的喜好/习惯(偏好:回答风格、偏好:出行方式)；一次性请求("帮我推荐X")不是偏好，丢弃
              计划:<主体>     用户的具体计划或决定，会过期(计划:五一成都游、计划:Python学习)；同一件计划固定用同一个主体名，不要 计划:学python / 计划 / 计划:python学习 混着写
              情况:<主体>     其它长期但话题局限的事实(情况:徒弟、情况:在读书目)

            内容(CONTENT)的语言跟随用户本轮的语言：用户说中文就写中文，说英文就写英文
            (内容要进向量检索，必须语言保真，绝不翻译)。每条只写一句话，
            同一主题只输出一条最新事实。

            如果这轮没有任何值得长期记住的新信息，只输出: NONE

            对话:
            用户: %s
            助手: %s""";

    private final ILongTermMemoryService ltm;
    private final int topK;
    private final int order;
    private ChatClient extractionClient;
    private ApplicationContext applicationContext;
    private volatile LongTermMemoryTurnSnapshot turnSnapshot;

    /** H2-A：记忆证据 emitter（可选）。null → 不 emit，advisor 行为不变。setter 注入避免改构造链。 */
    private volatile cn.bugstack.ai.domain.agent.service.execute.common.MemoryEvidenceEmitter memoryEvidenceEmitter;

    public void setMemoryEvidenceEmitter(cn.bugstack.ai.domain.agent.service.execute.common.MemoryEvidenceEmitter emitter) {
        this.memoryEvidenceEmitter = emitter;
    }

    private static final String EXTRACTION_TURN_CONTEXT_KEY = LongTermMemoryAdvisor.class.getName() + ".extractionTurn";
    private record ExtractionTurn(String userText, String userId, String tenantId, String sessionId,
                                  String agentId, AtomicBoolean claimed) {}

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

    public void setTurnSnapshot(LongTermMemoryTurnSnapshot turnSnapshot) {
        this.turnSnapshot = turnSnapshot;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // A reused context must never carry another invocation's pending extraction.
        if (request.context().containsKey(EXTRACTION_TURN_CONTEXT_KEY)) {
            Map<String, Object> cleanContext = new LinkedHashMap<>(request.context());
            cleanContext.remove(EXTRACTION_TURN_CONTEXT_KEY);
            request = ChatClientRequest.builder().prompt(request.prompt()).context(cleanContext).build();
        }
        if (ltm == null) {
            log.warn("[LTM] before SKIP: ltm is null (advisor created without ILongTermMemoryService)");
            return request;
        }

        Map<String, Object> ctx = request.context();
        String userId = TrustedAdvisorIdentity.userId(ctx);
        if (userId == null || userId.isBlank()) {
            return request;
        }
        UserMessage userMsg = request.prompt().getUserMessage();
        if (userMsg == null) return request;
        String userText = userMsg.getText();
        if (userText == null || userText.isBlank()) return request;

        if (Boolean.TRUE.equals(ctx.get(MEMORY_PERSIST_CONTEXT_KEY))) {
            Map<String, Object> extractionContext = new LinkedHashMap<>(ctx);
            extractionContext.put(EXTRACTION_TURN_CONTEXT_KEY, new ExtractionTurn(userText, userId,
                    TrustedAdvisorIdentity.tenantId(ctx), TrustedAdvisorIdentity.sessionId(ctx),
                    MDC.get("agentId"), new AtomicBoolean()));
            request = ChatClientRequest.builder().prompt(request.prompt()).context(extractionContext).build();
            ctx = request.context();
        }
        String retrievalQuery = userText;
        Object retrievalQueryObj = ctx == null ? null : ctx.get(RETRIEVAL_QUERY_CONTEXT_KEY);
        if (retrievalQueryObj != null && !retrievalQueryObj.toString().isBlank()) {
            retrievalQuery = retrievalQueryObj.toString();
        }

        // 混合检索：核心记忆（高频+近期）+ 语义相关记忆（向量相似度）
        String evidenceSessionId = TrustedAdvisorIdentity.sessionId(ctx);
        List<LongTermMemoryRecall> recalls = loadTurnMemorySnapshot(evidenceSessionId, userId, retrievalQuery);
        List<String> profileLines = recalls.stream().map(LongTermMemoryRecall::toPromptLine).toList();
        if (profileLines == null || profileLines.isEmpty()) return request;

        // H2-A：emit memory_evidence SSE 给前端展示"本轮用了哪些长期记忆"。
        // emitter 内部已 explain 开关 + try/catch 兜底；此处再外层 try 保险，advisor 失败 ≠ 主回答失败
        try {
            if (memoryEvidenceEmitter != null) {
                memoryEvidenceEmitter.emitLongTermEvidenceDetailed(evidenceSessionId, recalls);
            }
        } catch (Exception emitEx) {
            log.debug("[LTM] memory evidence emit skipped: {}", emitEx.toString());
        }

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

        Map<String, Object> nextCtx = new LinkedHashMap<>();
        if (ctx != null) nextCtx.putAll(ctx);
        nextCtx.put(cn.bugstack.ai.domain.agent.service.prompt.ContextEnvelopeComposer.CTX_LTM,
                memBlock.toString().trim());
        return ChatClientRequest.builder()
                // P2-B-2：LTM 不再直接改写 UserMessage，只把 section 写入 request context；
                // 后续 ContextEnvelopeRenderAdvisor 统一渲染。Prompt 原样透传，尤其不能丢 options。
                .prompt(request.prompt())
                .context(nextCtx)
                .build();
    }

    private List<LongTermMemoryRecall> loadTurnMemorySnapshot(String sessionId, String userId, String retrievalQuery) {
        LongTermMemoryTurnSnapshot snapshot = this.turnSnapshot;
        if (snapshot == null || sessionId == null || sessionId.isBlank()) {
            return retrieveProfileLines(userId, retrievalQuery);
        }
        return snapshot.getOrLoadDetailed(sessionId, userId, () -> retrieveProfileLines(userId, retrievalQuery));
    }

    private List<LongTermMemoryRecall> retrieveProfileLines(String userId, String retrievalQuery) {
        try {
            // 核心画像保留足够槽位；相关记忆数量采用当前 Advisor 的配置。
            return ltm.retrieveForInjectionDetailed(userId, retrievalQuery, 30, topK);
        } catch (Exception e) {
            log.warn("ltm.retrieveForInjection failed, fallback to retrieveProfile: {}", e.getMessage());
            try {
                return ltm.retrieveProfile(userId).stream()
                        .map(content -> LongTermMemoryRecall.builder().topic("other").content(content)
                                .kind(LongTermMemoryRecall.KIND_CORE).build())
                        .toList();
            } catch (Exception e2) {
                log.warn("ltm.retrieveProfile also failed: {}", e2.getMessage());
                return List.of();
            }
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Object pending = response.context().get(EXTRACTION_TURN_CONTEXT_KEY);
        if (!(pending instanceof ExtractionTurn turn) || !turn.claimed().compareAndSet(false, true)) return response;
        if (TrustedAdvisorIdentity.conflictsWith(turn.userId(), response.context())) return response;
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
            extractAndSaveAsync(response, turn);
        } else {
            log.warn("[LTM] after skip: ltm={} extractionClient={} applicationContext={}",
                    ltm != null, extractionClient != null, applicationContext != null);
        }
        return response;
    }

    private void extractAndSaveAsync(ChatClientResponse response, ExtractionTurn turn) {
        String userText = turn.userText();

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
            return;
        }
        triggerExtractionAsync(ltm, extractionClient,
                userText, assistantText, turn.userId(), turn.tenantId(), turn.sessionId(), turn.agentId());
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

        Map<String, String> currentMdc = MDC.getCopyOfContextMap();
        Map<String, String> mdcSnapshot = currentMdc == null ? new LinkedHashMap<>() : new LinkedHashMap<>(currentMdc);
        mdcSnapshot.put("userId", userId);
        mdcSnapshot.put("tenantId", tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        if (sessionId == null) mdcSnapshot.remove("sessionId"); else mdcSnapshot.put("sessionId", sessionId);
        if (agentId == null) mdcSnapshot.remove("agentId"); else mdcSnapshot.put("agentId", agentId);
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

                    // 脏抽取防护（2026-05-31）：LLM 偶尔把值粘进 topic（画像:姓名:张伟）或把多条记忆挤进一行
                    // （CONTENT 又长得像一条"类别:主体"记录）。这类行整条丢弃，避免污染库（下一轮会重新干净抽取）。
                    if (isDirtyExtraction(topic, content)) {
                        log.warn("[LTM] 丢弃脏抽取行 topic='{}' content='{}'", topic,
                                content.length() > 60 ? content.substring(0, 60) + "…" : content);
                        continue;
                    }

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

    /** content 自身又长得像一条"类别:主体…"记录 → 说明多条粘连/字段错位。 */
    private static final java.util.regex.Pattern DIRTY_CONTENT_AS_TOPIC =
            java.util.regex.Pattern.compile("^\\s*(画像|计划|情况|技能|偏好|职业目标)\\s*[:：]");

    /**
     * 脏抽取判定（2026-05-31）：命中任一即整行丢弃。
     * 1) content 又像一条"类别:主体"记录 → 一行挤了多条 / 字段错位（如 content="计划:技术博客写作: …"）。
     * 2) topic 的"主体"（首个冒号之后）里还含冒号 → 值被粘进了 topic（如 "画像:姓名: 张伟"）。
     */
    private static boolean isDirtyExtraction(String topic, String content) {
        if (topic == null || content == null) return true;
        if (DIRTY_CONTENT_AS_TOPIC.matcher(content).find()) return true;
        String t = topic.trim();
        int c = firstColonIndex(t);
        if (c >= 0) {
            String subject = t.substring(c + 1);
            if (subject.indexOf(':') >= 0 || subject.indexOf('：') >= 0) return true;
        }
        return false;
    }

    /** 返回首个半角或全角冒号的下标（取靠前者），无冒号返回 -1。 */
    private static int firstColonIndex(String s) {
        int half = s.indexOf(':');
        int full = s.indexOf('：');
        if (half < 0) return full;
        if (full < 0) return half;
        return Math.min(half, full);
    }
}
