package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.execute.common.DynamicToolUnavailableException;
import cn.bugstack.ai.domain.agent.service.execute.common.InMemoryResolvedToolLeaseStore;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.ResolvedToolLease;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolPromptHintRegistry;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

    @org.junit.After public void clearIdentity() { org.slf4j.MDC.clear(); }

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
        when(vectorStore.searchOwned(anyString(), any(), anyInt(), any())).thenReturn(List.of(catalogTool));
        when(registry.hasClient("mcp-search")).thenReturn(true);
        when(registry.getCurrentCallback("mcp-search", "web_search")).thenReturn(callback);

        List<ToolCallback> first = service.resolveDynamicToolCallbacks(
                "run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of());
        Thread.sleep(5); // matchCacheTtlMs=0 时确保旧路径会过期；lease 路径不会再查向量。
        List<ToolCallback> second = service.resolveDynamicToolCallbacks(
                "run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of());

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(1, store.listLeases("run-1").size());
        verify(vectorStore, times(1)).searchOwned(anyString(), any(), anyInt(), any());
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
        when(vectorStore.searchOwned(anyString(), any(), anyInt(), any())).thenReturn(List.of(catalogTool));
        when(registry.hasClient("mcp-search")).thenReturn(true);
        when(registry.getCurrentCallback("mcp-search", "web_search")).thenReturn(new StubToolCallback("web_search", "{}"));

        service.resolveDynamicToolCallbacks("run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of());
        String identity = store.listLeases("run-1").get(0).toolIdentity();
        when(registry.hasClient("mcp-search")).thenReturn(false);

        DynamicToolUnavailableException ex = assertThrows(DynamicToolUnavailableException.class,
                () -> service.resolveDynamicToolCallbacks("run-1", "s1", "client-1", "查询网页资料", "用户问题", List.of()));

        assertEquals(identity, ex.getToolIdentity());
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN, ex.getReason());
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN,
                store.find("run-1", identity).orElseThrow().availability());
        verify(vectorStore, times(1)).searchOwned(anyString(), any(), anyInt(), any());
    }

    @Test
    public void hintedToolLeaseKeepsActualSchemaHashAndCanBeReusedByNextNode() throws Exception {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        IToolVectorStore vectors = mock(IToolVectorStore.class);
        McpClientRegistry registry = mock(McpClientRegistry.class);
        McpToolCatalogService service = service(store, vectors, registry);
        ToolPromptHintRegistry hints = new ToolPromptHintRegistry();
        hints.initDefaults();
        ReflectionTestUtils.setField(service, "toolPromptHintRegistry", hints);
        String schema = "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}";
        ToolCallback raw = new StubToolCallback("AIsearch", schema);
        when(vectors.searchOwned(anyString(), any(), anyInt(), any())).thenReturn(List.of(
                AiMcpToolCatalogVO.builder().mcpId("mcp-search").toolName("AIsearch")
                        .inputSchemaJson(schema).build()));
        when(registry.hasClient("mcp-search")).thenReturn(true);
        when(registry.getCurrentCallback("mcp-search", "AIsearch")).thenReturn(raw);

        List<ToolCallback> first = service.resolveDynamicToolCallbacks(
                "run-1", "s1", "request_tool", "酒店搜索", "北京三日游", List.of());

        assertEquals(1, first.size());
        // Exercise the real hint wrapper's non-public anonymous ToolDefinition, not a mock.
        assertTrue(first.get(0).getToolDefinition().description().contains(hints.getHint("AIsearch")));
        assertEquals(schema, first.get(0).getToolDefinition().inputSchema());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(schema.getBytes(StandardCharsets.UTF_8)));
        String identity = "mcp-search:AIsearch:" + hash;
        assertEquals(identity, store.listLeases("run-1").get(0).toolIdentity());

        List<ToolCallback> second = service.resolveDynamicToolCallbacks(
                "run-1", "s1", "planning-client", "酒店搜索", "北京三日游", List.of());

        assertEquals(1, second.size());
        assertEquals(schema, second.get(0).getToolDefinition().inputSchema());
        assertEquals(ResolvedToolLease.Availability.AVAILABLE,
                store.find("run-1", identity).orElseThrow().availability());
        verify(vectors, times(1)).searchOwned(anyString(), any(), anyInt(), any());
    }

    @Test
    public void hintedToolLeaseStillRejectsRealSchemaChange() {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        IToolVectorStore vectors = mock(IToolVectorStore.class);
        McpClientRegistry registry = mock(McpClientRegistry.class);
        McpToolCatalogService service = service(store, vectors, registry);
        ToolPromptHintRegistry hints = new ToolPromptHintRegistry();
        hints.initDefaults();
        ReflectionTestUtils.setField(service, "toolPromptHintRegistry", hints);
        String schema = "{\"type\":\"object\"}";
        when(vectors.searchOwned(anyString(), any(), anyInt(), any())).thenReturn(List.of(
                AiMcpToolCatalogVO.builder().mcpId("mcp-search").toolName("AIsearch")
                        .inputSchemaJson(schema).build()));
        when(registry.hasClient("mcp-search")).thenReturn(true);
        when(registry.getCurrentCallback("mcp-search", "AIsearch"))
                .thenReturn(new StubToolCallback("AIsearch", schema));
        service.resolveDynamicToolCallbacks("run-1", "s1", "request_tool",
                "酒店搜索", "北京三日游", List.of());
        String identity = store.listLeases("run-1").get(0).toolIdentity();
        when(registry.getCurrentCallback("mcp-search", "AIsearch"))
                .thenReturn(new StubToolCallback("AIsearch",
                        "{\"type\":\"object\",\"required\":[\"query\"]}"));

        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class,
                () -> service.resolveDynamicToolCallbacks("run-1", "s1", "planning-client",
                        "酒店搜索", "北京三日游", List.of()));

        assertEquals(identity, error.getToolIdentity());
        assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        assertEquals(ResolvedToolLease.Availability.INVALIDATED,
                store.find("run-1", identity).orElseThrow().availability());
        verify(vectors, times(1)).searchOwned(anyString(), any(), anyInt(), any());
    }

    @Test
    public void revokedLeaseCannotRematchCachedToolOrRecoverOnNextResolve() {
        InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
        IToolVectorStore vectors = mock(IToolVectorStore.class);
        McpClientRegistry registry = mock(McpClientRegistry.class);
        McpToolCatalogService service = service(store, vectors, registry);
        var access = (cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository)
                ReflectionTestUtils.getField(service, "workspaceAccess");
        String identity = "mcp-search:web_search:hash";
        store.createOrMerge("run-1", "s1", "查询网页资料", identity);
        when(access.ownsMcp("alice", "mcp-search")).thenReturn(false);

        for (int attempt = 0; attempt < 2; attempt++) {
            DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class,
                    () -> service.resolveDynamicToolCallbacks("run-1", "s1", "client-1",
                            "查询网页资料", "用户问题", List.of()));
            assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
            assertEquals(identity, error.getToolIdentity());
        }
        assertEquals(ResolvedToolLease.Availability.INVALIDATED,
                store.find("run-1", identity).orElseThrow().availability());
        org.mockito.Mockito.verifyNoInteractions(vectors, registry);
    }

    private static McpToolCatalogService service(InMemoryResolvedToolLeaseStore store,
                                                 IToolVectorStore vectorStore,
                                                 McpClientRegistry registry) {
        McpToolCatalogService service = new McpToolCatalogService();
        ReflectionTestUtils.setField(service, "repository", mock(cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository.class));
        var access = mock(cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository.class);
        when(access.agentMcpIds("alice", "agent-a")).thenReturn(Set.of("mcp-search"));
        when(access.ownsMcp("alice", "mcp-search")).thenReturn(true);
        ReflectionTestUtils.setField(service, "workspaceAccess", access);
        org.slf4j.MDC.put("userId", "alice");
        org.slf4j.MDC.put("agentId", "agent-a");
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
