package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
    public static final String RESPONSE_SIZE_KEY = "chat_memory_response_size";
    public static final String RETRIEVE_SIZE_KEY = "chat_memory_retrieve_size";

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

        int retrieveSize = resolveRetrieveSize(ctx);
        if (retrieveSize == 0) {
            return request;
        }

        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return request;
        }
        if (retrieveSize > 0 && history.size() > retrieveSize) {
            int start = history.size() - retrieveSize;
            if (start > 0
                    && history.get(start).getMessageType() == MessageType.ASSISTANT
                    && history.get(start - 1).getMessageType() == MessageType.USER) {
                start--;
            }
            history = history.subList(start, history.size());
        }

        history = completeHistoryTurns(history);

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
                // 透传 options，否则 per-request 动态工具回调会丢（见 LongTermMemoryAdvisor 同样修复）。
                .prompt(Prompt.builder().messages(messages).chatOptions(request.prompt().getOptions()).build())
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

    private static int resolveRetrieveSize(Map<String, Object> ctx) {
        if (ctx == null) {
            return -1;
        }
        Object value = ctx.get(RESPONSE_SIZE_KEY);
        if (value == null) {
            value = ctx.get(RETRIEVE_SIZE_KEY);
        }
        if (value == null) {
            return -1;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * Only inject complete persisted dialogue turns.
     *
     * <p>The current request is appended after history by this advisor. If persisted history ends with
     * a dangling USER message (for example because a long E2E/shared session was summarized or windowed
     * in the middle of a turn), blindly injecting it produces a malformed wire prompt:
     * {@code ... USER(history), USER(current)}. Models then often treat the historical user as the
     * current task or merge the two user payloads.</p>
     *
     * <p>System messages (conversation summaries) are preserved. Leading assistant fragments are
     * preserved for compatibility with old summarized/windowed histories. USER fragments are buffered
     * until their matching ASSISTANT arrives; an unmatched trailing USER is dropped.</p>
     */
    private static List<Message> completeHistoryTurns(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        List<Message> result = new ArrayList<>(history.size());
        Message pendingUser = null;
        for (Message message : history) {
            if (message == null || message.getMessageType() == null) {
                continue;
            }
            if (message instanceof SystemMessage || message.getMessageType() == MessageType.SYSTEM) {
                result.add(message);
                continue;
            }
            if (message.getMessageType() == MessageType.USER) {
                // A new user before an assistant means the previous user was an incomplete turn.
                pendingUser = message;
                continue;
            }
            if (message instanceof AssistantMessage || message.getMessageType() == MessageType.ASSISTANT) {
                if (pendingUser != null) {
                    result.add(pendingUser);
                    pendingUser = null;
                }
                result.add(message);
            }
        }
        return result;
    }
}
