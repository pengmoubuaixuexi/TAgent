package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step4ExecuteStepsNode;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.Step2PrecisionExecutorNode;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual Flow and Auto wrappers with local streams, without an LLM. */
public class StreamingFailurePropagationTest {
    @Test
    public void toolStartedTimeoutCannotReplayTheStepInEitherMode() {
        for (boolean flow : List.of(true, false)) {
            AtomicInteger subscriptions = new AtomicInteger();
            AtomicReference<StreamingActivityTracker.Activity> captured = new AtomicReference<>();
            TimeoutException failure = new TimeoutException("provider silent after tool call");
            Flux<ChatClientResponse> stream = Flux.deferContextual(context -> {
                subscriptions.incrementAndGet();
                StreamingActivityTracker.Activity activity = context.get(StreamingActivityTracker.CONTEXT_KEY);
                captured.set(activity);
                activity.markToolActivity();
                return Flux.error(failure);
            });
            RuntimeException error = assertThrows(RuntimeException.class, () -> invoke(flow, stream));
            assertTrue(error.getMessage().contains("provider silent after tool call"));
            assertTrue(hasCause(error, failure));
            assertEquals(1, subscriptions.get());
            assertTrue(captured.get().isCancelled());
        }
    }

    @Test
    public void partialTextFollowedByTimeoutIsFailureRatherThanCompletedAnswer() {
        ChatClientResponse partial = new ChatClientResponse(new ChatResponse(List.of(
                new Generation(new AssistantMessage("partial answer ".repeat(25))))), java.util.Map.of());
        for (boolean flow : List.of(true, false)) {
            TimeoutException failure = new TimeoutException("idle after partial output");
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> invoke(flow, Flux.concat(Flux.just(partial), Flux.error(failure))));
            assertTrue(hasCause(error, failure));
        }
    }

    @Test
    public void cancellationAndEmptyCompletionNeverBecomeSuccessfulSteps() {
        for (boolean flow : List.of(true, false)) {
            CancellationException cancel = new CancellationException("user cancelled");
            assertSame(cancel, assertThrows(CancellationException.class, () -> invoke(flow, Flux.error(cancel))));
            RuntimeException error = assertThrows(RuntimeException.class, () -> invoke(flow, Flux.empty()));
            assertTrue(error.getMessage().contains("without an answer"));
        }
    }

    private Object invoke(boolean flow, Flux<ChatClientResponse> stream) {
        Object node = flow ? new Step4ExecuteStepsNode() : new Step2PrecisionExecutorNode();
        Object context = flow ? new DefaultFlowAgentExecuteStrategyFactory.DynamicContext()
                : new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        ReflectionTestUtils.setField(node, "llmObservationRecorder", mock(LlmObservationRecorder.class));
        ReflectionTestUtils.setField(node, "streamingRetryMaxAttempts", 3);
        ReflectionTestUtils.setField(node, "streamingIdleTimeoutSeconds", 60);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.stream().chatClientResponse()).thenReturn(stream);
        return ReflectionTestUtils.invokeMethod(node, "callChatClientWithTokenStreaming", spec, context, "step4", "test");
    }

    private boolean hasCause(Throwable error, Throwable expected) {
        for (Throwable t = error; t != null; t = t.getCause()) if (t == expected) return true;
        return false;
    }
}
