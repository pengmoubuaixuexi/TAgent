package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.router.ToolDescriptionTranslator;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SDK callbacks over mocked transports; never contacts an MCP or model provider. */
public class McpToolNamingTest {
    private final IAgentRepository repository = mock(IAgentRepository.class);
    private final IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
    private final IToolVectorStore vectors = mock(IToolVectorStore.class);
    private final ToolDescriptionTranslator translator = mock(ToolDescriptionTranslator.class);
    private final McpClientRegistry registry = new McpClientRegistry();
    private final McpToolCatalogService service = new McpToolCatalogService();
    private final Map<String, Map<String, AiMcpToolCatalogVO>> catalog = new LinkedHashMap<>();

    @Before public void setup() {
        MDC.put("userId", "alice"); MDC.put("agentId", "agent");
        when(access.agentMcpIds("alice", "agent")).thenReturn(Set.of("a", "b"));
        when(access.ownsMcp(eq("alice"), anyString())).thenAnswer(inv -> Set.of("a", "b").contains(inv.getArgument(1)));
        when(access.mcpCapacityOwner(anyString())).thenReturn("alice");
        when(access.publicMcpOrigins("alice")).thenReturn(Map.of());
        ReflectionTestUtils.setField(registry, "workspaceAccess", access);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "workspaceAccess", access);
        ReflectionTestUtils.setField(service, "mcpClientRegistry", registry);
        ReflectionTestUtils.setField(service, "mcpToolMetrics", mock(McpToolMetrics.class));
        ReflectionTestUtils.setField(service, "toolVectorStore", vectors);
        ReflectionTestUtils.setField(service, "toolDescriptionTranslator", translator);
        ReflectionTestUtils.setField(service, "matchCacheTtlMs", 600_000L);
        when(repository.queryAiClientToolMcpVOByMcpId(anyString())).thenAnswer(inv -> config(inv.getArgument(0)));
        when(repository.queryMcpToolCatalogByMcpId(anyString())).thenAnswer(inv ->
                new ArrayList<>(catalog.getOrDefault(inv.getArgument(0), Map.of()).values()));
        when(repository.queryEnabledMcpToolCatalog()).thenAnswer(inv ->
                catalog.values().stream().flatMap(rows -> rows.values().stream()).toList());
        doAnswer(inv -> {
            List<AiMcpToolCatalogVO> rows = inv.getArgument(0);
            rows.forEach(row -> catalog.computeIfAbsent(row.getMcpId(), ignored -> new LinkedHashMap<>()).put(row.getToolName(), row));
            return null;
        }).when(repository).upsertMcpToolCatalog(anyList());
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            List<String> names = inv.getArgument(1);
            names.forEach(name -> catalog.getOrDefault(id, Map.of()).remove(name));
            return null;
        }).when(repository).deleteMcpToolCatalog(anyString(), anyList());
    }

    @After public void cleanup() { registry.shutdown(); MDC.clear(); }

    private AiClientToolMcpVO config(String id) {
        return AiClientToolMcpVO.builder().mcpId(id).mcpName("railway").build();
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).description("railway tickets")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, null, true, false)).build();
    }

    private McpSyncClient client(String... names) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("test-client", "1"));
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("test-server", "1"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(java.util.Arrays.stream(names).map(this::tool).toList(), null));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(new McpSchema.CallToolResult("ok", false));
        return client;
    }

    private ToolCallback register(String id, McpSyncClient client) throws Exception {
        registry.ensureClient(config(id), ignored -> client);
        ToolCallback[] callbacks = registry.getToolCallbacksForAssembly(id, client);
        registry.registerCallbacks(id, callbacks);
        return callbacks[0];
    }

    private AiMcpToolCatalogVO row(String id, String name, String schema) {
        return AiMcpToolCatalogVO.builder().mcpId(id).mcpName("railway").toolName(name)
                .toolDescription("railway tickets").toolDescriptionZh("铁路余票查询")
                .toolIntentZh("查询铁路余票").inputSchemaJson(schema).enabled(1).build();
    }

    private void save(AiMcpToolCatalogVO row) {
        catalog.computeIfAbsent(row.getMcpId(), ignored -> new LinkedHashMap<>()).put(row.getToolName(), row);
    }

    private ToolCallback materialize(String id, String name) {
        return ReflectionTestUtils.invokeMethod(service, "ensureToolCallback", row(id, name, "{}"));
    }

    @Test public void sdkFormattedNameRetainsOriginalWireNameThroughLeasedCallback() throws Exception {
        McpSyncClient transport = client("get-tickets");
        ToolCallback callback = register("a", transport);
        assertEquals("get_tickets", callback.getToolDefinition().name());
        assertEquals("get-tickets", McpClientRegistry.originalToolName(callback));
        assertSame(callback, registry.getCurrentCallback("a", "get-tickets"));
        assertSame(callback, registry.getCurrentCallback("a", "get_tickets"));
        assertNull(registry.getCurrentCallback("a", "get/tickets"));
        assertTrue(callback.call("{}").contains("ok"));
        verify(transport).callTool(argThat(request -> "get-tickets".equals(request.name())));
    }

    @Test public void originalAliasesAreScopedAndDisappearWhenToolsAreRemoved() throws Exception {
        ToolCallback alice = register("a", client("get-tickets"));
        ToolCallback other = register("b", client("get-tickets"));
        assertSame(alice, registry.getCurrentCallback("a", "get-tickets"));
        assertSame(other, registry.getCurrentCallback("b", "get-tickets"));
        assertNull(registry.getCurrentCallback("get-tickets"));
        assertNull(registry.getMcpIdForTool("get_tickets"));
        registry.registerCallbacks("a", new ToolCallback[0]);
        assertNull(registry.getCurrentCallback("a", "get-tickets"));
        assertSame(other, registry.getCurrentCallback("get-tickets"));
        registry.unregister("b");
        assertNull(registry.getCurrentCallback("get-tickets"));
    }

    @Test public void ambiguousOriginalAndCanonicalNamesCannotReplaceAGoodGeneration() throws Exception {
        ToolCallback prior = register("a", client("get-tickets"));
        McpSyncClient transport = client("unused");
        ToolCallback one = SyncMcpToolCallback.builder().mcpClient(transport).tool(tool("remote-one"))
                .prefixedToolName("model_one").build();
        ToolCallback two = SyncMcpToolCallback.builder().mcpClient(transport).tool(tool("model_one"))
                .prefixedToolName("model_two").build();
        assertThrows(IllegalStateException.class, () -> registry.registerCallbacks("a", new ToolCallback[]{one, two}));
        assertSame(prior, registry.getCurrentCallback("a", "get-tickets"));
        assertNull(registry.getCurrentCallback("a", "model_one"));
    }

    @Test public void reconnectResolvesOriginalNameAndRefreshesItsCachedWrapper() throws Exception {
        McpSyncClient first = client("get-tickets"), next = client("get-tickets");
        AtomicInteger creations = new AtomicInteger();
        registry.ensureClient(config("a"), ignored -> creations.getAndIncrement() == 0 ? first : next);
        ToolCallback[] initial = registry.getToolCallbacksForAssembly("a", first);
        registry.registerCallbacks("a", initial);
        ToolCallback oldWrapper = materialize("a", "get-tickets");
        ToolCallback refreshed = registry.forceReconnect("a", "get-tickets");
        assertNotNull(refreshed);
        assertSame(refreshed, registry.getCurrentCallback("a", "get_tickets"));
        assertNotSame(oldWrapper, materialize("a", "get-tickets"));
        assertSame(materialize("a", "get-tickets"), materialize("a", "get_tickets"));
        assertTrue(initial[0].call("{}").contains("ok"));
        verify(first, never()).callTool(any(McpSchema.CallToolRequest.class));
        verify(next).callTool(argThat(request -> "get-tickets".equals(request.name())));
    }

    @Test public void oldCatalogNameMaterializesAfterRealSdkDiscoveryWithoutRenamingOrEnrichment() throws Exception {
        McpSyncClient transport = client("get-tickets");
        registry.ensureClient(config("a"), ignored -> transport);
        String schema = SyncMcpToolCallback.builder().mcpClient(transport).tool(tool("get-tickets"))
                .prefixedToolName("get-tickets").build().getToolDefinition().inputSchema();
        save(row("a", "get-tickets", schema));
        ToolCallback resolved = materialize("a", "get-tickets");
        assertNotNull(resolved);
        assertEquals("get_tickets", resolved.getToolDefinition().name());
        assertEquals(Set.of("get-tickets"), catalog.get("a").keySet());
        verify(repository, never()).deleteMcpToolCatalog(anyString(), anyList());
        verify(repository, never()).upsertMcpToolCatalog(anyList());
        verifyNoInteractions(translator, vectors);
        verify(transport, times(1)).listTools();
        verify(transport, never()).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test public void catalogMigrationPreservesTranslationsAndReconcilesStaleScopedVectors() throws Exception {
        ToolCallback callback = register("a", client("get-tickets"));
        register("b", client("get-tickets"));
        save(row("a", "get_tickets", callback.getToolDefinition().inputSchema()));
        save(row("b", "get_tickets", callback.getToolDefinition().inputSchema()));
        when(vectors.searchOwned(anyString(), anySet(), anyInt(), anySet())).thenReturn(List.of(
                row("a", "get_tickets", "stale"), row("foreign", "get-tickets", "foreign")));
        assertEquals("get_tickets", service.previewMatchedTools("train", "", Set.of()).get(0).getToolName());
        // Lazy discovery updates the relational catalog without an embedding/syncAll call.
        ReflectionTestUtils.invokeMethod(service, "upsertCatalog", config("a"), registry.currentCallbacks("a"));
        assertEquals(Set.of("get-tickets"), catalog.get("a").keySet());
        assertEquals(Set.of("get_tickets"), catalog.get("b").keySet());
        assertEquals("铁路余票查询", catalog.get("a").get("get-tickets").getToolDescriptionZh());
        var migrated = service.previewMatchedTools("train", "", Set.of());
        assertEquals(1, migrated.size());
        assertEquals("a", migrated.get(0).getMcpId());
        assertEquals("get-tickets", migrated.get(0).getToolName());
        assertEquals(callback.getToolDefinition().inputSchema(), migrated.get(0).getInputSchemaJson());
        verifyNoInteractions(translator);
        verify(vectors, never()).syncAll(anyList());
        verify(vectors, times(2)).searchOwned(anyString(), anySet(), anyInt(), anySet());
        var order = inOrder(repository);
        order.verify(repository).upsertMcpToolCatalog(anyList());
        order.verify(repository).deleteMcpToolCatalog("a", List.of("get_tickets"));
        verify(repository, never()).deleteMcpToolCatalog(eq("b"), anyList());
    }

    @Test public void failedCatalogMigrationNeverDeletesTheOnlyStoredDefinition() throws Exception {
        ToolCallback callback = register("a", client("get-tickets"));
        save(row("a", "get_tickets", callback.getToolDefinition().inputSchema()));
        doThrow(new IllegalStateException("database unavailable")).when(repository).upsertMcpToolCatalog(anyList());
        assertThrows(IllegalStateException.class, () -> service.refreshMcpCatalog("a"));
        assertEquals(Set.of("get_tickets"), catalog.get("a").keySet());
        verify(repository, never()).deleteMcpToolCatalog(anyString(), anyList());
        verifyNoInteractions(translator, vectors);
    }
}
