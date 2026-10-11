package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.armory.node.AiClientToolMcpNode;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real registry + catalog integration with offline SDK transports: no DB, model or tools/call. */
public class McpCatalogRecoveryTest {
    private final IAgentRepository repository = mock(IAgentRepository.class);
    private final IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
    private final AiClientToolMcpNode node = mock(AiClientToolMcpNode.class);
    private final IToolVectorStore vectors = mock(IToolVectorStore.class);
    private final McpClientRegistry registry = new McpClientRegistry();
    private final McpToolCatalogService catalog = new McpToolCatalogService();
    private final InMemoryResolvedToolLeaseStore store = new InMemoryResolvedToolLeaseStore();
    private final AiClientToolMcpVO config = AiClientToolMcpVO.builder().mcpId("alice-mcp").mcpName("search").build();
    private McpSyncClient initial;
    private ToolCallback initialCallback;
    private AiMcpToolCatalogVO row;
    private String identity;

    @Before public void setup() throws Exception {
        MDC.put("userId", "alice"); MDC.put("agentId", "agent");
        when(access.ownsMcp("alice", "alice-mcp")).thenReturn(true);
        when(access.agentMcpIds("alice", "agent")).thenReturn(Set.of("alice-mcp"));
        when(access.mcpCapacityOwner("alice-mcp")).thenReturn("alice");
        when(repository.queryAiClientToolMcpVOByMcpId("alice-mcp")).thenReturn(config);
        ReflectionTestUtils.setField(registry, "workspaceAccess", access);
        ReflectionTestUtils.setField(registry, "agentRepository", repository);
        ReflectionTestUtils.setField(catalog, "repository", repository);
        ReflectionTestUtils.setField(catalog, "workspaceAccess", access);
        ReflectionTestUtils.setField(catalog, "mcpClientRegistry", registry);
        ReflectionTestUtils.setField(catalog, "aiClientToolMcpNode", node);
        ReflectionTestUtils.setField(catalog, "toolVectorStore", vectors);
        ReflectionTestUtils.setField(catalog, "resolvedToolLeaseStore", store);
        ReflectionTestUtils.setField(catalog, "mcpToolMetrics", mock(McpToolMetrics.class));
        initial = client("web-search", List.of());
    }

    // Real registry discovery creates the production LeasedCallback around a fake transport.
    private void initialize() throws Exception {
        when(node.createMcpSyncClient(config)).thenReturn(initial);
        registry.ensureClient(config, node::createMcpSyncClient);
        ToolCallback[] callbacks = registry.getToolCallbacksForAssembly("alice-mcp", initial);
        registry.registerCallbacks("alice-mcp", callbacks);
        initialCallback = callbacks[0];
        String schema = initialCallback.getToolDefinition().inputSchema();
        row = AiMcpToolCatalogVO.builder().mcpId("alice-mcp").mcpName("search")
                .toolName("web-search").toolDescription("search web").toolDescriptionZh("检索网页")
                .toolIntentZh("网页查询").inputSchemaJson(schema).enabled(1).build();
        when(repository.queryMcpToolCatalogByMcpId("alice-mcp")).thenReturn(List.of(row));
        identity = "alice-mcp:" + McpClientRegistry.originalToolName(initialCallback) + ":"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(schema.getBytes(StandardCharsets.UTF_8)));
        store.createOrMerge("run", "session", "网页查询", identity);
    }

    @After public void cleanup() { registry.shutdown(); MDC.clear(); }

    private McpSyncClient client(String name, List<String> required) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("client", "1"));
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("server", "1"));
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).description("search web")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), required, false, null, null)).build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        return client;
    }

    private void evict() {
        McpConnectionPool pool = (McpConnectionPool) ReflectionTestUtils.getField(registry, "pool");
        pool.current("alice-mcp").lastUsed = 0;
        registry.evictIdleConnections();
        assertFalse(registry.hasClient("alice-mcp"));
        assertNull(registry.getCurrentCallback("alice-mcp", "web-search"));
        assertEquals(0, registry.currentCallbacks("alice-mcp").length);
    }

    private List<ToolCallback> resolve() {
        return catalog.resolveDynamicToolCallbacks("run", "session", "next-node", "网页查询", "用户问题", List.of());
    }

    @Test public void leaseRestoresSameAuthorizedToolAfterIdleEvictionWithoutBusinessReplay() throws Exception {
        initialize(); evict(); McpSyncClient next = client("web-search", List.of());
        when(node.createMcpSyncClient(config)).thenReturn(next);
        List<ToolCallback> result = resolve();
        assertEquals(1, result.size());
        assertEquals(initialCallback.getToolDefinition().inputSchema(), result.get(0).getToolDefinition().inputSchema());
        assertSame(next, registry.getClient("alice-mcp"));
        assertEquals(identity, store.listLeases("run").get(0).toolIdentity());
        assertEquals(ResolvedToolLease.Availability.AVAILABLE, store.listLeases("run").get(0).availability());
        verify(initial).close(); verify(next).listTools();
        verify(initial, never()).callTool(any(McpSchema.CallToolRequest.class));
        verify(next, never()).callTool(any(McpSchema.CallToolRequest.class));
        verifyNoInteractions(vectors);
    }

    @Test public void cachedCatalogHitMustDiscoverNewGenerationBeforeReportingAssemblySuccess() throws Exception {
        initialize();
        ToolCallback before = ReflectionTestUtils.invokeMethod(catalog, "ensureToolCallback", row);
        evict(); McpSyncClient next = client("web-search", List.of());
        when(node.createMcpSyncClient(config)).thenReturn(next);
        ToolCallback after = ReflectionTestUtils.invokeMethod(catalog, "ensureToolCallback", row);
        assertNotNull(after); assertNotSame(before, after); assertSame(next, registry.getClient("alice-mcp"));
        verify(next).listTools(); verify(next, never()).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test public void temporaryRecoveryFailureCanRetrySameIdentityWithoutVectorRematch() throws Exception {
        initialize(); evict(); McpSyncClient next = client("web-search", List.of());
        when(node.createMcpSyncClient(config)).thenThrow(new IllegalStateException("offline")).thenReturn(next);
        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN, error.getReason());
        assertEquals(ResolvedToolLease.Availability.MCP_DOWN, store.listLeases("run").get(0).availability());
        assertEquals(1, resolve().size());
        assertEquals(identity, store.listLeases("run").get(0).toolIdentity());
        assertEquals(ResolvedToolLease.Availability.AVAILABLE, store.listLeases("run").get(0).availability());
        verifyNoInteractions(vectors); verify(next, never()).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test public void recoveredSchemaChangeRemainsInvalidatedAndDoesNotRematch() throws Exception {
        initialize(); evict(); McpSyncClient next = client("web-search", List.of("query"));
        when(node.createMcpSyncClient(config)).thenReturn(next);
        for (int attempt = 0; attempt < 2; attempt++) {
            DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
            assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        }
        verify(next).listTools(); verify(next, never()).callTool(any(McpSchema.CallToolRequest.class));
        verifyNoInteractions(vectors);
    }

    @Test public void removedToolIsNotReplacedWithAnotherDiscoveredTool() throws Exception {
        initialize(); evict(); McpSyncClient next = client("other-search", List.of());
        when(node.createMcpSyncClient(config)).thenReturn(next);
        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
        assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        assertEquals(identity, error.getToolIdentity());
        verifyNoInteractions(vectors); verify(next, never()).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test public void normalizedNameCollisionCannotChangePinnedWireTool() throws Exception {
        initialize();
        String actualIdentity = ReflectionTestUtils.invokeMethod(catalog, "toolIdentity", row, initialCallback);
        assertEquals(identity, actualIdentity);
        evict(); McpSyncClient next = client("web_search", List.of());
        when(node.createMcpSyncClient(config)).thenReturn(next);
        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
        assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        verify(next, never()).callTool(any(McpSchema.CallToolRequest.class)); verifyNoInteractions(vectors);
    }

    @Test public void revokedConnectionCannotBeRecreatedFromOldLease() throws Exception {
        initialize(); evict(); when(access.ownsMcp("alice", "alice-mcp")).thenReturn(false);
        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
        assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        verify(node, times(1)).createMcpSyncClient(config); verifyNoInteractions(vectors);
    }

    @Test public void permissionRevokedDuringDiscoveryCannotReturnRecoveredLease() throws Exception {
        initialize(); evict(); McpSyncClient next = client("web-search", List.of());
        McpSchema.ListToolsResult discovered = next.listTools();
        clearInvocations(next);
        when(next.listTools()).thenAnswer(invocation -> {
            when(access.ownsMcp("alice", "alice-mcp")).thenReturn(false);
            return discovered;
        });
        when(node.createMcpSyncClient(config)).thenReturn(next);
        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
        assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        verify(next, never()).callTool(any(McpSchema.CallToolRequest.class)); verifyNoInteractions(vectors);
    }

    @Test public void anotherUserCannotRecoverOwnersOldLease() throws Exception {
        initialize(); evict(); MDC.put("userId", "bob");
        DynamicToolUnavailableException error = assertThrows(DynamicToolUnavailableException.class, this::resolve);
        assertEquals(ResolvedToolLease.Availability.INVALIDATED, error.getReason());
        verify(node, times(1)).createMcpSyncClient(config); verifyNoInteractions(vectors);
    }
}
