package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AutoAnalyzerToolVisibilityTest {
    @Test public void analyzerPromptIncludesLoadedAndBoundExecutorCapabilitiesWithoutExecuting() throws Exception {
        var loaded=mock(ToolCallback.class);
        when(loaded.getToolDefinition()).thenReturn(ToolDefinition.builder().name("maps_weather")
                .description("查询城市天气").inputSchema("{\"type\":\"object\",\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"}}}").build());
        var registry=new AgentToolRegistry();registry.register("executor",new ToolCallback[]{loaded});
        var catalog=mock(McpToolCatalogService.class);
        when(catalog.describeBoundMcpCapabilities("executor")).thenReturn("绑定目录：get-tickets；尚未装载");
        var node=new AutoNodeTestSeams.Step1();
        ReflectionTestUtils.setField(node,"dynamicAgentToolRegistry",registry);
        ReflectionTestUtils.setField(node,"dynamicMcpToolCatalogService",catalog);
        node.result("任务状态: CONTINUE");
        var context=DefaultAutoAgentExecuteStrategyFactory.DynamicContext.builder()
                .step(1).maxStep(4).executionHistory(new StringBuilder()).currentTask("规划旅行")
                .aiAgentClientFlowConfigVOMap(Map.of(
                        "TASK_ANALYZER_CLIENT",AiAgentClientFlowConfigVO.builder().clientId("analysis")
                                .stepPrompt("%s %d %d %s %s").build(),
                        "PRECISION_EXECUTOR_CLIENT",AiAgentClientFlowConfigVO.builder().clientId("executor").build())).build();
        node.run(ExecuteCommandEntity.builder().aiAgentId("agent").sessionId("session").message("规划旅行").build(),context);
        String prompt=node.capturedPrompt();
        assertTrue(prompt.contains("maps_weather"));assertTrue(prompt.contains("required=city"));
        assertTrue(prompt.contains("get-tickets"));assertTrue(prompt.contains("不授予当前阶段业务工具执行权限"));
        assertTrue(prompt.contains("不可信资料"));
        verify(catalog).describeBoundMcpCapabilities("executor");
        verify(catalog,never()).describeBoundMcpCapabilities("analysis");
        verify(loaded,never()).call(anyString());
    }
}
