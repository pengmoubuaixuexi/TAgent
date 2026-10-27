package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.test.contract.CapturingChatModel;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Captures the actual executor prompt before making any model request. */
public class AutoEmptyToolsPromptTest {
    private static final class Captured extends Error {}
    private static final class Executor extends Step2PrecisionExecutorNode {
        String prompt;
        ToolCapabilityProfile profile;
        @Override protected void checkCancelled(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {}
        @Override protected String checkFinalizeRoute(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) { return null; }
        @Override protected ChatClient getChatClientByClientId(String id) { return ChatClient.builder(new CapturingChatModel()).build(); }
        @Override protected String callStepWithSteer(Function<String, ChatClient.ChatClientRequestSpec> builder,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx, String step, String label,
                ToolCapabilityProfile profile, Supplier<String> prompt, String session) {
            this.prompt = prompt.get();
            this.profile = profile;
            throw new Captured();
        }
    }

    @Test public void executorWithNoBindingsOffersDiscoveryOnlyWhenAvailable() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            Executor node = new Executor();
            McpToolCatalogService catalog = mock(McpToolCatalogService.class);
            ReflectionTestUtils.setField(node, "agentToolRegistry", new AgentToolRegistry());
            ReflectionTestUtils.setField(node, "mcpToolCatalogService", catalog);
            ReflectionTestUtils.setField(node, "dynamicMcpToolCatalogService", catalog);
            ReflectionTestUtils.setField(node, "requestToolEnabled", enabled);
            var ctx = DefaultAutoAgentExecuteStrategyFactory.DynamicContext.builder()
                    .step(1).maxStep(4).executionHistory(new StringBuilder()).currentTask("查询杭州天气")
                    .aiAgentClientFlowConfigVOMap(Map.of("PRECISION_EXECUTOR_CLIENT",
                            AiAgentClientFlowConfigVO.builder().clientId("executor").stepPrompt("%s\n%s").build())).build();
            ctx.setValue("analysisResult", "需要查询实时天气");
            assertThrows(Captured.class, () -> node.doApply(ExecuteCommandEntity.builder()
                    .aiAgentId("agent").runId("run").sessionId("session").message("查询杭州天气").build(), ctx));
            assertEquals(ToolCapabilityPolicies.AUTO_STEP2_EXECUTION, node.profile);
            assertEquals(enabled, node.prompt.contains("先调用 request_tool"));
            assertFalse(node.prompt.contains("仅基于 LLM 自身知识"));
            assertFalse(node.requestToolAvailable(ToolCapabilityPolicies.AUTO_STEP4_SUMMARY));
            assertFalse(node.requestToolAvailable(ToolCapabilityPolicies.answerNow(true)));
            ReflectionTestUtils.setField(node, "dynamicMcpToolCatalogService", null);
            assertFalse(node.requestToolAvailable(ToolCapabilityPolicies.AUTO_STEP2_EXECUTION));
        }
    }
}
