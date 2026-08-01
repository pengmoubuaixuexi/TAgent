package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter;
import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import cn.bugstack.ai.domain.agent.service.security.HighRiskToolRegistry;
import cn.bugstack.ai.domain.agent.service.security.HumanApprovalGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H3-A 集成单测：MeteredToolCallback 在 4 个返回路径分别 emit 对应 status 的进度事件。
 * <p>
 * 覆盖：success / blocked (GitHub write) / approval_unavailable / error。
 */
public class MeteredToolCallbackProgressTest {

    private McpToolMetrics metrics;
    private CapturingEmitter sseEmitter;
    private ApprovalChannelRegistry channels;
    private ToolCallProgressEmitter progress;

    @Before
    public void setUp() {
        metrics = new McpToolMetrics(new SimpleMeterRegistry());
        sseEmitter = new CapturingEmitter();
        channels = new ApprovalChannelRegistry();
        channels.register("test-session-1", sseEmitter);
        progress = new ToolCallProgressEmitter(channels);
        MDC.put("sessionId", "test-session-1");
    }

    @After
    public void tearDown() {
        MDC.remove("sessionId");
    }

    @Test
    public void successPathEmitsStartAndEndWithSuccessStatus() {
        FakeToolCallback fake = new FakeToolCallback("aisearch", "OK_RESULT", null);
        MeteredToolCallback mc = new MeteredToolCallback(fake, metrics);
        mc.setToolCallProgressEmitter(progress);

        mc.call("{\"q\":\"hello\"}");

        assertEquals(2, sseEmitter.sent.size());
        assertTrue(sseEmitter.sent.get(0).contains("event: tool_call_start"));
        assertTrue(sseEmitter.sent.get(1).contains("event: tool_call_end"));
        assertTrue(sseEmitter.sent.get(1).contains("\"status\":\"success\""));
        assertTrue("成功路径应带 resultChars", sseEmitter.sent.get(1).contains("\"resultChars\":9"));
    }

    @Test
    public void blockedGithubWriteToolEmitsBlockedStatus() {
        FakeToolCallback fake = new FakeToolCallback("github_create_issue", "REAL", null);
        MeteredToolCallback mc = new MeteredToolCallback(fake, metrics);
        mc.setToolCallProgressEmitter(progress);

        mc.call("{\"title\":\"x\"}");

        assertEquals(2, sseEmitter.sent.size());
        assertTrue(sseEmitter.sent.get(0).contains("event: tool_call_start"));
        assertTrue(sseEmitter.sent.get(1).contains("event: tool_call_end"));
        assertTrue("黑名单短路应发 blocked 状态", sseEmitter.sent.get(1).contains("\"status\":\"blocked\""));
    }

    @Test
    public void approvalUnavailableDoesNotEmitToolProgress() {
        HighRiskToolRegistry registry = mock(HighRiskToolRegistry.class);
        when(registry.isHighRisk("destructive_tool")).thenReturn(true);
        HumanApprovalGate gate = mock(HumanApprovalGate.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.getHighRiskToolRegistry()).thenReturn(registry);
        when(gate.requestApproval(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(HumanApprovalGate.Decision.APPROVAL_UNAVAILABLE);
        FakeToolCallback fake = new FakeToolCallback("destructive_tool", "REAL", null);
        MeteredToolCallback mc = new MeteredToolCallback(fake, metrics);
        mc.setHumanApprovalGate(gate);
        mc.setToolCallProgressEmitter(progress);

        mc.call("{\"x\":1}");

        assertEquals("审批未通过时不应产生任何工具调用卡片事件", 0, sseEmitter.sent.size());
        assertFalse("审批未通过时不能调用真实工具", fake.invoked.get());
    }

    @Test
    public void approvedHighRiskToolStartsProgressAfterApproval() {
        HighRiskToolRegistry registry = mock(HighRiskToolRegistry.class);
        when(registry.isHighRisk("destructive_tool")).thenReturn(true);
        HumanApprovalGate gate = mock(HumanApprovalGate.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.getHighRiskToolRegistry()).thenReturn(registry);
        when(gate.requestApproval(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(HumanApprovalGate.Decision.APPROVED);

        FakeToolCallback fake = new FakeToolCallback("destructive_tool", "REAL", null);
        MeteredToolCallback mc = new MeteredToolCallback(fake, metrics);
        mc.setHumanApprovalGate(gate);
        mc.setToolCallProgressEmitter(progress);

        mc.call("{\"x\":1}");

        assertTrue("审批通过后应调用真实工具", fake.invoked.get());
        assertEquals(2, sseEmitter.sent.size());
        assertTrue(sseEmitter.sent.get(0).contains("event: tool_call_start"));
        assertTrue(sseEmitter.sent.get(1).contains("event: tool_call_end"));
        assertTrue(sseEmitter.sent.get(1).contains("\"status\":\"success\""));
    }

    @Test
    public void errorPathEmitsToolCallError() {
        RuntimeException err = new RuntimeException("upstream boom");
        FakeToolCallback fake = new FakeToolCallback("aisearch", null, err);
        // 用 returnErrorOnFailure=true 让异常被结构化包装而不是 rethrow，方便测试
        MeteredToolCallback mc = new MeteredToolCallback(fake, metrics, true);
        mc.setToolCallProgressEmitter(progress);

        mc.call("{\"q\":\"x\"}");

        assertEquals(2, sseEmitter.sent.size());
        assertTrue(sseEmitter.sent.get(0).contains("event: tool_call_start"));
        assertTrue("异常路径应发 tool_call_error 而非 tool_call_end",
                sseEmitter.sent.get(1).contains("event: tool_call_error"));
        assertTrue(sseEmitter.sent.get(1).contains("upstream boom"));
    }

    @Test
    public void nullProgressEmitterDoesNotBreakInvoke() {
        // 不调 setToolCallProgressEmitter，验证向后兼容
        FakeToolCallback fake = new FakeToolCallback("aisearch", "OK", null);
        MeteredToolCallback mc = new MeteredToolCallback(fake, metrics);

        String out = mc.call("{\"q\":\"x\"}");

        assertEquals("OK", out);
        assertEquals("无 progress 注入时不应有任何 SSE 事件被发", 0, sseEmitter.sent.size());
    }

    private HumanApprovalGate buildGate(boolean enabled, String whitelistedTool, boolean hasChannel) throws Exception {
        HumanApprovalGate gate = new HumanApprovalGate();
        HighRiskToolRegistry whitelist = new HighRiskToolRegistry();
        if (whitelistedTool != null) {
            LinkedHashSet<String> tools = new LinkedHashSet<>();
            tools.add(whitelistedTool);
            whitelist.setRequiredTools(tools);
        }
        ApprovalChannelRegistry gateChannels = new ApprovalChannelRegistry();
        if (hasChannel) gateChannels.register("test-session-1", new ResponseBodyEmitter());
        setField(gate, "enabled", enabled);
        setField(gate, "timeoutSeconds", 1);
        setField(gate, "highRiskToolRegistry", whitelist);
        setField(gate, "approvalChannelRegistry", gateChannels);
        return gate;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static class FakeToolCallback implements ToolCallback {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final String name;
        final String result;
        final RuntimeException toThrow;

        FakeToolCallback(String name, String result, RuntimeException toThrow) {
            this.name = name;
            this.result = result;
            this.toThrow = toThrow;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override
        public String call(String input) {
            invoked.set(true);
            if (toThrow != null) throw toThrow;
            return result;
        }
    }

    static class CapturingEmitter extends ResponseBodyEmitter {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(Object object) {
            sent.add(String.valueOf(object));
        }
    }
}
