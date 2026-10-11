package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapability;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory.DynamicContext;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.UserInputGate;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Runs the real FLOW node bodies, replacing only model/SSE/storage boundaries. */
public class FlowPlanningCatalogOnlyTest {
    @Test public void actualModelPromptRetainsSavedPersonaAndAddsCurrentStageSystemProtocol() throws Exception {
        String savedPersona = "USER_PRIVATE_TRAVEL_PERSONA; legacy instruction: 允许 request_tool 发现能力";
        var req = new ExecuteCommandEntity();
        req.setAiAgentId("agent"); req.setRunId("run"); req.setSessionId("session"); req.setMessage("Travel plan");
        var ctx = new DynamicContext();
        ctx.setCurrentTask("Travel plan; user clarified budget=2000");
        ctx.setAiAgentClientFlowConfigVOMap(Map.of(
                "TOOL_MCP_CLIENT", config("analysis"), "PLANNING_CLIENT", config("planner"),
                "EXECUTOR_CLIENT", config("executor")));
        for (var node : List.of(spy(new Step1McpToolsAnalysisNode()), spy(new Step2PlanningNode()))) {
            var model = new cn.bugstack.ai.test.contract.CapturingChatModel();
            var client = ChatClient.builder(model).defaultSystem(savedPersona).build();
            stubBoundaries(node, new AtomicReference<>(), new AtomicReference<>());
            doReturn(client).when(node).getChatClientByClientId(anyString());
            doAnswer(invocation -> {
                Function<String, ChatClient.ChatClientRequestSpec> builder = invocation.getArgument(0);
                Supplier<String> supplier = invocation.getArgument(5);
                return builder.apply(supplier.get()).call().content();
            }).when(node).callStepWithSteer(any(), any(), anyString(), anyString(),
                    any(ToolCapabilityProfile.class), any(), anyString());
            ReflectionTestUtils.invokeMethod(node, "doApply", req, ctx);
            var messages = model.lastSnapshot().messages();
            var systems = messages.stream().filter(message -> "SYSTEM".equals(message.role())).toList();
            assertEquals(2, systems.size());
            assertEquals(savedPersona, systems.get(0).text());
            assertTrue(systems.get(1).text().contains("本轮不开放 request_tool"));
            assertTrue(systems.get(1).text().contains("旧说明不适用于本轮"));
            assertTrue(systems.get(1).text().contains("保留用户配置的领域角色"));
            assertTrue(messages.stream().anyMatch(message -> "USER".equals(message.role())
                    && message.text().contains("budget=2000")));
        }
    }

    @Test public void newWorkspaceTemplatesDescribeCatalogFirstFlowAndCorrectPlanFormat() {
        var stages = cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.steps("flow");
        assertTrue(stages.get(0).system().contains("不为了解工具而装载"));
        assertTrue(stages.get(1).system().contains("Markdown 和 DEPENDS_ON"));
        assertTrue(stages.get(1).system().contains("实际执行时再按需装载"));
        assertFalse(stages.get(0).system().contains("允许 request_tool"));
    }

    @Test public void analysisAndPlanningReadCatalogWithoutSearchOrLoadingAndKeepClarification() throws Exception {
        var req = new ExecuteCommandEntity();
        req.setAiAgentId("agent"); req.setRunId("run"); req.setSessionId("session"); req.setMessage("Plan a trip");
        var ctx = new DynamicContext();
        ctx.setCurrentTask("Plan a trip; user already clarified city=Beijing and budget=2000");
        ctx.setAiAgentClientFlowConfigVOMap(Map.of(
                "TOOL_MCP_CLIENT", config("analysis"), "PLANNING_CLIENT", config("planner"),
                "EXECUTOR_CLIENT", config("executor")));
        var catalog = mock(McpToolCatalogService.class);
        when(catalog.describeBoundMcpCapabilities("executor"))
                .thenReturn("bound catalog: get-tickets; weather_forecast; maps_text_search; capability available, not executed");
        var registry = mock(AgentToolRegistry.class);
        var mounted = mock(ToolCallback.class);
        when(mounted.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("calculator").description("Already mounted calculator").inputSchema("{}").build());
        when(registry.getToolCallbacks("executor")).thenReturn(List.of(mounted));
        var gate = mock(UserInputGate.class);
        when(gate.isEnabled()).thenReturn(true); when(gate.remainingFor("session")).thenReturn(1);

        for (var node : List.of(spy(new Step1McpToolsAnalysisNode()), spy(new Step2PlanningNode()))) {
            ReflectionTestUtils.setField(node, "dynamicMcpToolCatalogService", catalog);
            ReflectionTestUtils.setField(node, "dynamicAgentToolRegistry", registry);
            ReflectionTestUtils.setField(node, "requestToolEnabled", true);
            ReflectionTestUtils.setField(node, "userInputGate", gate);
            var prompt = new AtomicReference<String>();
            var profile = new AtomicReference<ToolCapabilityProfile>();
            stubBoundaries(node, prompt, profile);
            assertEquals("ROUTED", ReflectionTestUtils.invokeMethod(node, "doApply", req, ctx));
            assertTrue(prompt.get().contains("get-tickets"));
            assertTrue(prompt.get().contains("calculator"));
            assertTrue(prompt.get().contains("budget=2000"));
            assertTrue(prompt.get().contains("ask_user"));
            assertFalse(prompt.get().contains("- request_tool："));
            assertFalse(prompt.get().contains("工具映射验证反馈"));
            assertEquals(ToolCapabilityProfile.CLARIFICATION_ONLY, profile.get());
            assertTrue(profile.get().allows(ToolCapability.ASK_USER));
            assertFalse(profile.get().allows(ToolCapability.REQUEST_TOOL));
            assertFalse(profile.get().allows(ToolCapability.BUSINESS_TOOL));
        }
        verify(catalog, times(2)).describeBoundMcpCapabilities("executor");
        verifyNoMoreInteractions(catalog); // No needs replay, embedding query, discovery or callback materialization.
        verify(mounted, never()).call(anyString());
        assertEquals(Set.of("calculator"), ((ExecutorToolCatalog) ctx.getValue("flow.toolCatalog.v1")).toolNames());
        assertEquals(Set.of("calculator"), ((ExecutorToolCatalog) ctx.getValue("flow.toolCatalog.v2")).toolNames());
        assertNotNull(ctx.getValue("mcpToolsAnalysis")); assertNotNull(ctx.getValue("planningResult"));
    }

    @Test public void finalSynthesisUsesCompletedResultsWithoutDiscoveringOrAttachingTools() {
        var node = spy(new Step4ExecuteStepsNode());
        var catalog = mock(McpToolCatalogService.class);
        ReflectionTestUtils.setField(node, "dynamicMcpToolCatalogService", catalog);
        var req = new ExecuteCommandEntity();
        req.setRunId("run"); req.setSessionId("session"); req.setMessage("Plan a trip");
        var ctx = new DynamicContext();
        ctx.setCurrentTask("Plan a trip; user clarified budget=2000");
        ctx.setValue("step1Result", "Confirmed weather: sunny");
        var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        var prompt = new AtomicReference<String>();
        doAnswer(invocation -> {
            assertEquals(ToolCapabilityProfile.ARTIFACT_ONLY, invocation.getArgument(4));
            prompt.set(invocation.getArgument(5));
            return "Trip summary";
        }).when(node).callStepWithStreaming(any(), any(), anyString(), anyString(),
                any(ToolCapabilityProfile.class), anyString(), anyString());
        assertEquals("Trip summary", ReflectionTestUtils.invokeMethod(node, "buildFinalDeliverable",
                ctx, req, client, "executor"));
        assertTrue(prompt.get().contains("Confirmed weather: sunny"));
        assertTrue(prompt.get().contains("budget=2000"));
        verifyNoInteractions(catalog);
        verify(node, never()).resolveAgentDynamicToolCallbacks(any(), anyString());
    }

    private AiAgentClientFlowConfigVO config(String client) {
        return AiAgentClientFlowConfigVO.builder().clientId(client).build();
    }

    private void stubBoundaries(AbstractExecuteSupport node, AtomicReference<String> prompt,
                                AtomicReference<ToolCapabilityProfile> profile) throws Exception {
        doReturn(mock(ChatClient.class)).when(node).getChatClientByClientId(anyString());
        doReturn(null).when(node).checkFinalizeRoute(any(), any());
        doReturn("ROUTED").when(node).router(any(), any());
        doNothing().when(node).sendThinkingEvent(any(), anyString(), anyString(), anyString());
        doNothing().when(node).sendSseResult(any(), any());
        doNothing().when(node).mirrorToWorkingMemory(anyString(), anyString(), any());
        doNothing().when(node).recordTransition(anyString(), any());
        doAnswer(invocation -> {
            Supplier<String> supplier = invocation.getArgument(5);
            prompt.set(supplier.get()); profile.set(invocation.getArgument(4));
            return "Use catalog tools during execution";
        }).when(node).callStepWithSteer(any(), any(), anyString(), anyString(), any(ToolCapabilityProfile.class), any(), anyString());
    }
}
