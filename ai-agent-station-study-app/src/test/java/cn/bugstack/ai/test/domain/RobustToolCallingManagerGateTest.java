package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.security.UserInputGate;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * P1-B：legacy stepLabel/global-switch 已删，工具门<b>只</b>由显式 ToolCapabilities profile 决定。<b>不连真实 LLM</b>、不跑 E2E：
 * <ol>
 *   <li><b>门的逻辑</b>：{@link RobustToolCallingManager#resolveToolDefinitions} 按显式 profile 分别 gate
 *       业务/ask_user/request_tool；缺失显式 policy → fail-closed 到 NONE（不再依据 stepLabel 猜执行步）；非法值 → NONE。</li>
 *   <li><b>运行期管道</b>：经真实 {@link ChatClient}→{@link OpenAiChatModel} 走一遍，
 *       验证 step 通过 {@code spec.toolContext} 注入的 profile <b>确实到达</b>了
 *       {@code resolveToolDefinitions(options)} 的 {@code options.getToolContext()}——这正是门判定的依据。
 *       用一个不可达 endpoint 让 HTTP 失败即可（resolveToolDefinitions 发生在 HTTP 之前，捕获到的已写入）。</li>
 * </ol>
 */
public class RobustToolCallingManagerGateTest {

    @After
    public void clearMdc() {
        MDC.remove("step");
        MDC.remove("sessionId");
    }

    /** 返回固定 2 条工具定义的假 delegate（用于门逻辑测试，看门有没有把它清空）。 */
    static class StaticDelegate implements ToolCallingManager {
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of(
                    ToolDefinition.builder().name("toolA").description("a").inputSchema("{}").build(),
                    ToolDefinition.builder().name("toolB").description("b").inputSchema("{}").build());
        }
        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            return ToolExecutionResult.builder().conversationHistory(prompt.getInstructions()).build();
        }
    }

    private static ToolCallingChatOptions optsWithStepLabel(String stepLabel) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder();
        if (stepLabel != null) b.toolContext(Map.of("stepLabel", stepLabel));
        return b.build();
    }

    private static OpenAiChatOptions optsWithProfile(ToolCapabilityProfile profile) {
        return OpenAiChatOptions.builder().toolContext(Map.of(
                "sessionId", "s1", ToolCapabilities.TOOL_CONTEXT_KEY, profile.name())).build();
    }

    private static ChatResponse responseWithToolCall(String name) {
        return responseWithToolCall(name, "{}");
    }

    private static ChatResponse responseWithToolCall(String name, String arguments) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("id-" + name, "function", name, arguments);
        AssistantMessage message = AssistantMessage.builder().content("").properties(Map.of())
                .toolCalls(List.of(call)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static Prompt promptWithProfile(ToolCapabilityProfile profile) {
        return new Prompt("test", optsWithProfile(profile));
    }

    // ---------- 1. 门的逻辑（P1-B：只由显式 profile 决定） ----------

    @Test
    public void missingPolicy_failsClosedToNone_regardlessOfStepLabel() {
        RobustToolCallingManager mgr = new RobustToolCallingManager(new StaticDelegate());
        // P1-B：删除 legacy stepLabel/global-switch fallback 后，缺失显式 policy 一律 fail-closed 到 NONE，
        // 无论 stepLabel 看起来像非执行步还是执行步，也无论 MDC step。
        assertTrue("缺失显式 policy(非执行步样 stepLabel) → NONE 空",
                mgr.resolveToolDefinitions(optsWithStepLabel("质量评审")).isEmpty());
        assertTrue("缺失显式 policy(执行步样 stepLabel) → 仍 NONE 空(stepLabel 不再影响)",
                mgr.resolveToolDefinitions(optsWithStepLabel("精准执行")).isEmpty());
        assertTrue("缺失显式 policy + 无 toolContext → NONE 空",
                mgr.resolveToolDefinitions(optsWithStepLabel(null)).isEmpty());

        MDC.put("step", "step2_precision_executor");
        assertTrue("MDC step 也不再放行(legacy heuristic 已删) → NONE 空",
                mgr.resolveToolDefinitions(optsWithStepLabel(null)).isEmpty());
        MDC.remove("step");
    }

    @Test
    public void forceNoTools_precedesDelegateAndMetaToolBroadcast() {
        RobustToolCallingManager mgr = new RobustToolCallingManager(new StaticDelegate());
        mgr.setRequestToolEnabled(true);
        mgr.setMcpToolCatalogService(mock(cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService.class));

        // P1-B：基线改用显式 ALL profile（不再靠 stepLabel-missing→ALL，那条 legacy 路径已删）。
        OpenAiChatOptions normal = optsWithProfile(ToolCapabilityProfile.ALL);
        assertEquals("normal(ALL) 请求应包含 delegate 的 2 个工具 + request_tool", 3,
                mgr.resolveToolDefinitions(normal).size());

        OpenAiChatOptions forced = OpenAiChatOptions.builder()
                .toolContext(Map.of(
                        ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.ALL.name(),
                        RobustToolCallingManager.FORCE_NO_TOOLS_KEY, Boolean.TRUE))
                .build();
        assertTrue("force-NONE 必须早于 delegate 与 request_tool 广播", mgr.resolveToolDefinitions(forced).isEmpty());

        OpenAiChatOptions untyped = OpenAiChatOptions.builder()
                .toolContext(Map.of(
                        ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.ALL.name(),
                        RobustToolCallingManager.FORCE_NO_TOOLS_KEY, "true"))
                .build();
        assertEquals("只有应用侧 Boolean.TRUE 可触发，字符串不得触发", 3,
                mgr.resolveToolDefinitions(untyped).size());
    }

    @Test
    public void explicitProfilesGateBusinessAskUserAndRequestToolIndependently() {
        RobustToolCallingManager mgr = new RobustToolCallingManager(new StaticDelegate());
        mgr.setRequestToolEnabled(true);
        mgr.setMcpToolCatalogService(mock(cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService.class));
        UserInputGate gate = mock(UserInputGate.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.remainingFor("s1")).thenReturn(1);
        mgr.setUserInputGate(gate);
        MDC.put("sessionId", "s1");

        assertEquals(0, mgr.resolveToolDefinitions(optsWithProfile(ToolCapabilityProfile.NONE)).size());
        assertEquals(List.of("request_tool"), mgr.resolveToolDefinitions(optsWithProfile(ToolCapabilityProfile.DISCOVERY_ONLY))
                .stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("ask_user", "request_tool"), mgr.resolveToolDefinitions(optsWithProfile(ToolCapabilityProfile.INTERACTIVE_META))
                .stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("toolA", "toolB"), mgr.resolveToolDefinitions(optsWithProfile(ToolCapabilityProfile.BUSINESS_ONLY))
                .stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("toolA", "toolB", "ask_user", "request_tool"), mgr.resolveToolDefinitions(optsWithProfile(ToolCapabilityProfile.ALL))
                .stream().map(ToolDefinition::name).toList());
    }

    @Test
    public void askUserEmitsItsMetaToolProgressCard() {
        RobustToolCallingManager mgr = new RobustToolCallingManager(new StaticDelegate());
        UserInputGate gate = mock(UserInputGate.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.requestUserInput(anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new UserInputGate.Result(UserInputGate.Status.ANSWERED, "user-selected"));
        ToolCallProgressEmitter progress = mock(ToolCallProgressEmitter.class);
        mgr.setUserInputGate(gate);
        mgr.setToolCallProgressEmitter(progress);
        MDC.put("sessionId", "s1");

        ToolExecutionResult result = mgr.executeToolCalls(
                promptWithProfile(ToolCapabilityProfile.INTERACTIVE_META),
                responseWithToolCall("ask_user", "{\"questions\":[\"where\"]}"));

        verify(progress).emitMetaStart(eq("s1"), eq("ask_user"), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        verify(progress).emitMetaEnd(eq("s1"), eq("ask_user"), eq("success"), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        org.springframework.ai.chat.messages.ToolResponseMessage response =
                (org.springframework.ai.chat.messages.ToolResponseMessage) result.conversationHistory()
                        .get(result.conversationHistory().size() - 1);
        assertTrue(response.getResponses().get(0).responseData().contains("user-selected"));
    }

    @Test
    public void invalidExplicitPolicyFailsClosedToNone() {
        RobustToolCallingManager mgr = new RobustToolCallingManager(new StaticDelegate());
        OpenAiChatOptions invalid = OpenAiChatOptions.builder()
                .toolContext(Map.of(ToolCapabilities.TOOL_CONTEXT_KEY, "INVALID_PROFILE", "stepLabel", "精准执行"))
                .build();
        assertTrue(mgr.resolveToolDefinitions(invalid).isEmpty());
    }

    @Test
    public void executeToolCallsRejectsUnauthorizedCallsBeforeDelegate() {
        class CountingDelegate extends StaticDelegate {
            final AtomicInteger calls = new AtomicInteger();
            @Override public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
                calls.incrementAndGet();
                return super.executeToolCalls(prompt, response);
            }
        }
        CountingDelegate delegate = new CountingDelegate();
        RobustToolCallingManager mgr = new RobustToolCallingManager(delegate);

        ToolExecutionResult deniedBusiness = mgr.executeToolCalls(
                promptWithProfile(ToolCapabilityProfile.NONE), responseWithToolCall("toolA"));
        assertEquals(0, delegate.calls.get());
        assertTrue(deniedBusiness.conversationHistory().get(deniedBusiness.conversationHistory().size() - 1)
                instanceof org.springframework.ai.chat.messages.ToolResponseMessage);

        mgr.executeToolCalls(promptWithProfile(ToolCapabilityProfile.BUSINESS_ONLY), responseWithToolCall("request_tool"));
        assertEquals(0, delegate.calls.get());

        mgr.executeToolCalls(promptWithProfile(ToolCapabilityProfile.BUSINESS_ONLY), responseWithToolCall("toolA"));
        assertEquals(1, delegate.calls.get());
    }

    @Test
    public void requestToolFeedbackAndCurrentRoundInjectionFollowPolicy() {
        RobustToolCallingManager mgr = new RobustToolCallingManager(new StaticDelegate());
        cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService catalog =
                mock(cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService.class);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name("calculate").description("calc").inputSchema("{}").build());
        when(catalog.resolveDynamicToolCallbacks(any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(callback));
        mgr.setRequestToolEnabled(true);
        mgr.setMcpToolCatalogService(catalog);
        MDC.put("sessionId", "s1");

        Prompt discoveryPrompt = promptWithProfile(ToolCapabilityProfile.DISCOVERY_ONLY);
        ToolExecutionResult discovery = mgr.executeToolCalls(discoveryPrompt,
                responseWithToolCall("request_tool", "{\"needs\":[\"精确计算\"]}"));
        org.springframework.ai.chat.messages.ToolResponseMessage discoveryResponse =
                (org.springframework.ai.chat.messages.ToolResponseMessage) discovery.conversationHistory()
                        .get(discovery.conversationHistory().size() - 1);
        assertTrue(discoveryResponse.getResponses().get(0).responseData().contains("后续执行阶段"));
        assertTrue(((OpenAiChatOptions) discoveryPrompt.getOptions()).getToolCallbacks() == null
                || ((OpenAiChatOptions) discoveryPrompt.getOptions()).getToolCallbacks().isEmpty());

        Prompt allPrompt = promptWithProfile(ToolCapabilityProfile.ALL);
        ToolExecutionResult all = mgr.executeToolCalls(allPrompt,
                responseWithToolCall("request_tool", "{\"needs\":[\"精确计算\"]}"));
        org.springframework.ai.chat.messages.ToolResponseMessage allResponse =
                (org.springframework.ai.chat.messages.ToolResponseMessage) all.conversationHistory()
                        .get(all.conversationHistory().size() - 1);
        assertTrue(allResponse.getResponses().get(0).responseData().contains("现在可以直接调用"));
        assertEquals(1, ((OpenAiChatOptions) allPrompt.getOptions()).getToolCallbacks().size());
    }

    // ---------- 2. 运行期管道：spec.toolContext 是否到达 resolveToolDefinitions ----------

    /** 捕获 resolveToolDefinitions 收到的 options.toolContext.stepLabel，然后返回空让链路继续。 */
    static class CapturingDelegate implements ToolCallingManager {
        final AtomicReference<String> capturedStepLabel = new AtomicReference<>("<未被调用>");
        final AtomicReference<String> capturedPolicy = new AtomicReference<>("<未被调用>");
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            Object v = options.getToolContext() == null ? null : options.getToolContext().get("stepLabel");
            capturedStepLabel.set(v == null ? "<none>" : String.valueOf(v));
            Object p = options.getToolContext() == null ? null : options.getToolContext().get(ToolCapabilities.TOOL_CONTEXT_KEY);
            capturedPolicy.set(p == null ? "<none>" : String.valueOf(p));
            return List.of();
        }
        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            return ToolExecutionResult.builder().conversationHistory(prompt.getInstructions()).build();
        }
    }

    @Test
    public void plumbing_specToolContextReachesResolveToolDefinitions() {
        // 不可达 endpoint：resolveToolDefinitions 在 HTTP 之前执行，捕获完标签后 HTTP 失败无所谓
        OpenAiApi api = OpenAiApi.builder().baseUrl("http://127.0.0.1:1").apiKey("test").build();
        CapturingDelegate delegate = new CapturingDelegate();
        RobustToolCallingManager robust = new RobustToolCallingManager(delegate); // 开关默认 false → 一律委托，便于捕获
        // maxAttempts=1：endpoint 不可达，默认 10 次重试退避会把测试拖到 ~19 分钟；这里只需 HTTP 失败一次即可
        // （resolveToolDefinitions 在 HTTP 之前已执行并捕获），不重试 → 秒级失败。
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api).toolCallingManager(robust)
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
                .build();
        ChatClient client = ChatClient.builder(model).build();

        try {
            client.prompt()
                    .user("你好")
                    // 与 callStepWithStreaming 完全一致：用 ChatClient 级 spec.toolContext 注入 stepLabel
                    .toolContext(Map.of("stepLabel", "质量评审",
                            ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.ALL.name()))
                    .options(OpenAiChatOptions.builder().build())
                    .call()
                    .content();
        } catch (Exception expectedHttpFailure) {
            // 期望：endpoint 不可达，HTTP 阶段抛错——但此时 resolveToolDefinitions 早已执行并捕获
        }

        assertEquals("spec.toolContext 注入的 stepLabel 必须到达 resolveToolDefinitions 的 options（门的判定依据）",
                "质量评审", delegate.capturedStepLabel.get());
        assertEquals("显式 profile 必须随同一个 ToolContext 到达 manager/delegate 管道",
                "ALL", delegate.capturedPolicy.get());
    }
}
