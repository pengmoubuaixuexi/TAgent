package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import cn.bugstack.ai.domain.agent.service.router.*;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step1McpToolsAnalysisNode;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step2PlanningNode;
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
        verify(repo,never()).queryEnabledMcpToolCatalog();
        verify(registry).getCurrentCallback("owned","delete_file");
        verifyNoMoreInteractions(registry); // local alias lookup only; no MCP discovery or invocation
    }
    @Test public void unindexedConnectionUsesListedDefinitionsButNeverCallsATool() {
        var cb=mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder().name("maps_weather").description("天气查询").inputSchema("{}").build());
        when(registry.currentCallbacks("owned")).thenReturn(new ToolCallback[]{cb});
        assertTrue(service.describeBoundMcpCapabilities("executor").contains("maps_weather"));
        verify(cb,never()).call(anyString());verifyNoInteractions(vectors);
    }
    @Test public void savedCanonicalSelectionCanSeeTheAuthoritativeOriginalNameInBoundCatalog() {
        when(repo.queryWorkspaceNodePolicy("executor")).thenReturn(policy(Set.of("get_tickets"),false));
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("get-tickets")));
        var raw=mock(org.springframework.ai.mcp.SyncMcpToolCallback.class);
        when(raw.getOriginalToolName()).thenReturn("get-tickets");
        when(raw.getToolDefinition()).thenReturn(ToolDefinition.builder().name("get_tickets")
                .description("查询车票").inputSchema("{}").build());
        when(registry.getCurrentCallback("owned","get-tickets")).thenReturn(raw);
        assertTrue(service.describeBoundMcpCapabilities("executor").contains("get-tickets"));
        verify(raw,never()).call(anyString());verifyNoInteractions(vectors);
        verify(registry).getCurrentCallback("owned","get-tickets");verifyNoMoreInteractions(registry);
    }
    @Test public void failedCatalogIsUnknownNotAbsenceOfCapabilities() {
        when(registry.currentCallbacks("owned")).thenThrow(new IllegalStateException("secret URL"));
        String result=service.describeBoundMcpCapabilities("executor");
        assertTrue(result.contains("目录读取失败，能力未知"));assertFalse(result.contains("secret URL"));
    }
    @Test public void missingCatalogDoesNotConnectToRemoteServiceDuringPlanning() {
        when(registry.currentCallbacks("owned")).thenReturn(new ToolCallback[0]);
        String result=service.describeBoundMcpCapabilities("executor");
        assertTrue(result.contains("尚未发现工具目录，能力未知"));
        assertTrue(result.contains("执行阶段再按需发现"));
        verify(registry).currentCallbacks("owned");
        verifyNoMoreInteractions(registry);
        verifyNoInteractions(vectors);
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

    @Test public void loadedToolDoesNotHideUnloadedExecutorBindingsFromEitherFlowStage() {
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("maps_weather"),tool("get-tickets")));
        var callback=mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("maps_weather")
                .description("已装载的天气查询").inputSchema("{}").build());
        var catalog=ExecutorToolCatalog.from(List.of(callback),ExecutorToolCatalog.Source.RESIDENT,1);
        for (Object step:List.of(new Step1McpToolsAnalysisNode(),new Step2PlanningNode())) {
            ReflectionTestUtils.setField(step,"dynamicMcpToolCatalogService",service);
            String prompt=ReflectionTestUtils.invokeMethod(step,"renderToolRuntimeForPrompt",catalog,"executor");
            assertTrue(prompt.contains("maps_weather"));
            assertTrue(prompt.contains("get-tickets"));
            assertTrue(prompt.contains("不授予分析/规划节点业务执行权限"));
        }
        verify(callback,never()).call(anyString());verifyNoInteractions(registry,vectors);
    }

    @Test public void emptyFlowCatalogDistinguishesPlanningExecutionAndUnavailableDiscovery() {
        var step = new cn.bugstack.ai.domain.agent.service.execute.flow.step.Step4ExecuteStepsNode();
        var catalogService = mock(cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService.class);
        ReflectionTestUtils.setField(step, "dynamicMcpToolCatalogService", catalogService);
        ReflectionTestUtils.setField(step, "requestToolEnabled", true);
        var empty = ExecutorToolCatalog.from(List.of(), ExecutorToolCatalog.Source.RESIDENT, 1);
        String planning = ReflectionTestUtils.invokeMethod(step, "renderToolRuntimeForPrompt", empty, "executor");
        assertTrue(planning.contains("本阶段只分析或规划，不调用 request_tool"));
        assertTrue(planning.contains("待发现"));
        String execution = ReflectionTestUtils.invokeMethod(step, "renderToolRuntimeForPrompt", empty, "executor",
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_DAG_EXECUTION);
        assertTrue(execution.contains("先调用 request_tool"));
        assertFalse(execution.contains("自身知识"));
        String finalize = ReflectionTestUtils.invokeMethod(step, "renderToolRuntimeForPrompt", empty, "executor",
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.answerNow(true));
        assertFalse(finalize.contains("request_tool"));
        for (boolean servicePresent : new boolean[]{true, false}) {
            ReflectionTestUtils.setField(step, "requestToolEnabled", !servicePresent);
            ReflectionTestUtils.setField(step, "dynamicMcpToolCatalogService", servicePresent ? catalogService : null);
            String unavailable = ReflectionTestUtils.invokeMethod(step, "renderToolRuntimeForPrompt", empty, "executor",
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_DAG_EXECUTION);
            assertFalse(unavailable.contains("request_tool"));
            assertTrue(unavailable.contains("如实说明限制"));
        }
        verify(catalogService, never()).resolveDynamicToolCallbacks(any(), any(), any(), any(), any(), any());
    }

    @Test public void onlyConfiguredAnalysisAndPlanningRolesCanInspectTheirExecutorGrant() {
        when(repo.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("maps_weather")));
        var roles=Map.of("TOOL_MCP_CLIENT","analysis","PLANNING_CLIENT","planning",
                "TASK_ANALYZER_CLIENT","auto-analysis","QUALITY_SUPERVISOR_CLIENT","quality");
        Map<String,AiAgentClientFlowConfigVO> flow=new HashMap<>();
        roles.forEach((role,client)->{
            flow.put(role,AiAgentClientFlowConfigVO.builder().clientId(client).clientType(role).build());
            when(repo.queryWorkspaceNodePolicy(client)).thenReturn(new WorkspaceNodePolicy("agent",client,"",Map.of(),Set.of()));
        });
        flow.put("EXECUTOR_CLIENT",AiAgentClientFlowConfigVO.builder().clientId("executor").build());
        flow.put("PRECISION_EXECUTOR_CLIENT",AiAgentClientFlowConfigVO.builder().clientId("executor").build());
        when(repo.queryAiAgentClientFlowConfig("agent")).thenReturn(flow);
        for (String client:List.of("analysis","planning","auto-analysis")) {
            assertTrue(service.describeBoundMcpCapabilities(client).contains("maps_weather"));
        }
        assertFalse(service.describeBoundMcpCapabilities("quality").contains("maps_weather"));
        when(repo.queryWorkspaceNodePolicy("executor")).thenReturn(
                new WorkspaceNodePolicy("other-agent","executor","",Map.of("owned",Set.of()),Set.of("owned")));
        assertFalse(service.describeBoundMcpCapabilities("analysis").contains("maps_weather"));
        MDC.put("agentId","other-agent");
        assertEquals("",service.describeBoundMcpCapabilities("analysis"));
        verifyNoInteractions(registry,vectors);
    }
}
