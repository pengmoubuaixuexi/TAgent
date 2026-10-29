package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.fixed.FixedAgentExecuteStrategy;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FixedSteerIsolationTest {
    private static final String SESSION = "same-session";

    @Test
    public void otherStrategyBroadcastCannotSteerLaterFixedRun() throws Exception {
        Fixture f = new Fixture();
        when(f.response.chatClientResponse()).thenReturn(Flux.just(answer("normal answer")));

        // An Auto or Flow run broadcasts to all strategies while Fixed is inactive.
        f.strategy.steerExecute(SESSION, "old unrelated instruction");
        f.run("run-1");
        verify(f.client, never()).prompt();
        verify(f.emitter).send(org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String && ((String) value).startsWith("data: ") && ((String) value).contains("normal answer")));
    }

    @Test
    public void activeFixedSteerRerunsAndDoesNotLeakIntoNextRun() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch subscribed = new CountDownLatch(1);
        when(f.response.chatClientResponse()).thenReturn(
                Flux.<ChatClientResponse>never().doOnSubscribe(s -> subscribed.countDown()),
                Flux.just(answer("steered answer")),
                Flux.just(answer("next answer")));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> {
                try { f.run("run-1"); } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertTrue("Fixed stream did not start", subscribed.await(5, TimeUnit.SECONDS));
            f.strategy.steerExecute(SESSION, "new constraint");
            running.get(10, TimeUnit.SECONDS);

            verify(f.client, times(1)).prompt();
            verify(f.spec).user(org.mockito.ArgumentMatchers.<String>argThat(prompt -> prompt.contains("new constraint")));
            verify(f.emitter).send(org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String && ((String) value).startsWith("data: ") && ((String) value).contains("steered answer")));

            // Late broadcasts after cleanup cannot be stored for the next run.
            f.strategy.steerExecute(SESSION, "late unrelated instruction");
            f.run("run-2");
            verify(f.client, times(1)).prompt();
            verify(f.emitter).send(org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String && ((String) value).startsWith("data: ") && ((String) value).contains("next answer")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void steerBeforeFirstStreamIsNotLost() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(f.application.getBean(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release Fixed run");
            return f.client;
        });
        when(f.response.chatClientResponse()).thenReturn(Flux.never(), Flux.just(answer("early steer answer")));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> {
                try { f.run("run-early"); } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.strategy.steerExecute(SESSION, "early constraint");
            proceed.countDown();
            running.get(10, TimeUnit.SECONDS);
            verify(f.spec).user(org.mockito.ArgumentMatchers.<String>argThat(prompt -> prompt.contains("early constraint")));
            verify(f.emitter).send(org.mockito.ArgumentMatchers.<Object>argThat(value -> value instanceof String && ((String) value).startsWith("data: ") && ((String) value).contains("early steer answer")));
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    private static ChatClientResponse answer(String text) {
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), Map.of());
    }

    private static final class Fixture {
        final FixedAgentExecuteStrategy strategy = new FixedAgentExecuteStrategy();
        final ChatClient client = mock(ChatClient.class);
        final ApplicationContext application = mock(ApplicationContext.class);
        final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        final ChatClient.StreamResponseSpec response = mock(ChatClient.StreamResponseSpec.class);
        final ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);

        Fixture() {
            IAgentRepository repository = mock(IAgentRepository.class);
            when(repository.queryAiAgentClientsByAgentId("agent")).thenReturn(List.of(
                    AiAgentClientFlowConfigVO.builder().clientId("client").build()));
            when(application.getBean(anyString())).thenReturn(client);
            when(client.prompt(anyString())).thenReturn(spec);
            when(client.prompt()).thenReturn(spec);
            when(spec.stream()).thenReturn(response);
            ReflectionTestUtils.setField(strategy, "repository", repository);
            ReflectionTestUtils.setField(strategy, "applicationContext", application);
            ReflectionTestUtils.setField(strategy, "longTermMemoryTurnSnapshot", mock(LongTermMemoryTurnSnapshot.class));
            ReflectionTestUtils.setField(strategy, "llmObservationRecorder", mock(LlmObservationRecorder.class));
            ReflectionTestUtils.setField(strategy, "tokenStreamingEnabled", true);
            ReflectionTestUtils.setField(strategy, "streamingRetryMaxAttempts", 3);
            ReflectionTestUtils.setField(strategy, "streamingIdleTimeoutSeconds", 60);
        }

        void run(String runId) throws Exception {
            strategy.execute(ExecuteCommandEntity.builder().aiAgentId("agent").sessionId(SESSION)
                    .userId("user").runId(runId).message("question").build(), emitter);
        }
    }
}
