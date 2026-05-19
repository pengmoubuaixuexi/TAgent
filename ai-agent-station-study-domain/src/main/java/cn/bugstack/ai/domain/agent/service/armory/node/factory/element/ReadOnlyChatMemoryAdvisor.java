package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Injects persisted chat history but never writes the current request/response.
 */
public class ReadOnlyChatMemoryAdvisor implements BaseAdvisor {

    public static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final ChatMemory chatMemory;
    private final int order;

    public ReadOnlyChatMemoryAdvisor(ChatMemory chatMemory) {
        this(chatMemory, 0);
    }

    public ReadOnlyChatMemoryAdvisor(ChatMemory chatMemory, int order) {
        this.chatMemory = chatMemory;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (chatMemory == null || request == null || request.prompt() == null) {
            return request;
        }
        Map<String, Object> ctx = request.context();
        Object conversationIdObj = ctx == null ? null : ctx.get(CONVERSATION_ID_KEY);
        String conversationId = conversationIdObj == null ? null : conversationIdObj.toString();
        if (conversationId == null || conversationId.isBlank()) {
            return request;
        }

        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return request;
        }

        List<Message> current = request.prompt().getInstructions();
        List<Message> messages = new ArrayList<>(history.size() + current.size());
        for (Message message : current) {
            if (message instanceof SystemMessage) {
                messages.add(message);
            }
        }
        messages.addAll(history);
        for (Message message : current) {
            if (!(message instanceof SystemMessage)) {
                messages.add(message);
            }
        }
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
        return chain.nextCall(before(request, chain));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(before(request, chain));
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
