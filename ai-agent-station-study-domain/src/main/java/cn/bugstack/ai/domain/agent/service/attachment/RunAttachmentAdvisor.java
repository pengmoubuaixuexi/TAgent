package cn.bugstack.ai.domain.agent.service.attachment;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shows current attachments and a session-wide metadata index to every step of every node mode.
 *
 * <p>Runs after memory, RAG and ask_user facts, before the image renderer. The manifest is a
 * separate user message just before the task, so the task stays the last user message and
 * images still attach to it.</p>
 */
@Component
public class RunAttachmentAdvisor implements BaseAdvisor {

    private static final String MARKER = "agent.run_attachments";

    private final RunAttachmentRegistry registry;

    @Value("${agent.attachments.inline-chars:8192}")
    private int inlineChars = ChatFileManifest.DEFAULT_INLINE_CHARS;

    @Value("${agent.attachments.inline-total-chars:32768}")
    private int inlineTotalChars = ChatFileManifest.DEFAULT_INLINE_TOTAL_CHARS;

    public RunAttachmentAdvisor(RunAttachmentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (request == null || request.prompt() == null
                || !(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) return request;
        RunAttachmentRegistry.Scope scope = registry.find(options.getToolContext());
        if (scope == null) return request;
        String manifest = ChatFileManifest.render(scope.runId(), scope.current(), inlineChars, inlineTotalChars)
                + ChatFileManifest.renderSessionIndex(scope.runId(), scope.sessionFiles(), scope.current());
        if (manifest.isEmpty()) return request;

        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        messages.removeIf(message -> Boolean.TRUE.equals(message.getMetadata().get(MARKER)));
        int task = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) { task = i; break; }
        }
        if (task < 0) return request;
        messages.add(task, UserMessage.builder().text(manifest).metadata(Map.of(MARKER, true)).build());
        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(messages).chatOptions(request.prompt().getOptions()).build())
                .context(request.context()).build();
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

    /** After RunUserInputAdvisor (900), before MultimodalMessageAdvisor (1000). */
    @Override
    public int getOrder() {
        return 950;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }
}
