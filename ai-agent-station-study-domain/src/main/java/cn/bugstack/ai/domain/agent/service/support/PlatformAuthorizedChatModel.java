package cn.bugstack.ai.domain.agent.service.support;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Set;

/** Cached helper clients must honor model/API revocation before every provider request. */
public final class PlatformAuthorizedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final String sourceModelId;
    private final IWorkspaceAccessRepository access;

    public PlatformAuthorizedChatModel(ChatModel delegate, String sourceModelId, IWorkspaceAccessRepository access) {
        this.delegate = Objects.requireNonNull(delegate);
        this.sourceModelId = Objects.requireNonNull(sourceModelId);
        this.access = Objects.requireNonNull(access);
    }

    @Override public ChatResponse call(Prompt prompt) {
        requireEnabled();
        return delegate.call(prompt);
    }

    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        // Check on subscription: the model may have been disabled after the stream was constructed.
        return Flux.defer(() -> {
            requireEnabled();
            return delegate.stream(prompt);
        });
    }

    @Override public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void requireEnabled() {
        Set<String> enabled = access.platformModelIds();
        if (enabled == null || !enabled.contains(sourceModelId)) {
            throw new IllegalStateException("平台辅助模型或连接已停用，请联系管理员");
        }
    }
}
