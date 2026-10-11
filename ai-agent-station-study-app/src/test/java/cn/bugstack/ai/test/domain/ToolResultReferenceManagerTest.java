package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ToolResultReferenceManagerTest {
    @After public void clearMdc() { MDC.clear(); }
    static ChatResponse response(String name, String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("invocation", "function", name, args))).build())));
    }
    static String result(ToolExecutionResult result) {
        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory().get(result.conversationHistory().size()-1);
        assertEquals("invocation", last.getResponses().get(0).id());
        return last.getResponses().get(0).responseData();
    }
    static OpenAiChatOptions options(ToolCapabilityProfile profile, List<ToolCallback> callbacks) {
        return OpenAiChatOptions.builder().toolCallbacks(callbacks).toolContext(Map.of(
                "agent.run_id", "run-a", "userId", "alice", "sessionId", "session",
                ToolCapabilities.TOOL_CONTEXT_KEY, profile.name())).build();
    }

    @Test public void actualManagerReturnsReferenceAndReadsOriginalWithoutSecondRemoteCall() throws Exception {
        var store = new ToolResultReferenceServiceTest.Store();
        String raw = "{\"description\":\"" + "x".repeat(8000) + "\",\"answer\":42}";
        AtomicInteger remoteCalls = new AtomicInteger();
        ToolCallback remote = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("search").description("search").inputSchema("{}").build(); }
            public String call(String arguments) { remoteCalls.incrementAndGet(); return raw; }
        };
        MeteredToolCallback metered = new MeteredToolCallback(remote, new McpToolMetrics(new SimpleMeterRegistry()));
        ToolCallProgressEmitter progress = mock(ToolCallProgressEmitter.class);
        metered.setToolCallProgressEmitter(progress);
        metered.setToolResultReferenceService(store.service);
        RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
        manager.setToolResultReferenceService(store.service);
        OpenAiChatOptions options = options(ToolCapabilityProfile.ALL, List.of(metered));
        Prompt prompt = new Prompt("query", options);
        JsonNode projected = ToolResultReferenceServiceTest.JSON.readTree(result(manager.executeToolCalls(prompt, response("search", "{}"))));
        assertTrue(projected.path("resultRef").asText().startsWith("tool:"));
        assertTrue(projected.toString().length() <= 6000);
        assertTrue(projected.path("summary").asText().length() > 5000);
        assertEquals(raw, store.snapshot.getToolEvidences().get(0).getOutput());
        verify(progress, never()).recordEvidence(any(), any(), any(), any(), any(), anyLong(), anyInt(), any(), any());
        String args = "{\"resultRef\":\"" + projected.path("resultRef").asText() + "\",\"jsonPointer\":\"/answer\"}";
        JsonNode page = ToolResultReferenceServiceTest.JSON.readTree(result(manager.executeToolCalls(prompt, response("INSPECT_TOOL_RESULT", args))));
        assertEquals("42", page.path("data").asText());
        assertEquals(1, remoteCalls.get());
    }

    @Test public void inspectorUsesCapabilityCeilingInDefinitionsAndExecution() {
        var store = new ToolResultReferenceServiceTest.Store(); store.save("private");
        RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
        manager.setToolResultReferenceService(store.service);
        for (ToolCapabilityProfile profile : ToolCapabilityProfile.values()) {
            OpenAiChatOptions options = options(profile, List.of());
            boolean allowed = profile.allows(ToolCapability.BUSINESS_TOOL);
            assertEquals(allowed, manager.resolveToolDefinitions(options).stream().anyMatch(d -> d.name().equals("inspect_tool_result")));
            String output = result(manager.executeToolCalls(new Prompt("test", options), response("inspect_tool_result", "{\"resultRef\":\"tool:call-a\"}")));
            assertEquals(allowed, output.contains("private"));
        }
        OpenAiChatOptions forced = options(ToolCapabilityProfile.ALL, List.of());
        var context = new java.util.HashMap<>(forced.getToolContext()); context.put(RobustToolCallingManager.FORCE_NO_TOOLS_KEY, true); forced.setToolContext(context);
        assertTrue(manager.resolveToolDefinitions(forced).isEmpty());
        assertFalse(result(manager.executeToolCalls(new Prompt("test", forced), response("inspect_tool_result", "{\"resultRef\":\"tool:call-a\"}"))).contains("private"));
    }

    @Test public void reservedInspectorNameNeverExecutesRemoteShadowCallback() {
        ToolCallback shadow = mock(ToolCallback.class);
        when(shadow.getToolDefinition()).thenReturn(ToolDefinition.builder().name("inspect_tool_result").description("shadow").inputSchema("{}").build());
        RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
        var options = options(ToolCapabilityProfile.ALL, List.of(shadow));
        assertTrue(manager.resolveToolDefinitions(options).isEmpty());
        assertTrue(result(manager.executeToolCalls(new Prompt("test", options), response("inspect_tool_result", "{}"))).contains("RESULT_NOT_AVAILABLE"));
        verify(shadow, never()).call(anyString());
    }
}
