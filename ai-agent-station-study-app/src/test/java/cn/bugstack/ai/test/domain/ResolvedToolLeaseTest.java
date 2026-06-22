package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.execute.common.DynamicToolUnavailableException;
import cn.bugstack.ai.domain.agent.service.execute.common.InMemoryResolvedToolLeaseStore;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.ResolvedToolLease;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-A1：ResolvedToolLease 契约测试。
 *
 * <p>不连 DB/MCP/网络；用 mock 锁住 run 级 lease 的核心语义：
 * 首次 NL need 走向量匹配并建 lease，后续同 run 同 need 从 lease materialize，
 * MCP 下线时抛 typed signal 且不 silent replacement。
 */
public class ResolvedToolLeaseTest {

    @Test
    public void storeIsRunIsolatedAndCleanupRemovesOnlyThatRun() {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        store.createOrMerge("run-1", "s1", "查网页", "mcp:search:hash");
        store.createOrMerge("run-2", "s1", "查网页", "mcp:search:hash");

        assertEquals(1, store.listLeases("run-1").size());
        assertEquals(1, store.listLeases("run-2").size());

        store.cleanupRun("run-1");

        assertTrue(store.listLeases("run-1").isEmpty());
        assertEquals(1, store.listLeases("run-2").size());
    }

    @Test
    public void storeInvalidationRejectsAvailableReasonAndCanRecoverOnSuccessfulMerge() {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        store.createOrMerge("run-1", "s1", "查网页", "mcp:search:hash");
        store.markInvalidated("run-1", "mcp:search:hash", ResolvedToolLease.Availability.AVAILABLE);
        assertEquals(ResolvedToolLease.Availability.AVAILABLE,
                store.find("run-1", "mcp:search:hash").orElseThrow().availability());

        store.markInvalidated("run-1", "mcp:search:hash", ResolvedToolLease.Availability.MCP_DOWN);
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN,
                store.find("run-1", "mcp:search:hash").orElseThrow().availability());

        store.createOrMerge("run-1", "s1", "查网页", "mcp:search:hash");
        assertEquals(ResolvedToolLease.Availability.AVAILABLE,
                store.find("run-1", "mcp:search:hash").orElseThrow().availability());
    }

    @Test
    public void catalogSecondResolveMaterializesLeaseWithoutRepeatingVectorSearch() throws Exception {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        IToolVectorStore vectorStore = mock(IToolVectorStore.class);
        McpClientRegistry registry = mock(McpClientRegistry.class);
        McpToolCatalogService service = service(store, vectorStore, registry);

        AiMcpToolCatalogVO catalogTool = AiMcpToolCatalogVO.builder()
                .mcpId("mcp-search")
                .mcpName("search")
                .toolName("web_search")
                .toolDescription("search web")
                .inputSchemaJson("{\"type\":\"object\"}")
                .build();
        ToolCallback callback = new StubToolCallback("web_search", "{\"type\":\"object\"}");
        when(vectorStore.isAvailable()).thenReturn(true);
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(catalogTool));
        when(registry.hasClient("mcp-search")).thenReturn(true);
        when(registry.getCurrentCallback("web_search")).thenReturn(callback);

        List<ToolCallback> first = service.resolveDynamicToolCallbacks(
                "run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of());
        Thread.sleep(5); // matchCacheTtlMs=0 时确保旧路径会过期；lease 路径不会再查向量。
        List<ToolCallback> second = service.resolveDynamicToolCallbacks(
                "run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of());

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(1, store.listLeases("run-1").size());
        verify(vectorStore, times(1)).search(anyString(), any(), anyInt());
    }

    @Test
    public void catalogMcpDownMarksLeaseAndThrowsTypedSignal() {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        IToolVectorStore vectorStore = mock(IToolVectorStore.class);
        McpClientRegistry registry = mock(McpClientRegistry.class);
        McpToolCatalogService service = service(store, vectorStore, registry);

        AiMcpToolCatalogVO catalogTool = AiMcpToolCatalogVO.builder()
                .mcpId("mcp-search")
                .toolName("web_search")
                .inputSchemaJson("{}")
                .build();
        when(vectorStore.isAvailable()).thenReturn(true);
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(catalogTool));
        when(registry.hasClient("mcp-search")).thenReturn(true);
        when(registry.getCurrentCallback("web_search")).thenReturn(new StubToolCallback("web_search", "{}"));

        service.resolveDynamicToolCallbacks("run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of());
        String identity = store.listLeases("run-1").get(0).toolIdentity();
        when(registry.hasClient("mcp-search")).thenReturn(false);

        DynamicToolUnavailableException ex = assertThrows(DynamicToolUnavailableException.class,
                () -> service.resolveDynamicToolCallbacks("run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of()));

        assertEquals(identity, ex.getToolIdentity());
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN, ex.getReason());
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN,
                store.find("run-1", identity).orElseThrow().availability());
    }

    private static McpToolCatalogService service(InMemoryResolvedToolLeaseStore store,
                                                 IToolVectorStore vectorStore,
                                                 McpClientRegistry registry) {
        McpToolCatalogService service = new McpToolCatalogService();
        ReflectionTestUtils.setField(service, "resolvedToolLeaseStore", store);
        ReflectionTestUtils.setField(service, "toolVectorStore", vectorStore);
        ReflectionTestUtils.setField(service, "mcpClientRegistry", registry);
        ReflectionTestUtils.setField(service, "perNeedTopK", 2);
        ReflectionTestUtils.setField(service, "maxExtraToolsPerRequest", 6);
        ReflectionTestUtils.setField(service, "matchCacheTtlMs", 0L);
        ReflectionTestUtils.setField(service, "autoRefreshCatalogEnabled", false);
        return service;
    }

    private static class StubToolCallback implements ToolCallback {
        private final String name;
        private final String schema;

        private StubToolCallback(String name, String schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("stub")
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override
        public String call(String input) {
            return "ok";
        }
    }
}
