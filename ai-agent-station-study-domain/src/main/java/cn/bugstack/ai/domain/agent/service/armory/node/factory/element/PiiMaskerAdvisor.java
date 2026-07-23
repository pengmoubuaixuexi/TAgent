package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.domain.agent.service.security.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * PII 脱敏 advisor（P2.5 14.2 输入侧）。
 * <p>
 * before() 阶段：
 * 1. 对所有 user message 调用 {@link PiiMasker#mask(String)} 掩码
 * 2. 在 PromptInjectionDefense（order=-50）之前执行，确保注入检测看到的是已脱敏文本
 * <p>
 * 输出侧 PII 脱敏仍由 AbstractExecuteSupport 作为深度防御层保留。
 */
@Slf4j
public class PiiMaskerAdvisor implements BaseAdvisor {

    private final int order;

    public PiiMaskerAdvisor() {
        this(-60);
    }

    public PiiMaskerAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) return request;

        List<Message> sanitized = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.USER) {
                String text = msg.getText();
                if (text != null && !text.isBlank()) {
                    String masked = PiiMasker.mask(text);
                    sanitized.add(((UserMessage) msg).mutate().text(masked).build());
                } else {
                    sanitized.add(msg);
                }
            } else {
                sanitized.add(msg);
            }
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
