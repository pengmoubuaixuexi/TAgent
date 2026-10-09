package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import cn.bugstack.ai.domain.agent.service.router.*;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import org.junit.*;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PublicToolDiscoveryTest {
    IAgentRepository repository=mock(IAgentRepository.class);
    IWorkspaceAccessRepository access=mock(IWorkspaceAccessRepository.class);
    IToolVectorStore vectors=mock(IToolVectorStore.class);
    McpClientRegistry registry=mock(McpClientRegistry.class);
    McpToolCatalogService service=new McpToolCatalogService();
    AiMcpToolCatalogVO tool(String id,String name) {
        return AiMcpToolCatalogVO.builder().mcpId(id).mcpName("公共联网搜索").toolName(name)
                .toolDescription("联网搜索网页资料").inputSchemaJson("{}").enabled(1).build();
    }
    @Before public void setup() {
        ReflectionTestUtils.setField(service,"repository",repository);
        ReflectionTestUtils.setField(service,"workspaceAccess",access);
        ReflectionTestUtils.setField(service,"toolVectorStore",vectors);
        ReflectionTestUtils.setField(service,"mcpClientRegistry",registry);
        ReflectionTestUtils.setField(service,"mcpToolMetrics",mock(McpToolMetrics.class));
        MDC.put("userId","alice");MDC.put("agentId","a1");
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of("alice-search"));
        when(access.ownsMcp("alice","alice-search")).thenReturn(true);
        when(access.publicMcpOrigins("alice")).thenReturn(Map.of("alice-search","platform-search"));
    }
    @After public void cleanup(){MDC.clear();}
    @Test public void sharedVectorResultsAreRemappedBeforeRuntimeAndForeignHitsAreRejected() {
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),eq(Set.of("platform-search"))))
                .thenReturn(List.of(tool("platform-search","web-search"),tool("bob-private","delete_files")));
        var result=service.previewMatchedTools("联网搜索","query",Set.of());
        assertEquals(1,result.size());assertEquals("alice-search",result.get(0).getMcpId());
        assertEquals("web-search",result.get(0).getToolName());
        verify(vectors).searchOwned(anyString(),anySet(),anyInt(),eq(Set.of("platform-search")));
    }
    @Test public void missingVectorsFallBackOnlyToAuthorizedCatalogText() {
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(null);
        when(repository.queryMcpToolCatalogByMcpId("platform-search")).thenReturn(List.of(tool("platform-search","web-search")));
        assertEquals("alice-search",service.previewMatchedTools("web-search","query",Set.of()).get(0).getMcpId());
        verify(repository,never()).queryEnabledMcpToolCatalog();
        verify(repository,never()).queryMcpToolCatalogByMcpId("bob-private");
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of());
        assertTrue(service.previewMatchedTools("web-search","query",Set.of()).isEmpty());
    }
    @Test public void flowAnalysisAndPlanningDiscoverUsingExecutorGrantWithoutGivingThemExecutionRights() {
        var analysis=new WorkspaceNodePolicy("a1","analysis","",Map.of(),Set.of());
        var planning=new WorkspaceNodePolicy("a1","planning","",Map.of(),Set.of());
        var executor=new WorkspaceNodePolicy("a1","executor","",Map.of(),Set.of(),Set.of("alice-search"));
        when(repository.queryWorkspaceNodePolicy("analysis")).thenReturn(analysis);
        when(repository.queryWorkspaceNodePolicy("planning")).thenReturn(planning);
        when(repository.queryWorkspaceNodePolicy("executor")).thenReturn(executor);
        var a=mock(AiAgentClientFlowConfigVO.class);when(a.getClientId()).thenReturn("analysis");
        var e=mock(AiAgentClientFlowConfigVO.class);when(e.getClientId()).thenReturn("executor");
        var p=mock(AiAgentClientFlowConfigVO.class);when(p.getClientId()).thenReturn("planning");
        when(repository.queryAiAgentClientFlowConfig("a1")).thenReturn(Map.of("TOOL_MCP_CLIENT",a,"PLANNING_CLIENT",p,"EXECUTOR_CLIENT",e));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(List.of(tool("platform-search","web-search")));
        var raw=mock(ToolCallback.class);when(raw.getToolDefinition()).thenReturn(ToolDefinition.builder().name("web-search").description("search").inputSchema("{}").build());
        when(registry.getCurrentCallback("alice-search","web-search")).thenReturn(raw);
        for (String client:List.of("analysis","planning")) {
            var result=service.resolveDynamicToolCallbacks("run","session",client,"联网搜索","query",List.of());
            assertEquals(1,result.size());assertTrue(executor.allows(result.get(0)));
            assertFalse(analysis.allows(result.get(0)));assertFalse(planning.allows(result.get(0)));
        }
        verify(raw,never()).call(anyString());verify(registry,never()).getCurrentCallback(eq("platform-search"),anyString());
    }
    @Test public void publicPoolDoesNotOverrideExplicitConnectionToolSelection() {
        var p=new WorkspaceNodePolicy("a1","c1","",Map.of("alice-search",Set.of("web-search")),Set.of(),Set.of("alice-search"));
        assertTrue(p.allows("alice-search","web-search"));assertFalse(p.allows("alice-search","delete_files"));
    }
    @Test public void boundConnectionLoadsOnlyRelevantToolAndDoesNotBorrowSiblingConnection() throws Exception {
        var policy=new WorkspaceNodePolicy("a1","c1","",Map.of("alice-search",Set.of()),Set.of("alice-search"),Set.of(),Set.of("alice-search"));
        when(repository.queryWorkspaceNodePolicy("c1")).thenReturn(policy);
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),eq(Set.of("platform-search"))))
                .thenReturn(List.of(tool("platform-search","web-search"),tool("sibling","delete")));
        var raw=raw("web-search","联网搜索");
        when(registry.getCurrentCallback("alice-search","web-search")).thenReturn(raw);
        var found=service.resolveDynamicToolCallbacks("run","session","c1","联网搜索","query",List.of());
        assertEquals(List.of("web-search"),found.stream().map(c->c.getToolDefinition().name()).toList());
        verify(registry,never()).getToolCallbacksForAssembly(anyString(),any());
        verify(raw,never()).call(anyString());
        verify(repository,never()).queryAiClientToolMcpVOByMcpId("sibling");
    }
    @Test public void newPrivateConnectionNeedsNoPriorCatalogOrFrontendDiscovery() throws Exception {
        when(access.publicMcpOrigins("alice")).thenReturn(Map.of());
        when(repository.queryWorkspaceNodePolicy("c1")).thenReturn(new WorkspaceNodePolicy("a1","c1","",
                Map.of("alice-search",Set.of()),Set.of("alice-search"),Set.of(),Set.of("alice-search")));
        var config=AiClientToolMcpVO.builder().mcpId("alice-search").mcpName("我的工具").build();
        when(repository.queryAiClientToolMcpVOByMcpId("alice-search")).thenReturn(config);
        var raw=raw("search","联网搜索网页");var other=raw("weather","天气预报");
        var client=mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(registry.getClient("alice-search")).thenReturn(client);
        when(registry.getToolCallbacksForAssembly("alice-search",client)).thenReturn(new ToolCallback[]{raw,other});
        when(registry.getCurrentCallback("alice-search","search")).thenReturn(raw);
        var found=service.resolveDynamicToolCallbacks("run","session","c1","搜索网页","query",List.of());
        assertEquals(List.of("search"),found.stream().map(c->c.getToolDefinition().name()).toList());
        verify(registry).registerCallbacks(eq("alice-search"),any());
        verify(raw,never()).call(anyString());verify(other,never()).call(anyString());
    }
    @Test public void revokedLazyBindingNeverConnectsAndAlreadyLoadedToolsAreExcluded() {
        when(repository.queryWorkspaceNodePolicy("c1")).thenReturn(new WorkspaceNodePolicy("a1","c1","",
                Map.of("alice-search",Set.of()),Set.of("alice-search"),Set.of(),Set.of("alice-search")));
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of());
        assertTrue(service.resolveDynamicToolCallbacks("run","session","c1","search","query",List.of()).isEmpty());
        verifyNoInteractions(registry);
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of("alice-search"));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(List.of(tool("platform-search","web-search")));
        assertTrue(service.resolveDynamicToolCallbacks("run","session","c1","search","query",List.of(new AgentToolRegistry.ToolInfo("web-search","search"))).isEmpty());
        verify(registry,never()).getCurrentCallback(anyString(),anyString());
    }
    private ToolCallback raw(String name,String description) {
        var callback=mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description(description).inputSchema("{}").build());
        return callback;
    }
    @Test public void publicHitCannotHideNewExplicitPrivateConnection() {
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of("alice-search","alice-docs"));
        when(access.ownsMcp("alice","alice-docs")).thenReturn(true);
        when(repository.queryWorkspaceNodePolicy("c1")).thenReturn(new WorkspaceNodePolicy("a1","c1","",
                Map.of("alice-docs",Set.of()),Set.of("alice-docs"),Set.of("alice-search"),Set.of("alice-docs")));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),eq(Set.of("platform-search"))))
                .thenReturn(List.of(tool("platform-search","web-search")));
        when(repository.queryAiClientToolMcpVOByMcpId("alice-docs"))
                .thenReturn(AiClientToolMcpVO.builder().mcpId("alice-docs").mcpName("内部文档").build());
        var docs=raw("docs_search","搜索公司文档");var web=raw("web-search","联网搜索");
        when(registry.currentCallbacks("alice-docs")).thenReturn(new ToolCallback[]{docs});
        when(registry.getCurrentCallback("alice-docs","docs_search")).thenReturn(docs);
        when(registry.getCurrentCallback("alice-search","web-search")).thenReturn(web);
        var found=service.resolveDynamicToolCallbacks("run","session","c1","搜索","query",List.of());
        assertEquals(List.of("docs_search","web-search"),found.stream().map(c->c.getToolDefinition().name()).toList());
        verify(docs,never()).call(anyString());
    }
}
