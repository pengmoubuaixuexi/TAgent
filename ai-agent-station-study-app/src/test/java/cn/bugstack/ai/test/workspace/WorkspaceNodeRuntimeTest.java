package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Model output, run-level callback injection and a sibling MCP cannot widen a node's grant. */
public class WorkspaceNodeRuntimeTest {
    @Test public void lazyPolicyKeepsPermissionButSkipsConnectionDuringAssembly() {
        String caps="""
                {"workspaceNode":{"agentId":"a1","clientId":"c1","mcpBindings":[
                {"mcpId":"mine","allTools":true,"toolNames":[],"loadMode":"ON_DEMAND"}]}}
                """;
        var policy=WorkspaceNodePolicy.fromCapabilities(caps);
        assertTrue(policy.allows("mine","search"));assertFalse(policy.eager("mine"));
        var lazy=cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO.builder().capabilitiesJson(caps).toolMcpIds(List.of("mine")).build();
        assertFalse(cn.bugstack.ai.domain.agent.service.armory.node.AiClientToolMcpNode.requiresEagerConnection("mine",List.of(lazy)));
        var legacy=cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO.builder().toolMcpIds(List.of("mine")).build();
        assertTrue(cn.bugstack.ai.domain.agent.service.armory.node.AiClientToolMcpNode.requiresEagerConnection("mine",List.of(lazy,legacy)));
    }
    private WorkspaceNodePolicy policy() {
        return new WorkspaceNodePolicy("a1","c1","Task only",Map.of("mine",Set.of("search")),Set.of());
    }
    private MeteredToolCallback callback(String mcp,String name) {
        var callback=mock(MeteredToolCallback.class);
        when(callback.getMcpId()).thenReturn(mcp);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
        return callback;
    }
    @Test public void definitionsIntersectNodeConnectionAndToolSelection() {
        var own=callback("mine","search");var sibling=callback("sibling","delete");var sameName=callback("other-owner","search");
        ToolCallingManager delegate=mock(ToolCallingManager.class);
        when(delegate.resolveToolDefinitions(any())).thenAnswer(call->{
            var options=(org.springframework.ai.model.tool.ToolCallingChatOptions)call.getArgument(0);
            return options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition).toList();
        });
        var manager=new RobustToolCallingManager(delegate);manager.setWorkspaceNodePolicy(policy());
        var options=OpenAiChatOptions.builder().toolCallbacks(own,sibling,sameName).toolNames("delete")
                .toolContext(Map.of("agent.tool_capabilities","BUSINESS_ONLY")).build();
        assertEquals(List.of("search"),manager.resolveToolDefinitions(options).stream().map(ToolDefinition::name).toList());
        assertTrue(options.getToolNames().isEmpty());assertEquals(List.of(own),options.getToolCallbacks());
    }
    @Test public void injectedCallbacksAndHallucinatedCallsAreDeniedBeforeDelegate() {
        var delegate=mock(ToolCallingManager.class);var manager=new RobustToolCallingManager(delegate);
        manager.setWorkspaceNodePolicy(policy());
        var options=OpenAiChatOptions.builder().toolCallbacks(callback("mine","search"),callback("sibling","delete"))
                .toolContext(Map.of("agent.tool_capabilities","BUSINESS_ONLY")).build();
        var response=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1","function","delete","{}"))).build())));
        var result=manager.executeToolCalls(new Prompt("test",options),response);
        assertNotNull(result);verify(delegate,never()).executeToolCalls(any(),any());
        assertEquals(List.of("search"),options.getToolCallbacks().stream().map(c->c.getToolDefinition().name()).toList());
    }
    @Test public void identicalToolNameOnDifferentConnectionCannotImpersonateSelectedTool() {
        assertFalse(policy().allows(callback("other-owner","search")));
        assertFalse(policy().allows(callback("mine","delete")));
        assertTrue(policy().allows(callback("mine","search")));
        assertNull(WorkspaceNodePolicy.fromCapabilities("{\"imageInput\":true}"));
        assertThrows(IllegalStateException.class,()->WorkspaceNodePolicy.fromCapabilities("{\"workspaceNode\":{}}"));
    }
    @Test public void emptyNodeCannotBorrowAnotherNodesDynamicLeaseOrCatalog() {
        var repository=mock(IAgentRepository.class);var access=mock(IWorkspaceAccessRepository.class);
        when(repository.queryWorkspaceNodePolicy("c1")).thenReturn(new WorkspaceNodePolicy("a1","c1","",Map.of(),Set.of()));
        var service=new McpToolCatalogService();ReflectionTestUtils.setField(service,"repository",repository);
        ReflectionTestUtils.setField(service,"workspaceAccess",access);
        try {
            MDC.put("agentId","a1");MDC.put("userId","u1");
            assertTrue(service.resolveDynamicToolCallbacks("run1","session1","c1","need search","query",List.of()).isEmpty());
            verify(repository,never()).queryEnabledMcpToolCatalog();verify(repository,never()).queryEnabledAiClientToolMcpVOList();
            verifyNoInteractions(access);
        } finally {MDC.clear();}
    }
}
