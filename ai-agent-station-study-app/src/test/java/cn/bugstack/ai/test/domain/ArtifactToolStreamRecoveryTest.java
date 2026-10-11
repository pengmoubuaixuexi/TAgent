package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.RunArtifactService;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk.ChunkChoice;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.Role;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real Spring AI recursive stream loop, without HTTP or paid model calls. */
public class ArtifactToolStreamRecoveryTest {
    private static final String BROKEN = "{\"title\":\"Trip\",\"format\":\"MARKDOWN\",\"content\": # Trip";
    private static final String CODE = "{\"title\":\"Trip code\",\"format\":\"CODE\",\"content\":{\"filename\":\"trip.py\",\"language\":\"python\",\"code\":\"print('Beijing')\\n\"}}";

    @Test public void malformedArgumentsThenCorrectedCodeAreSavedOnceInActualStreamLoop() {
        var fixture = new Fixture();
        when(fixture.service.create(eq("corrected"), eq(CODE), anyMap()))
                .thenReturn("{\"ok\":true,\"artifactId\":\"saved-code\",\"format\":\"CODE\"}");
        when(fixture.api.chatCompletionStream(any(ChatCompletionRequest.class), any())).thenAnswer(invocation -> {
            ChatCompletionRequest request = invocation.getArgument(0);
            fixture.requests.add(request);
            return switch (fixture.requests.size()) {
                case 1 -> toolChunk("broken", BROKEN);
                case 2 -> toolChunk("corrected", CODE);
                case 3 -> textChunk("已保存 trip.py，可从产物卡片下载。");
                default -> Flux.error(new AssertionError("Unexpected recursive model request"));
            };
        });
        String text = fixture.answer();
        assertEquals("已保存 trip.py，可从产物卡片下载。", text);
        assertEquals(3, fixture.requests.size());
        assertTrue("one bounded repair still advertises the tool", offersArtifact(fixture.requests.get(1)));
        assertTrue("successful creation does not disable later distinct artifacts", offersArtifact(fixture.requests.get(2)));
        assertRepairedHistory(fixture.requests.get(1), "broken");
        assertTrue(fixture.requests.get(1).messages().stream().filter(m -> m.role() == Role.TOOL)
                .anyMatch(m -> m.content().contains("INVALID_TOOL_ARGUMENTS") && m.content().contains("\"retryable\":true")));
        assertTrue(fixture.requests.get(2).messages().stream().filter(m -> m.role() == Role.TOOL)
                .anyMatch(m -> m.content().contains("saved-code")));
        verify(fixture.service, times(1)).create(eq("corrected"), eq(CODE), argThat(scope ->
                "owner".equals(scope.get("userId")) && "run-stream".equals(scope.get("agent.run_id"))));
        verify(fixture.service, never()).create(eq("broken"), anyString(), anyMap());
    }

    @Test public void secondMalformedResultRemovesArtifactFromNextRequestAndAllowsTextAnswer() {
        var fixture = new Fixture();
        when(fixture.api.chatCompletionStream(any(ChatCompletionRequest.class), any())).thenAnswer(invocation -> {
            ChatCompletionRequest request = invocation.getArgument(0);
            fixture.requests.add(request);
            return switch (fixture.requests.size()) {
                case 1 -> toolChunk("broken-1", BROKEN);
                case 2 -> toolChunk("broken-2", BROKEN);
                case 3 -> textChunk("文件未保存。以下是完整内容：北京三日游攻略。");
                default -> Flux.error(new AssertionError("Artifact repair must not loop indefinitely"));
            };
        });
        assertEquals("文件未保存。以下是完整内容：北京三日游攻略。", fixture.answer());
        assertEquals(3, fixture.requests.size());
        assertTrue(offersArtifact(fixture.requests.get(0)));
        assertTrue(offersArtifact(fixture.requests.get(1)));
        assertFalse("request-local state must survive Spring AI recursive option handling", offersArtifact(fixture.requests.get(2)));
        assertRepairedHistory(fixture.requests.get(2), "broken-1");
        assertRepairedHistory(fixture.requests.get(2), "broken-2");
        assertTrue(fixture.requests.get(2).messages().stream().filter(m -> m.role() == Role.TOOL)
                .anyMatch(m -> m.content().contains("\"retryable\":false") && m.content().contains("文件未保存")));
        verify(fixture.service, never()).create(anyString(), anyString(), anyMap());
    }

    private static boolean offersArtifact(ChatCompletionRequest request) {
        return request.tools() != null && request.tools().stream()
                .anyMatch(tool -> RunArtifactService.TOOL_NAME.equals(tool.getFunction().getName()));
    }

    private static void assertRepairedHistory(ChatCompletionRequest request, String callId) {
        var call = request.messages().stream().filter(message -> message.role() == Role.ASSISTANT && message.toolCalls() != null)
                .flatMap(message -> message.toolCalls().stream()).filter(item -> callId.equals(item.id())).findFirst();
        assertTrue("tool response must retain matching assistant call ID", call.isPresent());
        assertEquals("invalid JSON must never be sent back to the provider", "{}", call.get().function().arguments());
    }

    private static Flux<ChatCompletionChunk> toolChunk(String id, String arguments) {
        // OpenAiApi merges raw SSE tool argument deltas before returning its tool-call chunk.
        var message = new ChatCompletionMessage("", Role.ASSISTANT, null, null,
                List.of(new ToolCall(0, id, "function", new ChatCompletionFunction("create_artifact", arguments))),
                null, null, null, null);
        return Flux.just(new ChatCompletionChunk("completion-"+id,
                List.of(new ChunkChoice(ChatCompletionFinishReason.TOOL_CALLS, 0, message, null)),
                1L, "fixture-model", null, null, "chat.completion.chunk", null));
    }

    private static Flux<ChatCompletionChunk> textChunk(String text) {
        return Flux.just(new ChatCompletionChunk("completion-final",
                List.of(new ChunkChoice(ChatCompletionFinishReason.STOP, 0, new ChatCompletionMessage(text, Role.ASSISTANT), null)),
                1L, "fixture-model", null, null, "chat.completion.chunk", null));
    }

    private static final class Fixture {
        final OpenAiApi api = mock(OpenAiApi.class);
        final RunArtifactService service = mock(RunArtifactService.class);
        final List<ChatCompletionRequest> requests = new CopyOnWriteArrayList<>();
        final OpenAiChatModel model;
        Fixture() {
            when(service.definition()).thenReturn(ToolDefinition.builder().name(RunArtifactService.TOOL_NAME)
                    .description("Save a document or code file")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"format\":{\"type\":\"string\"},\"content\":{\"type\":\"object\"}},\"required\":[\"title\",\"format\",\"content\"]}").build());
            var manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
            manager.setRunArtifactService(service);
            model = OpenAiChatModel.builder().openAiApi(api).toolCallingManager(manager)
                    .defaultOptions(OpenAiChatOptions.builder().model("fixture-model").build())
                    .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build()).build();
        }
        String answer() {
            var options = OpenAiChatOptions.builder().toolContext(Map.of(
                    "userId", "owner", "agent.run_id", "run-stream", "sessionId", "session",
                    ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.ARTIFACT_ONLY.name())).build();
            List<ChatResponse> responses = model.stream(new Prompt("Create the requested artifact", options))
                    .collectList().block(Duration.ofSeconds(10));
            assertNotNull(responses);
            return responses.stream().filter(response -> response.getResult() != null && response.getResult().getOutput().getText() != null)
                    .map(response -> response.getResult().getOutput().getText()).reduce("", String::concat);
        }
    }
}
