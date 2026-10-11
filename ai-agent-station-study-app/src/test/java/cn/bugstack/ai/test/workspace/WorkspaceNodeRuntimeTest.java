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
    @Test public void originalToolSelectionAcceptsItsCanonicalCallbackOnlyOnTheSelectedConnection() {
        var selected=new WorkspaceNodePolicy("a1","c1","",Map.of("mine",Set.of("get-tickets")),Set.of());
        var own=callback("mine","get_tickets");when(own.getOriginalToolName()).thenReturn("get-tickets");
        var another=callback("other-owner","get_tickets");when(another.getOriginalToolName()).thenReturn("get-tickets");
        var unproven=callback("mine","get_tickets");
        assertTrue(selected.allows(own));assertFalse(selected.allows(another));assertFalse(selected.allows(unproven));
        assertFalse(selected.allows("mine",another));
        assertEquals(List.of(own),selected.filter(List.of(own,another,unproven)));
    }
    @Test public void hintAndMeterWrappersPreserveTheSdkOriginalNameForSavedSelections() {
        var raw=mock(org.springframework.ai.mcp.SyncMcpToolCallback.class);
        when(raw.getOriginalToolName()).thenReturn("get-tickets");
        when(raw.getToolDefinition()).thenReturn(ToolDefinition.builder().name("get_tickets")
                .description("查询车票").inputSchema("{}").build());
        var hint=new cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback(raw,"优先查询余票");
        var metered=new MeteredToolCallback(hint,
                mock(cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics.class),
                false,false,10,10000,true,true,1,1,null,"mine");
        var selected=new WorkspaceNodePolicy("a1","c1","",Map.of("mine",Set.of("get-tickets")),Set.of());
        assertEquals("get-tickets",metered.getOriginalToolName());
        assertTrue(selected.allows(metered));
        assertTrue(selected.allows("mine",hint));
        assertEquals("get_tickets",metered.getToolDefinition().name());
        verify(raw,never()).call(anyString());
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

    @Test public void analysisAndPlanningMaySeeExecutorMetadataButNeverAdvertiseOrInvokeBusinessTools() {
        var business=callback("mine","maps_weather");
        var delegate=mock(ToolCallingManager.class);
        var manager=new RobustToolCallingManager(delegate);
        manager.setWorkspaceNodePolicy(new WorkspaceNodePolicy("a1","analysis","",Map.of(),Set.of()));
        manager.setRequestToolEnabled(true);
        var catalog=mock(McpToolCatalogService.class);
        manager.setMcpToolCatalogService(catalog);
        var gate=mock(cn.bugstack.ai.domain.agent.service.security.UserInputGate.class);
        when(gate.isEnabled()).thenReturn(true);when(gate.remainingFor("session")).thenReturn(1);
        manager.setUserInputGate(gate);
        try {
            MDC.put("sessionId","session");
            for (var profile:List.of(
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_STEP1_TOOL_ANALYSIS,
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_STEP2_PLANNING,
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.AUTO_STEP1_ANALYSIS)) {
                var options=OpenAiChatOptions.builder().toolCallbacks(business)
                        .toolContext(Map.of("agent.tool_capabilities",profile.name())).build();
                assertEquals(profile == cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.AUTO_STEP1_ANALYSIS
                                ? List.of("ask_user","request_tool") : List.of("ask_user"), manager.resolveToolDefinitions(options)
                        .stream().map(ToolDefinition::name).toList());
                var response=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1","function","maps_weather","{}"))).build())));
                assertNotNull(manager.executeToolCalls(new Prompt("Executor capability: maps_weather",options),response));
                if (!profile.allows(cn.bugstack.ai.domain.agent.service.execute.common.ToolCapability.REQUEST_TOOL)) {
                    var discoveryResponse=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("discovery-1","function","request_tool",
                                    "{\"needs\":\"maps_weather\"}"))).build())));
                    assertNotNull(manager.executeToolCalls(new Prompt("Executor capability: maps_weather",options),discoveryResponse));
                }
            }
            verify(delegate,never()).executeToolCalls(any(),any());verify(business,never()).call(anyString());
            verifyNoInteractions(catalog);
        } finally {MDC.clear();}
    }
}
