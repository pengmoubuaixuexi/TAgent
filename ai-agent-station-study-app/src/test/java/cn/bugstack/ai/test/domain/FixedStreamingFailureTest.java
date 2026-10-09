package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.fixed.FixedAgentExecuteStrategy;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FixedStreamingFailureTest {
    @Test
    public void toolStartedTimeoutIsNotReplayedOrDisplayedAsSuccess() throws Exception {
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ChatClientResponse> stream = Flux.deferContextual(context -> {
            subscriptions.incrementAndGet();
            ((StreamingActivityTracker.Activity) context.get(StreamingActivityTracker.CONTEXT_KEY)).markToolActivity();
            return Flux.error(new TimeoutException("fixed provider idle"));
        });
        verifyFailedRun(stream, "fixed provider idle");
        assertEquals(1, subscriptions.get());
    }

    @Test
    public void emptyCompletionIsNotDisplayedAsSuccess() throws Exception {
        verifyFailedRun(Flux.empty(), "without an answer");
    }

    @SuppressWarnings("unchecked")
    private void verifyFailedRun(Flux<ChatClientResponse> stream, String reason) throws Exception {
        FixedAgentExecuteStrategy strategy = new FixedAgentExecuteStrategy();
        IAgentRepository repository = mock(IAgentRepository.class);
        when(repository.queryAiAgentClientsByAgentId("agent")).thenReturn(List.of(
                AiAgentClientFlowConfigVO.builder().clientId("client").build()));
        ApplicationContext application = mock(ApplicationContext.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.StreamResponseSpec response = mock(ChatClient.StreamResponseSpec.class);
        when(application.getBean(anyString())).thenReturn(client);
        when(client.prompt(anyString())).thenReturn(spec);
        when(spec.stream()).thenReturn(response);
        when(response.chatClientResponse()).thenReturn(stream);
        ReflectionTestUtils.setField(strategy, "repository", repository);
        ReflectionTestUtils.setField(strategy, "applicationContext", application);
        ReflectionTestUtils.setField(strategy, "longTermMemoryTurnSnapshot", mock(LongTermMemoryTurnSnapshot.class));
        ReflectionTestUtils.setField(strategy, "tokenStreamingEnabled", true);
        ReflectionTestUtils.setField(strategy, "streamingRetryMaxAttempts", 3);
        ReflectionTestUtils.setField(strategy, "streamingIdleTimeoutSeconds", 60);
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        var previous = MDC.getCopyOfContextMap();
        try {
            RuntimeException error = assertThrows(RuntimeException.class, () -> strategy.execute(
                    ExecuteCommandEntity.builder().aiAgentId("agent").sessionId("session-fixed")
                            .userId("user").runId("run-fixed").message("test").build(), emitter));
            assertTrue(error.toString(), error.getMessage().contains(reason));
            verify(emitter).send(org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String event
                    && event.contains("event: step_end") && event.contains("\"status\":\"failed\"")));
            verify(emitter, never()).send(org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String event
                    && event.contains("event: step_end") && event.contains("\"status\":\"completed\"")));
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
