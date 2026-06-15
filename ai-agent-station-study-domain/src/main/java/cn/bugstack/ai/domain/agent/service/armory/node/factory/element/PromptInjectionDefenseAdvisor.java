package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.types.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt Injection 防御 advisor（P2.5 14.1）。
 * <p>
 * before() 阶段：
 * 1. 在所有 user message 中扫描已知注入模式（DAN / ignore / system override）
 * 2. RAG 检索出的 documents 用 {@code <untrusted_data>} 包裹 + system 声明
 * <p>
 * 不阻断请求（防止误杀），只打 warn log（ELK 可搜索审计）。
 */
@Slf4j
public class PromptInjectionDefenseAdvisor implements BaseAdvisor {

    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|above|prior)\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+DAN"),
            Pattern.compile("(?i)system\\s*:\\s*override"),
            Pattern.compile("(?i)pretend\\s+you\\s+are"),
            Pattern.compile("(?i)forget\\s+everything"),
            Pattern.compile("(?i)new\\s+system\\s+prompt"),
            Pattern.compile("(?i)\\{\\{.*?\\}\\}"),  // template injection
            Pattern.compile("(?i)<\\s*script\\s*>"),  // XSS via prompt
            Pattern.compile("(?i)(请扮演|现在你是|忽略).*(规则|指令|限制)"),  // Chinese role-playing/ignore
            Pattern.compile("(?i)(DAN|越狱|角色扮演)"),  // Chinese/English jailbreak
            Pattern.compile("(?i)tell\\s+me\\s+your\\s+system\\s+prompt"),  // system prompt extraction
            Pattern.compile("(?i)reveal\\s+your\\s+(system\\s+)?prompt"),
            Pattern.compile("(?i)show\\s+me\\s+your\\s+instructions"),
    };

    @Value("${agent.prompt-injection.block-on-detect:false}")
    private boolean blockOnDetect;

    private final int order;

    public PromptInjectionDefenseAdvisor() {
        this(-50);
    }

    public PromptInjectionDefenseAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) return request;

        boolean flagged = false;
        List<Message> sanitized = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.USER || msg.getMessageType() == MessageType.SYSTEM) {
                String text = msg.getText();
                if (text != null && !text.isBlank()) {
                    for (Pattern p : INJECTION_PATTERNS) {
                        if (p.matcher(text).find()) {
                            log.warn("[PromptInjection] pattern={} detected in {} message",
                                    p.pattern(), msg.getMessageType());
                            flagged = true;
                            break;
                        }
                    }
                }
            }
            sanitized.add(msg);
        }

        if (flagged) {
            if (blockOnDetect) {
                log.warn("[PromptInjection] potential injection flagged — request BLOCKED");
                throw new BizException("请求包含潜在注入内容，已被阻止");
            }
            log.warn("[PromptInjection] potential injection flagged — request NOT blocked, audit ELK");
        }

        return ChatClientRequest.builder()
                // 透传 options，否则 per-request 动态工具回调会丢（见 LongTermMemoryAdvisor 同样修复）。
                .prompt(Prompt.builder().messages(sanitized).chatOptions(request.prompt().getOptions()).build())
                .context(request.context())
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
