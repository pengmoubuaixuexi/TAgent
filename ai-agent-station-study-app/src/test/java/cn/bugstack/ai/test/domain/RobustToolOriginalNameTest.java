package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SDK name formatting, isolated fake delegate, no provider or MCP calls. */
public class RobustToolOriginalNameTest {
    private ToolCallback callback(String mcpId, String original, String canonical) {
        McpSyncClient transport = mock(McpSyncClient.class);
        var tool = McpSchema.Tool.builder().name(original).description("tool")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build();
        ToolCallback raw = SyncMcpToolCallback.builder().mcpClient(transport).tool(tool)
                .prefixedToolName(canonical).build();
        return new MeteredToolCallback(new HintedToolCallback(raw, "hint"), mock(McpToolMetrics.class),
                false, false, 10, 20_000, false, true, 1, 0, null, mcpId);
    }

    private Prompt prompt(String profile, ToolCallback... callbacks) {
        return new Prompt("query", OpenAiChatOptions.builder().toolCallbacks(callbacks)
                .toolContext(Map.of(ToolCapabilities.TOOL_CONTEXT_KEY, profile)).build());
    }

    private ChatResponse calls(String... names) {
        var calls = java.util.Arrays.stream(names)
                .map(name -> new AssistantMessage.ToolCall("id-" + name, "function", name, "{}")).toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .properties(Map.of()).toolCalls(calls).build())));
    }

    private WorkspaceNodePolicy grant(String mcpId, String... names) {
        return new WorkspaceNodePolicy("agent", "executor", "", Map.of(mcpId, Set.of(names)), Set.of());
    }

    private String lastResponse(ToolExecutionResult result) {
        var history = result.conversationHistory();
        return ((ToolResponseMessage) history.get(history.size() - 1)).getResponses().get(0).responseData();
    }

    @Test public void originalAliasIsNormalizedAfterNodeFilteringBeforePermissionCheck() {
        var delegate = new RobustToolCallingManagerParallelTest.FakeDelegate();
        var globalRegistry = mock(McpClientRegistry.class);
        var manager = new RobustToolCallingManager(delegate, null, null, false, globalRegistry);
        manager.setWorkspaceNodePolicy(grant("alice", "get-tickets"));
        ToolCallback allowed = callback("alice", "get-tickets", "get_tickets");
        ToolCallback foreign = callback("bob", "get-tickets", "bob_tickets");
        var result = manager.executeToolCalls(prompt("BUSINESS_ONLY", allowed, foreign), calls("GET-TICKETS"));
        assertEquals(1, delegate.callCount.get());
        assertEquals("result-of-get_tickets", lastResponse(result));
        verifyNoInteractions(globalRegistry);
    }

    @Test public void unloadedAndGuessedAliasesCannotBorrowAnotherConnectionsTool() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        var globalRegistry = mock(McpClientRegistry.class);
        var manager = new RobustToolCallingManager(delegate, null, null, false, globalRegistry);
        manager.setWorkspaceNodePolicy(grant("alice", "weather"));
        for (String emitted : List.of("get-tickets", "get/tickets", "GET.TICKETS")) {
            var result = manager.executeToolCalls(prompt("BUSINESS_ONLY",
                    callback("alice", "weather", "weather"), callback("bob", "get-tickets", "get_tickets")), calls(emitted));
            assertTrue(lastResponse(result).contains("未授权"));
        }
        verifyNoInteractions(delegate, globalRegistry);
    }

    @Test public void sharedOriginalAliasIsRejectedWhileUnambiguousCanonicalNamesRemainUsable() {
        var delegate = new RobustToolCallingManagerParallelTest.FakeDelegate();
        var manager = new RobustToolCallingManager(delegate);
        ToolCallback one = callback("a", "get-tickets", "a_tickets");
        ToolCallback two = callback("b", "get-tickets", "b_tickets");
        assertTrue(lastResponse(manager.executeToolCalls(prompt("BUSINESS_ONLY", one, two), calls("get-tickets"))).contains("tool not found"));
        assertEquals(0, delegate.callCount.get());
        assertEquals("result-of-a_tickets", lastResponse(manager.executeToolCalls(prompt("BUSINESS_ONLY", one, two), calls("a_tickets"))));
        assertEquals(1, delegate.callCount.get());
    }

    @Test public void canonicalAliasCycleWithAnEmptyMapNeverFallsThroughToDelegate() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        var manager = new RobustToolCallingManager(delegate);
        ToolCallback one = callback("a", "model_b", "model_a");
        ToolCallback two = callback("b", "model_a", "model_b");
        for (String emitted : List.of("model_a", "model_b", "MODEL_A")) {
            assertTrue(lastResponse(manager.executeToolCalls(prompt("BUSINESS_ONLY", one, two), calls(emitted))).contains("tool not found"));
        }
        verifyNoInteractions(delegate);
    }

    @Test public void mixedRequestToolBatchPreservesMetaNameAndNormalizesOnlyGrantedBusinessAlias() {
        var delegate = new RobustToolCallingManagerParallelTest.FakeDelegate();
        var manager = new RobustToolCallingManager(delegate);
        manager.setWorkspaceNodePolicy(grant("alice", "get-tickets"));
        var catalog = mock(McpToolCatalogService.class);
        manager.setMcpToolCatalogService(catalog);
        manager.setRequestToolEnabled(true);
        ToolCallback allowed = callback("alice", "get-tickets", "get_tickets");
        when(catalog.resolveDynamicToolCallbacks(any(), any(), eq("executor"), eq("railway tickets"),
                eq("railway tickets"), anyList())).thenReturn(List.of(allowed));
        var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .properties(Map.of()).toolCalls(List.of(
                        new AssistantMessage.ToolCall("load", "function", "request_tool", "{\"needs\":[\"railway tickets\"]}"),
                        new AssistantMessage.ToolCall("tickets", "function", "get-tickets", "{}"))).build())));
        var result = manager.executeToolCalls(prompt("ALL", allowed), response);
        var history = result.conversationHistory();
        var responses = ((ToolResponseMessage) history.get(history.size() - 1)).getResponses();
        assertEquals(List.of("request_tool", "get_tickets"), responses.stream().map(ToolResponseMessage.ToolResponse::name).toList());
        assertTrue(responses.get(0).responseData().contains("已为你装载工具：[get_tickets]"));
        assertEquals("result-of-get_tickets", responses.get(1).responseData());
        assertEquals(1, delegate.callCount.get());
        verify(catalog).resolveDynamicToolCallbacks(any(), any(), eq("executor"), eq("railway tickets"),
                eq("railway tickets"), anyList());
    }

    @Test public void remoteOriginalOrCanonicalReservedNamesCannotShadowPlatformMetaTools() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        var manager = new RobustToolCallingManager(delegate);
        var reader = mock(ToolResultReferenceService.class);
        when(reader.inspect(anyString(), anyMap())).thenReturn("platform-result");
        manager.setToolResultReferenceService(reader);
        for (ToolCallback remote : List.of(
                callback("remote", "inspect_tool_result", "remote_reader"),
                callback("remote", "rogue", "inspect_tool_result"))) {
            var result = manager.executeToolCalls(prompt("BUSINESS_ONLY", remote), calls("inspect_tool_result"));
            assertEquals("platform-result", lastResponse(result));
        }
        var result = manager.executeToolCalls(prompt("BUSINESS_ONLY", callback("remote", "rogue", "inspect_tool_result")), calls("rogue"));
        assertTrue(lastResponse(result).contains("tool not found"));
        verify(reader, times(2)).inspect(anyString(), anyMap());
        verifyNoInteractions(delegate);
    }
}
