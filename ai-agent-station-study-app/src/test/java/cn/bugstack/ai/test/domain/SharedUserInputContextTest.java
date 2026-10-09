package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.event.IRunEventStore;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.prompt.RunUserInputAdvisor;
import cn.bugstack.ai.domain.agent.service.security.UserInputGate;
import com.alibaba.fastjson.JSON;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Actual gate -> run events -> next node advisor, without a database, model or MCP network call. */
public class SharedUserInputContextTest {
    @After public void clearMdc() { MDC.clear(); }

    @Test
    public void answerFromAnalysisIsVisibleToPlanningExecutionAndQualityWithoutChangingTaskOrOptions() {
        RunEventPublisher events = new RunEventPublisher();
        events.registerRun("run1", "session1");
        UserInputGate gate = gate(events);
        answerOnRequest(events, gate, "2026 年 10 月 12 日出发，预算 3000 元");
        UserInputGate.Result answered = gate.requestUserInput("session1",
                "{\"context\":\"行程需要时间和预算\",\"questions\":[{\"question\":\"什么时候出发，预算多少？\"}]}",
                "MCP 工具分析", "run1", "u1");
        assertEquals(UserInputGate.Status.ANSWERED, answered.status);

        for (String step : List.of("Fixed 后续节点", "Auto 质检", "Flow 规划", "Flow 执行")) {
            ChatClientRequest request = request("run1", "u1", step);
            var out = new RunUserInputAdvisor(events).before(request, null);
            assertSame(request.prompt().getOptions(), out.prompt().getOptions());
            assertEquals("system rules", out.prompt().getInstructions().get(0).getText());
            assertEquals(step, out.prompt().getInstructions().get(2).getText());
            var supplement = out.prompt().getInstructions().get(1);
            assertTrue(supplement instanceof UserMessage);
            assertTrue(supplement.getText().contains("什么时候出发，预算多少？"));
            assertTrue(supplement.getText().contains("2026 年 10 月 12 日出发，预算 3000 元"));
            assertTrue(supplement.getText().contains("不要因为切换节点而再次询问"));
            assertEquals(3, new RunUserInputAdvisor(events).before(out, null).prompt().getInstructions().size());
        }
    }

    @Test
    public void realChatClientCallAndStreamReceiveSharedFactsThroughToolContext() {
        RunEventPublisher events = new RunEventPublisher();
        events.registerRun("run1", "session1");
        publishAnswer(events, "run1", "session1", "a", "u1", "预算 3000 元");
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertTrue(prompt.getInstructions().stream().anyMatch(message -> message.getText().contains("预算 3000 元")));
            return response;
        });
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertTrue(prompt.getInstructions().stream().anyMatch(message -> message.getText().contains("预算 3000 元")));
            return reactor.core.publisher.Flux.just(response);
        });
        ChatClient client = ChatClient.builder(model).defaultAdvisors(new RunUserInputAdvisor(events)).build();
        Map<String, Object> identity = Map.of("agent.run_id", "run1", "userId", "u1");
        assertEquals("ok", client.prompt().user("planning").toolContext(identity).call().content());
        assertEquals(List.of("ok"), client.prompt().user("execution").toolContext(identity).stream().content().collectList().block());
    }

    @Test
    public void scopeRequiresSameUserAndActiveRunAndDoesNotReuseSessionHistoryOrMdc() {
        RunEventPublisher events = new RunEventPublisher();
        events.registerRun("run1", "session1");
        publishAnswer(events, "run1", "session1", "a", "u1", "private answer");
        RunUserInputAdvisor advisor = new RunUserInputAdvisor(events);
        MDC.put("userId", "u1");
        MDC.put("agent.run_id", "run1");
        for (ChatClientRequest request : List.of(request("run1", "u2", "task"),
                request("other", "u1", "task"), request("run1", "", "task"))) {
            assertSame(request, advisor.before(request, null));
        }
        events.finishRun("run1");
        events.registerRun("run2", "session1");
        assertTrue(events.answeredUserInputs("run1", "u1").isEmpty());
        assertTrue(events.answeredUserInputs("run2", "u1").isEmpty());
    }

    @Test
    public void timeoutAndBlankRepliesAreNotSharedAsFacts() {
        RunEventPublisher events = new RunEventPublisher();
        events.registerRun("run1", "session1");
        UserInputGate gate = gate(events);
        ReflectionTestUtils.setField(gate, "timeoutSeconds", 0);
        assertEquals(UserInputGate.Status.TIMEOUT,
                gate.requestUserInput("session1", "{\"questions\":[\"date?\"]}", "analysis", "run1", "u1").status);
        answerOnRequest(events, gate, "  ");
        assertEquals(UserInputGate.Status.ANSWERED,
                gate.requestUserInput("session1", "{\"questions\":[\"date?\"]}", "analysis", "run1", "u1").status);
        assertTrue(events.answeredUserInputs("run1", "u1").isEmpty());
    }

    @Test
    public void sameRunResumeRestoresAuthenticatedAnswersFromExistingSnapshot() {
        RunEventPublisher events = new RunEventPublisher();
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        String payload = JSON.toJSONString(Map.of("inputId", "a", "userId", "u1", "status", "ANSWERED",
                "questions", List.of("预算？"), "answer", "3000 元"));
        RunEventRecord record = RunEventRecord.builder().runId("run1").sessionId("session1")
                .eventType("user_input_result").payloadJson(payload).build();
        when(snapshots.find("run1")).thenReturn(Optional.of(RunSnapshot.builder()
                .runId("run1").userId("u1").timelineEvents(List.of(record)).build()));
        ReflectionTestUtils.setField(events, "runSnapshotService", snapshots);
        events.registerRun("run1", "session1");
        assertEquals("3000 元", events.answeredUserInputs("run1", "u1").get(0).get("answer"));
        assertTrue(events.answeredUserInputs("run1", "u2").isEmpty());
    }

    @Test
    public void concurrentNodeRepliesAreBothVisibleAndDeduplicatedByInputId() throws Exception {
        RunEventPublisher events = new RunEventPublisher();
        events.registerRun("run1", "session1");
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> publishAnswer(events, "run1", "session1", "a", "u1", "date")),
                CompletableFuture.runAsync(() -> publishAnswer(events, "run1", "session1", "b", "u1", "budget")))
                .get(5, TimeUnit.SECONDS);
        publishAnswer(events, "run1", "session1", "a", "u1", "date");
        assertEquals(2, events.answeredUserInputs("run1", "u1").size());
    }

    @Test
    public void lateReplyCannotEnterNewRunAfterSessionIsReused() throws Exception {
        RunEventPublisher events = new RunEventPublisher();
        events.registerRun("run1", "session1");
        UserInputGate gate = gate(events);
        IRunEventStore store = mock(IRunEventStore.class);
        ReflectionTestUtils.setField(events, "eventStore", store);
        CountDownLatch pending = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> inputId = new java.util.concurrent.atomic.AtomicReference<>();
        when(store.append(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if ("user_input_required".equals(invocation.getArgument(2))) {
                inputId.set(JSON.parseObject(invocation.getArgument(3, String.class)).getString("inputId"));
                pending.countDown();
            }
            return null;
        });
        CompletableFuture<UserInputGate.Result> answer = CompletableFuture.supplyAsync(() ->
                gate.requestUserInput("session1", "{\"questions\":[\"date?\"]}", "analysis", "run1", "u1"));
        assertTrue(pending.await(5, TimeUnit.SECONDS));
        events.finishRun("run1");
        events.registerRun("run2", "session1");
        gate.resolveUserInput(inputId.get(), "old task answer");
        assertEquals(UserInputGate.Status.UNAVAILABLE, answer.get(5, TimeUnit.SECONDS).status);
        assertTrue(events.answeredUserInputs("run2", "u1").isEmpty());
    }

    private static UserInputGate gate(RunEventPublisher events) {
        UserInputGate gate = new UserInputGate();
        ReflectionTestUtils.setField(gate, "enabled", true);
        ReflectionTestUtils.setField(gate, "maxAsks", 5);
        ReflectionTestUtils.setField(gate, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(gate, "runEventPublisher", events);
        return gate;
    }

    private static void answerOnRequest(RunEventPublisher events, UserInputGate gate, String answer) {
        IRunEventStore store = mock(IRunEventStore.class);
        ReflectionTestUtils.setField(events, "eventStore", store);
        when(store.append(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if ("user_input_required".equals(invocation.getArgument(2))) {
                gate.resolveUserInput(JSON.parseObject(invocation.getArgument(3, String.class)).getString("inputId"), answer);
            }
            return null;
        });
    }

    private static void publishAnswer(RunEventPublisher events, String run, String session, String input, String user, String answer) {
        events.publish(run, session, "user_input_result", Map.of("inputId", input, "userId", user,
                "status", "ANSWERED", "questions", List.of("question"), "answer", answer));
    }

    private static ChatClientRequest request(String run, String user, String task) {
        var options = OpenAiChatOptions.builder().toolContext(Map.of("agent.run_id", run, "userId", user)).build();
        return ChatClientRequest.builder().prompt(new Prompt(List.of(new SystemMessage("system rules"),
                new UserMessage(task)), options)).context(Map.of("preserved", true)).build();
    }
}
