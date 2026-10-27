package cn.bugstack.ai.test.sandbox;

import cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapability;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService;
import org.junit.After;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** run_python is a real tool: execution stages only, node switch respected, counted in the run's tool budget. */
public class RunPythonToolTest {

    private final String run = "run-py-" + System.nanoTime();

    @After
    public void finish() {
        RunBudgetRegistry.finish(run);
    }

    static final class Sandbox implements ISandboxService {
        boolean available = true;
        final List<Map<String, Object>> scopes = new ArrayList<>();
        public boolean available() { return available; }
        public String run(String toolCallId, String arguments, Map<String, Object> scope, Progress progress) {
            scopes.add(scope);
            if (progress != null) { progress.queued(1); progress.running(0); progress.running(2000); progress.running(6000); }
            return "{\"ok\":true,\"status\":\"OK\",\"stdout\":\"2\"}";
        }
        public void releaseRun(String runId) { }
    }

    private OpenAiChatOptions options(ToolCapabilityProfile profile) {
        Map<String, Object> context = new HashMap<>(Map.of("agent.run_id", run, "userId", "alice", "sessionId", "s1"));
        ToolCapabilities.put(context, profile);
        return OpenAiChatOptions.builder().toolContext(context).build();
    }

    private static ChatResponse call() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "run_python", "{\"code\":\"print(1+1)\"}"))).build())));
    }

    private static String response(ToolExecutionResult result) {
        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory().get(result.conversationHistory().size() - 1);
        return last.getResponses().get(0).responseData();
    }

    private static boolean offered(RobustToolCallingManager manager, OpenAiChatOptions options) {
        return manager.resolveToolDefinitions(options).stream().map(ToolDefinition::name).anyMatch("run_python"::equals);
    }

    @Test
    public void offeredOnlyToExecutionStagesWhenTheSandboxIsAvailableAndTheNodeAllowsIt() {
        Sandbox sandbox = new Sandbox();
        RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
        manager.setSandboxService(sandbox);
        for (ToolCapabilityProfile profile : ToolCapabilityProfile.values()) {
            assertEquals(profile.name(), profile.allows(ToolCapability.RUN_CODE), offered(manager, options(profile)));
        }
        assertFalse("delivery steps only save artifacts", offered(manager, options(ToolCapabilityProfile.ARTIFACT_ONLY)));
        sandbox.available = false;
        assertFalse(offered(manager, options(ToolCapabilityProfile.ALL)));
        sandbox.available = true;
        manager.setWorkspaceNodePolicy(new WorkspaceNodePolicy("a", "c", "", Map.of(), Set.of(), Set.of(), Set.of(), false));
        assertFalse("the owner turned code execution off for this node", offered(manager, options(ToolCapabilityProfile.ALL)));
        String result = response(manager.executeToolCalls(new Prompt("x", options(ToolCapabilityProfile.ALL)), call()));
        assertTrue(result, result.contains("SANDBOX_UNAVAILABLE"));
        assertTrue(sandbox.scopes.isEmpty());
    }

    @Test
    public void executionUsesTheTrustedScopeReportsStatusAndCountsAgainstTheToolBudget() {
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(5), 100, 1, 100000));
        Sandbox sandbox = new Sandbox();
        ToolCallProgressEmitter progress = mock(ToolCallProgressEmitter.class);
        RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
        manager.setSandboxService(sandbox);
        manager.setToolCallProgressEmitter(progress);
        String result = response(manager.executeToolCalls(new Prompt("x", options(ToolCapabilityProfile.ALL)), call()));
        assertTrue(result.contains("\"ok\":true"));
        assertEquals("alice", sandbox.scopes.get(0).get("userId"));
        verify(progress).emitStart(eq("s1"), eq("run_python"), anyString(), any(), eq("call-1"));
        verify(progress).emitProgress("s1", "run_python", "call-1", "排队中，前面还有 1 个");
        verify(progress).emitProgress("s1", "run_python", "call-1", "运行中 0 秒");
        verify(progress, never()).emitProgress("s1", "run_python", "call-1", "运行中 2 秒");
        verify(progress).emitProgress("s1", "run_python", "call-1", "运行中 6 秒");
        verify(progress).emitEnd(eq("s1"), eq("run_python"), eq("success"), anyLong(), anyInt(), any(), eq("call-1"));
        try {
            manager.executeToolCalls(new Prompt("x", options(ToolCapabilityProfile.ALL)), call());
            fail("the second call exceeds the one-call budget");
        } catch (RunBudgetExceededException expected) {
            assertEquals(1, sandbox.scopes.size());
        }
    }

    @Test
    public void planningStagesCannotCallItEvenWhenTheModelTries() {
        Sandbox sandbox = new Sandbox();
        RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build());
        manager.setSandboxService(sandbox);
        for (ToolCapabilityProfile profile : List.of(ToolCapabilityProfile.CLARIFICATION_ONLY, ToolCapabilityProfile.ARTIFACT_ONLY,
                ToolCapabilityProfile.INTERACTIVE_META, ToolCapabilityProfile.NONE)) {
            String result = response(manager.executeToolCalls(new Prompt("x", options(profile)), call()));
            assertTrue(profile + ": " + result, result.contains("未授权"));
        }
        assertTrue(sandbox.scopes.isEmpty());
    }

    @Test
    public void nodeSwitchDefaultsOnAndCanBeTurnedOff() {
        String on = "{\"workspaceNode\":{\"agentId\":\"a\",\"clientId\":\"c\",\"mcpBindings\":[]}}";
        String off = "{\"workspaceNode\":{\"agentId\":\"a\",\"clientId\":\"c\",\"mcpBindings\":[],\"codeExecution\":false}}";
        assertTrue(WorkspaceNodePolicy.fromCapabilities(on).codeExecution());
        assertFalse(WorkspaceNodePolicy.fromCapabilities(off).codeExecution());
    }
}
