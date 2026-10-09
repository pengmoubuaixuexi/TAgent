package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.service.support.PlatformAuthorizedChatModel;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PlatformAuthorizedChatModelTest {
    @Test public void cachedClientRejectsCallsAfterModelOrApiRevocation() {
        ChatModel provider = mock(ChatModel.class);
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        var model = new PlatformAuthorizedChatModel(provider, "platform-model", access);
        Prompt prompt = new Prompt("fixture");
        ChatResponse response = new ChatResponse(List.of());
        when(access.platformModelIds()).thenReturn(Set.of("platform-model"));
        when(provider.call(prompt)).thenReturn(response);
        assertSame(response, model.call(prompt));
        when(access.platformModelIds()).thenReturn(Set.of());
        assertThrows(IllegalStateException.class, () -> model.call(prompt));
        verify(provider, times(1)).call(prompt);
    }

    @Test public void streamChecksAuthorizationOnEverySubscription() {
        ChatModel provider = mock(ChatModel.class);
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        var model = new PlatformAuthorizedChatModel(provider, "platform-model", access);
        Prompt prompt = new Prompt("fixture");
        ChatResponse response = new ChatResponse(List.of());
        when(access.platformModelIds()).thenReturn(Set.of("platform-model"));
        when(provider.stream(prompt)).thenReturn(Flux.just(response));
        Flux<ChatResponse> stream = model.stream(prompt);
        verifyNoInteractions(provider);
        assertSame(response, stream.blockLast());
        when(access.platformModelIds()).thenReturn(Set.of());
        assertThrows(IllegalStateException.class, stream::blockLast);
        verify(provider, times(1)).stream(prompt);
    }

    @Test public void authorizationStorageFailureNeverCallsProvider() {
        ChatModel provider = mock(ChatModel.class);
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        var model = new PlatformAuthorizedChatModel(provider, "platform-model", access);
        when(access.platformModelIds()).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> model.call(new Prompt("fixture")));
        assertThrows(IllegalStateException.class, () -> model.stream(new Prompt("fixture")).blockLast());
        verifyNoInteractions(provider);
    }
}
