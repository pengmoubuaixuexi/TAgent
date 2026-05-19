package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 跨会话摘要记忆 advisor（P2.1 Episodic Memory）。
 * <p>
 * before() 阶段取用户最近 N 次会话摘要，作为"历史对话背景"前置到 user message；
 * after() 暂不做（摘要生成由 Step4 节点触发，不在 advisor 内）。
 * <p>
 * 与 LongTermMemoryAdvisor 的区别：LTM 按用户 query 做语义检索召回相关事实，
 * Episodic 只取最近 N 条摘要按时间序，不依赖向量检索。
 */
@Slf4j
public class EpisodicMemoryAdvisor implements BaseAdvisor {

    /** 与 ChatMemoryAdvisor 用同一个 context key */
    public static final String SESSION_CONTEXT_KEY = "chat_memory_conversation_id";

    private final IEpisodicMemoryService episodic;
    private final int topN;
    private final int order;

    public EpisodicMemoryAdvisor(IEpisodicMemoryService episodic, int topN) {
        this(episodic, topN, -80); // order 比 LTM(-100) 晚但早于 RAG(0)，让 RAG 之前已有上下文
    }

    public EpisodicMemoryAdvisor(IEpisodicMemoryService episodic, int topN, int order) {
        this.episodic = episodic;
        this.topN = topN > 0 ? topN : 5;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (episodic == null) return request;

        Map<String, Object> ctx = request.context();
        String userId = MDC.get("userId");
        if (userId == null || userId.isBlank()) {
            Object sidObj = ctx == null ? null : ctx.get(SESSION_CONTEXT_KEY);
            userId = sidObj == null ? null : sidObj.toString();
        }
        if (userId == null || userId.isBlank()) {
            return request;
        }

        UserMessage userMsg = request.prompt().getUserMessage();
        if (userMsg == null) return request;
        String userText = userMsg.getText();
        if (userText == null || userText.isBlank()) return request;

        // 当前 sessionId：优先从 MDC 取
        String sessionId = MDC.get("sessionId");

        // ① 当前会话的摘要
        String currentSessionSummary = null;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                currentSessionSummary = episodic.findBySessionId(sessionId);
            } catch (Exception e) {
                log.warn("episodic.findBySessionId failed: {}", e.getMessage());
            }
        }

        // ② 其他会话的摘要（排除当前 sessionId，5 天内）
        List<String> otherEpisodes = Collections.emptyList();
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                otherEpisodes = episodic.getOtherSessions(userId, sessionId, topN, 5);
            } catch (Exception e) {
                log.warn("episodic.getOtherSessions failed: {}", e.getMessage());
            }
        } else {
            // sessionId 为空时 fallback 到旧逻辑
            try {
                otherEpisodes = episodic.getRecent(userId, topN);
            } catch (Exception e) {
                log.warn("episodic.getRecent failed: {}", e.getMessage());
            }
        }

        boolean hasCurrent = currentSessionSummary != null && !currentSessionSummary.isBlank();
        boolean hasOthers = otherEpisodes != null && !otherEpisodes.isEmpty();
        if (!hasCurrent && !hasOthers) return request;

        StringBuilder sb = new StringBuilder();
        if (hasCurrent) {
            sb.append("【当前会话已聊到】\n");
            sb.append(currentSessionSummary).append("\n");
        }
        if (hasOthers) {
            if (hasCurrent) sb.append("\n");
            sb.append("【最近聊过的其他话题】\n");
            for (int i = 0; i < otherEpisodes.size(); i++) {
                sb.append(i + 1).append(". ").append(otherEpisodes.get(i)).append("\n");
            }
        }
        sb.append("\n--------\n").append(userText);

        // 保留原始 SystemMessage，避免 ChatMemory advisor 后续拿不到
        List<Message> messages = new ArrayList<>();
        Prompt originalPrompt = request.prompt();
        if (originalPrompt != null) {
            SystemMessage sysMsg = originalPrompt.getSystemMessage();
            if (sysMsg != null) messages.add(sysMsg);
        }
        messages.add(new UserMessage(sb.toString()));

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(messages).build())
                .context(ctx)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
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
}
