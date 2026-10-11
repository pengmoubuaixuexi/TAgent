package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolCallMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class MeteredToolCallbackDelegateRefreshTest {
    @org.junit.Before public void bindOwner() {
        org.slf4j.MDC.put("userId", "alice");
        org.slf4j.MDC.put("agentId", "agent-a");
    }
    @org.junit.After public void clearOwner() { org.slf4j.MDC.clear(); }
    private static <T extends McpClientRegistry> T authorize(T registry) {
        var access = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository.class);
        org.mockito.Mockito.when(access.ownsMcp(org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        org.mockito.Mockito.when(access.mcpCapacityOwner(org.mockito.ArgumentMatchers.anyString())).thenReturn("alice");
        org.mockito.Mockito.when(access.agentMcpIds("alice", "agent-a")).thenReturn(java.util.Set.of("amap", "calc-mcp", "search-mcp"));
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "workspaceAccess", access);
        return registry;
    }


    @Test
    public void refreshesStaleDelegateFromRegistryBeforeCallingTool() {
        McpClientRegistry registry = authorize(new McpClientRegistry());
        CountingToolCallback stale = new CountingToolCallback("maps_geo", "STALE");
        CountingToolCallback fresh = new CountingToolCallback("maps_geo", "FRESH");
        registry.registerCallbacks("amap", new ToolCallback[]{fresh});

        MeteredToolCallback callback = new MeteredToolCallback(
                stale,
                new McpToolMetrics(new SimpleMeterRegistry()),
                false,
                false,
                10,
                20_000,
                false,
                true,
                2,
                1,
                registry,
                "amap"
        );

        String result = callback.call("{\"address\":\"成都\"}");

        assertEquals("FRESH", result);
        assertEquals(0, stale.calls.get());
        assertEquals(1, fresh.calls.get());
    }

    /**
     * T10 回归保护：delegate 被 refresh 从 hinted 切到 registry 的 raw callback 后，
     * getToolDefinition().description() 仍须保留 prompt hint（否则后续 LLM 轮次看不到避坑提示）。
     * 复刻 AiClientModelNode 装配链：registry 存 raw（无 hint），MeteredToolCallback 的 delegate 是 hinted。
     */
    @Test
    public void keepsHintInDescriptionAfterDelegateRefresh() {
        String hint = "precision 字段只接受数字，不要传 high/default";

        McpClientRegistry registry = authorize(new McpClientRegistry());
        // registry 注册 raw（无 hint）——对应 AiClientModelNode:121 在 hint 包装之前注册
        CountingToolCallback raw = new CountingToolCallback("calculate", "RAW");
        registry.registerCallbacks("calc-mcp", new ToolCallback[]{raw});

        // delegate = hinted（raw 外包一层 hint）——对应 AiClientModelNode:144
        HintedToolCallback hinted = new HintedToolCallback(new CountingToolCallback("calculate", "HINTED"), hint);

        MeteredToolCallback callback = new MeteredToolCallback(
                hinted,
                new McpToolMetrics(new SimpleMeterRegistry()),
                false,
                false,
                10,
                20_000,
                false,
                true,
                2,
                1,
                registry,
                "calc-mcp"
        );

        // 调用前：description 含 hint
        assertTrue("调用前 description 应含 hint",
                callback.getToolDefinition().description().contains(hint));

        // 调用触发 refreshDelegateFromRegistry → delegate 被换成 registry 的 raw（无 hint）
        String result = callback.call("{}");
        assertEquals("delegate 应已切到 registry 的 raw callback", "RAW", result);

        // 修复后：description 仍含 hint（构造时已缓存，不随 delegate 切换丢失）
        assertTrue("delegate 切到 raw 后 description 仍应保留 hint",
                callback.getToolDefinition().description().contains(hint));
    }

    @Test
    public void timeoutProbeDeadReconnectsOutsideProbePath() {
        TimeoutThenFailToolCallback stale = new TimeoutThenFailToolCallback("AIsearch");
        CountingToolCallback fresh = new CountingToolCallback("AIsearch", "FRESH_AFTER_TIMEOUT");
        TimeoutProbeRegistry registry = authorize(new TimeoutProbeRegistry(stale, fresh));

        MeteredToolCallback callback = new MeteredToolCallback(
                stale,
                new McpToolMetrics(new SimpleMeterRegistry()),
                false,
                false,
                10,
                20_000,
                false,
                true,
                2,
                1,
                registry,
                "search-mcp"
        );

        String result = callback.call("{\"query\":\"理财\"}");

        assertEquals("FRESH_AFTER_TIMEOUT", result);
        assertEquals("原始 callback 只负责第一次超时", 1, stale.calls.get());
        assertEquals("探活失败后应由外层显式重连并切换到 fresh callback", 1, fresh.calls.get());
        assertEquals("probeAfterTimeout 只做探活", 1, registry.probes.get());
        assertEquals("重建由 reconnectAfterTimeout 承担", 1, registry.timeoutReconnects.get());
    }

    @Test
    public void changedSchemaAfterIdleRecoveryNeverReceivesOldArguments() {
        McpClientRegistry registry = authorize(new McpClientRegistry());
        CountingToolCallback stale = new CountingToolCallback("calculate", "STALE");
        CountingToolCallback changed = changedSchema("calculate");
        registry.registerCallbacks("calc-mcp", new ToolCallback[]{changed});
        MeteredToolCallback callback = guarded(stale, registry, "calc-mcp");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> callback.call("{}"));

        assertTrue(failure.getMessage().contains("definition changed"));
        assertEquals(0, stale.calls.get());
        assertEquals(0, changed.calls.get());
        assertEquals("{}", callback.getToolDefinition().inputSchema());
    }

    @Test
    public void reconnectWithChangedSchemaDoesNotReplayReadOnlyCall() {
        TimeoutThenFailToolCallback stale = new TimeoutThenFailToolCallback("AIsearch");
        CountingToolCallback changed = changedSchema("AIsearch");
        TimeoutProbeRegistry registry = authorize(new TimeoutProbeRegistry(stale, changed));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guarded(stale, registry, "search-mcp").call("{}"));

        assertTrue(failure.getMessage().contains("definition changed"));
        assertEquals(1, stale.calls.get());
        assertEquals(0, changed.calls.get());
        assertEquals(1, registry.timeoutReconnects.get());
    }

    @Test
    public void sameDisplayedNameCannotSwapOriginalWireTool() {
        McpClientRegistry registry = authorize(new McpClientRegistry());
        CountingToolCallback stale = new NamedToolCallback("maps_geo", "maps-geo");
        CountingToolCallback changed = new NamedToolCallback("maps_geo", "maps_geo");
        registry.registerCallbacks("amap", new ToolCallback[]{changed});

        assertThrows(IllegalStateException.class, () -> guarded(stale, registry, "amap").call("{}"));

        assertEquals(0, stale.calls.get());
        assertEquals(0, changed.calls.get());
    }

    private static MeteredToolCallback guarded(ToolCallback callback, McpClientRegistry registry, String id) {
        return new MeteredToolCallback(callback, new McpToolMetrics(new SimpleMeterRegistry()),
                false, false, 10, 20_000, false, true, 2, 1, registry, id);
    }

    private static CountingToolCallback changedSchema(String name) {
        return new CountingToolCallback(name, "MUST_NOT_EXECUTE") {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("changed")
                        .inputSchema("{\"type\":\"object\",\"required\":[\"newField\"]}").build();
            }
        };
    }

    private static class NamedToolCallback extends CountingToolCallback implements McpClientRegistry.OriginalToolName {
        private final String original;
        NamedToolCallback(String name, String original) { super(name, "MUST_NOT_EXECUTE"); this.original = original; }
        @Override public String getOriginalToolName() { return original; }
    }

    static class CountingToolCallback implements ToolCallback {
        final String name;
        final String result;
        final AtomicInteger calls = new AtomicInteger();

        CountingToolCallback(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return new McpToolCallMetadata("search-mcp", true, false, null, false);
        }

        @Override
        public String call(String input) {
            calls.incrementAndGet();
            return result;
        }
    }

    static class TimeoutThenFailToolCallback extends CountingToolCallback {
        TimeoutThenFailToolCallback(String name) {
            super(name, "SHOULD_NOT_RETURN");
        }

        @Override
        public String call(String input) {
            calls.incrementAndGet();
            throw new RuntimeException(new TimeoutException("simulated timeout"));
        }
    }

    static class TimeoutProbeRegistry extends McpClientRegistry {
        final ToolCallback fresh;
        final ToolCallback initial;
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger timeoutReconnects = new AtomicInteger();

        TimeoutProbeRegistry(ToolCallback initial, ToolCallback fresh) {
            this.initial = initial;
            this.fresh = fresh;
        }

        @Override
        public ToolCallback getCurrentCallback(String mcpId, String toolName) {
            return initial;
        }

        @Override
        public boolean probeAfterTimeout(String mcpId, String toolName) {
            probes.incrementAndGet();
            return false;
        }

        @Override
        public ToolCallback reconnectAfterTimeout(String mcpId, String toolName) {
            timeoutReconnects.incrementAndGet();
            return fresh;
        }
    }
}
