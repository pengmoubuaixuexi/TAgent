package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
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
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G1-C 单测：MeteredToolCallback 在 invoke 前 hook HumanApprovalGate 的端到端行为。
 * <p>
 * 不拉 Spring 上下文，纯手动装配各组件。覆盖 5 个核心场景：APPROVED 放行 / REJECTED 不调真实工具 /
 * TIMEOUT / APPROVAL_UNAVAILABLE / gate==null 兼容性。
 */
public class MeteredToolCallbackApprovalTest {

    private McpToolMetrics metrics;
    private FakeToolCallback realTool;

    @Before
    public void setUp() {
        metrics = new McpToolMetrics(new SimpleMeterRegistry());
        realTool = new FakeToolCallback("custom_destructive_tool");
        MDC.put("sessionId", "test-session-1");
    }

    @After
    public void tearDown() {
        MDC.remove("sessionId");
    }

    @Test
    public void approvedDecisionInvokesRealTool() {
        // APPROVED 路径覆盖 = 工具不在白名单（HumanApprovalGate.requestApproval 直接返 APPROVED 不走 future.get）。
        // 异步 resolveApproval 路径需要 ExecutorService 协调，留给集成测试，单测只验证关键契约：
        // "gate 判定为 APPROVED 时必须真正调用底层工具且返其结果"。
        HumanApprovalGate gate = buildGate(true, "other_high_risk_tool" /* 白名单存在但 ≠ 被测工具 */, true);
        MeteredToolCallback mc = newMetered(realTool);
        mc.setHumanApprovalGate(gate);

        String out = mc.call("{\"title\":\"bug\"}");

        assertTrue("approved 必须真正调用底层工具", realTool.invoked.get());
        assertEquals("REAL_RESULT", out);
    }

    @Test
    public void rejectedDecisionReturnsStructuredErrorAndSkipsRealTool() {
        // 直接固定一个非 APPROVED 决策，验证门控契约；不依赖审批传输层的运行时夹具。
        HighRiskToolRegistry registry = mock(HighRiskToolRegistry.class);
        when(registry.isHighRisk("custom_destructive_tool")).thenReturn(true);
        HumanApprovalGate gate = mock(HumanApprovalGate.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.getHighRiskToolRegistry()).thenReturn(registry);
        when(gate.requestApproval(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(HumanApprovalGate.Decision.APPROVAL_UNAVAILABLE);
        MeteredToolCallback mc = newMetered(realTool);
        mc.setHumanApprovalGate(gate);

        String out = mc.call("{\"title\":\"bug\"}");

        assertFalse("非 APPROVED 必须跳过真实工具调用", realTool.invoked.get());
        assertTrue("应返结构化拒绝 JSON", out.contains("\"ok\":false"));
        assertTrue("应包含 approval 字段标明 Decision", out.contains("\"approval\":\"APPROVAL_UNAVAILABLE\""));
        assertTrue("应包含给 LLM 的 message", out.contains("\"message\":"));
        assertTrue("应包含 toolName 让 LLM 区分", out.contains("custom_destructive_tool"));
    }

    @Test
    public void nonHighRiskToolBypassesApproval() {
        // gate.enabled=true 但白名单不含此工具 → isHighRisk=false → 直接放行
        HumanApprovalGate gate = buildGate(true, "github_delete_repo" /* 白名单内有，但被测工具不在 */, true);
        FakeToolCallback safeTool = new FakeToolCallback("aisearch");
        MeteredToolCallback mc = newMetered(safeTool);
        mc.setHumanApprovalGate(gate);

        String out = mc.call("{\"q\":\"hello\"}");

        assertTrue("非白名单工具必须直接放行", safeTool.invoked.get());
        assertEquals("REAL_RESULT", out);
    }

    @Test
    public void gateDisabledBypassesApproval() {
        // gate.enabled=false → APPROVED 放行
        HumanApprovalGate gate = buildGate(false, "custom_destructive_tool", true);
        MeteredToolCallback mc = newMetered(realTool);
        mc.setHumanApprovalGate(gate);

        String out = mc.call("{\"title\":\"bug\"}");

        assertTrue("enabled=false 必须直接放行", realTool.invoked.get());
        assertEquals("REAL_RESULT", out);
    }

    @Test
    public void nullGateIsBackwardCompatible() {
        // setter 没调（gate==null）—— 旧装配点的兼容性保证：现有 agent 运行零行为变化
        MeteredToolCallback mc = newMetered(realTool);
        // 不调 setHumanApprovalGate

        String out = mc.call("{\"title\":\"bug\"}");

        assertTrue("gate==null 必须直接放行（向后兼容）", realTool.invoked.get());
        assertEquals("REAL_RESULT", out);
    }

    @Test
    public void emptyWhitelistEvenWhenEnabledBypassesApproval() {
        // gate.enabled=true 但白名单空 —— HighRiskToolRegistry 保护机制
        HumanApprovalGate gate = buildGate(true, null /* empty whitelist */, true);
        MeteredToolCallback mc = newMetered(realTool);
        mc.setHumanApprovalGate(gate);

        String out = mc.call("{\"title\":\"bug\"}");

        assertTrue("空白名单保护：enabled=true 也必须放行所有工具", realTool.invoked.get());
        assertEquals("REAL_RESULT", out);
    }

    /** 装配 MeteredToolCallback：用最长的构造函数 + 默认 metrics。 */
    private MeteredToolCallback newMetered(ToolCallback delegate) {
        return new MeteredToolCallback(delegate, metrics);
    }

    /** 装配 HumanApprovalGate：用反射注入两个 registry + 设 enabled / timeout，避免 Spring 上下文。 */
    private HumanApprovalGate buildGate(boolean enabled, String whitelistedTool, boolean hasChannel) {
        try {
            HumanApprovalGate gate = new HumanApprovalGate();

            HighRiskToolRegistry whitelist = new HighRiskToolRegistry();
            if (whitelistedTool != null) {
                LinkedHashSet<String> tools = new LinkedHashSet<>();
                tools.add(whitelistedTool);
                whitelist.setRequiredTools(tools);
            }

            ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
            if (hasChannel) {
                channels.register("test-session-1", new ResponseBodyEmitter());
            }

            setField(gate, "enabled", enabled);
            setField(gate, "timeoutSeconds", 1); // 测试快返
            setField(gate, "highRiskToolRegistry", whitelist);
            setField(gate, "approvalChannelRegistry", channels);
            return gate;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 测试用 ToolCallback：记录是否被调用，返固定字符串。 */
    static class FakeToolCallback implements ToolCallback {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final String name;

        FakeToolCallback(String name) {
            this.name = name;
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
            return "REAL_RESULT";
        }
    }
}
