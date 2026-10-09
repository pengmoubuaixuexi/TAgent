package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import cn.bugstack.ai.domain.agent.service.router.*;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step1McpToolsAnalysisNode;
import org.junit.*;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Bound inventory must be visible before any request_tool search, without granting business execution. */
public class FlowBoundMcpCapabilitiesTest {
    final IAgentRepository repo=mock(IAgentRepository.class);
    final IWorkspaceAccessRepository access=mock(IWorkspaceAccessRepository.class);
    final McpClientRegistry registry=mock(McpClientRegistry.class);
    final IToolVectorStore vectors=mock(IToolVectorStore.class);
    final McpToolCatalogService service=new McpToolCatalogService();
    @Before public void setup() {
        ReflectionTestUtils.setField(service,"repository",repo);
        ReflectionTestUtils.setField(service,"workspaceAccess",access);
        ReflectionTestUtils.setField(service,"mcpClientRegistry",registry);
        ReflectionTestUtils.setField(service,"toolVectorStore",vectors);
        ReflectionTestUtils.setField(service,"mcpToolMetrics",mock(McpToolMetrics.class));
        MDC.put("userId","alice");MDC.put("agentId","agent");
        when(access.agentMcpIds("alice","agent")).thenReturn(Set.of("owned"));
        when(access.ownsMcp("alice","owned")).thenReturn(true);
        when(access.publicMcpOrigins("alice")).thenReturn(Map.of("owned","platform"));
        when(repo.queryAiClientToolMcpVOByMcpId("owned"))
                .thenReturn(AiClientToolMcpVO.builder().mcpId("owned").mcpName("地图与天气").build());
        when(repo.queryWorkspaceNodePolicy("executor")).thenReturn(policy(Set.of(),true));
    }
    @After public void cleanup(){MDC.clear();}
    WorkspaceNodePolicy policy(Set<String> names,boolean all) {
        return new WorkspaceNodePolicy("agent","executor","",Map.of("owned",names),all?Set.of("owned"):Set.of(),Set.of(),Set.of("owned"));
    }
    AiMcpToolCatalogVO tool(String name) {
        return AiMcpToolCatalogVO.builder().mcpId("platform").toolName(name).enabled(1)
                .toolDescription("查询地点天气").inputSchemaJson("{\"type\":\"object\",\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"}}}").build();
    }
    @Test public void fullBoundCatalogReachesAnalysisEvenWithZeroLoadedCallbacksAndNoNeeds() {
        var entries=new ArrayList<AiMcpToolCatalogVO>();
        for(int i=0;i<9;i++)entries.add(tool("tool_"+i));entries.add(tool("maps_weather"));
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(entries);
        var step=new Step1McpToolsAnalysisNode();
        ReflectionTestUtils.setField(step,"dynamicMcpToolCatalogService",service);
        ReflectionTestUtils.setField(step,"requestToolEnabled",true);
        String prompt=ReflectionTestUtils.invokeMethod(step,"renderStep1ToolRuntimeForPrompt",
                ExecutorToolCatalog.from(List.of(),ExecutorToolCatalog.Source.DYNAMIC,1),"executor");
        assertTrue(prompt.contains("maps_weather"));assertTrue(prompt.contains("required=city"));
        assertTrue(prompt.contains("\"authorizedToolCount\":10"));assertTrue(prompt.contains("尚未装载"));
        assertFalse(prompt.contains("没有配置任何可执行"));
        verifyNoInteractions(vectors,registry); // metadata is neither top-k filtered nor a business invocation
    }
    @Test public void sourceCatalogIsFilteredByOwnNodePermissionsAndRevocation() {
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("maps_weather"),tool("delete_file")));
        when(repo.queryWorkspaceNodePolicy("executor")).thenReturn(policy(Set.of("maps_weather"),false));
        String result=service.describeBoundMcpCapabilities("executor");
        assertTrue(result.contains("maps_weather"));assertFalse(result.contains("delete_file"));
        when(access.agentMcpIds("alice","agent")).thenReturn(Set.of());
        assertFalse(service.describeBoundMcpCapabilities("executor").contains("maps_weather"));
        MDC.put("agentId","another");assertEquals("",service.describeBoundMcpCapabilities("executor"));
        verify(repo,never()).queryEnabledMcpToolCatalog();verifyNoInteractions(registry);
    }
    @Test public void unindexedConnectionUsesListedDefinitionsButNeverCallsATool() {
        var cb=mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder().name("maps_weather").description("天气查询").inputSchema("{}").build());
        when(registry.currentCallbacks("owned")).thenReturn(new ToolCallback[]{cb});
        assertTrue(service.describeBoundMcpCapabilities("executor").contains("maps_weather"));
        verify(cb,never()).call(anyString());verifyNoInteractions(vectors);
    }
    @Test public void failedCatalogIsUnknownNotAbsenceOfCapabilities() {
        when(registry.currentCallbacks("owned")).thenThrow(new IllegalStateException("secret URL"));
        String result=service.describeBoundMcpCapabilities("executor");
        assertTrue(result.contains("目录读取失败，能力未知"));assertFalse(result.contains("secret URL"));
    }
    @Test public void inventoryBudgetReportsOmissionsInsteadOfPretendingItIsComplete() {
        var entries=new ArrayList<AiMcpToolCatalogVO>();
        for(int i=0;i<205;i++)entries.add(tool("tool_"+i));
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(entries);
        String result=service.describeBoundMcpCapabilities("executor");
        assertTrue(result.contains("\"authorizedToolCount\":205"));assertTrue(result.contains("\"omittedTools\":5"));
    }
    @Test public void exactToolNameFromInventoryWinsOverUnrelatedSemanticHits() {
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("maps_weather")));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(List.of(tool("wrong_search")));
        var found=service.previewMatchedTools("maps_weather","weather",Set.of());
        assertEquals(List.of("maps_weather"),found.stream().map(AiMcpToolCatalogVO::getToolName).toList());
        assertEquals("owned",found.get(0).getMcpId());verifyNoInteractions(vectors);
    }
}
