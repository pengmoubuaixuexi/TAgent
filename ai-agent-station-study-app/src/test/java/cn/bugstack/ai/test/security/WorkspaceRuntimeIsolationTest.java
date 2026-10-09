package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolNameGuard;
import cn.bugstack.ai.domain.agent.service.router.AgentPoolSelector;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkspaceRuntimeIsolationTest {
    @After public void clearIdentity() { MDC.clear(); }

    private ToolCallback tool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("fixture").inputSchema("{\"type\":\"object\"}").build());
        return callback;
    }

    @Test public void sameToolNameNeverSelectsAnotherConnection() {
        McpClientRegistry registry = new McpClientRegistry();
        ToolCallback a = tool("search"), b = tool("search");
        registry.registerCallbacks("alice-mcp", new ToolCallback[]{a});
        registry.registerCallbacks("bob-mcp", new ToolCallback[]{b});
        assertSame(a, registry.getCurrentCallback("alice-mcp", "search"));
        assertSame(b, registry.getCurrentCallback("bob-mcp", "search"));
        assertNull(registry.getCurrentCallback("search"));
        assertNull(registry.getMcpIdForTool("search"));
        registry.unregister("alice-mcp");
        assertNull(registry.getCurrentCallback("alice-mcp", "search"));
        assertSame(b, registry.getCurrentCallback("bob-mcp", "search"));
    }

    @Test public void refreshedToolListRemovesOnlyThisConnectionsStaleTools() {
        McpClientRegistry registry = new McpClientRegistry();
        registry.registerCallbacks("a", new ToolCallback[]{tool("removed"), tool("kept")});
        ToolCallback b = tool("removed");
        registry.registerCallbacks("b", new ToolCallback[]{b});
        registry.registerCallbacks("a", new ToolCallback[]{tool("kept")});
        assertNull(registry.getCurrentCallback("a", "removed"));
        assertSame(b, registry.getCurrentCallback("b", "removed"));
    }

    @Test public void oneAgentRejectsAmbiguousToolsIncludingCaseVariants() {
        var error = assertThrows(McpToolNameGuard.CollisionException.class,
                () -> McpToolNameGuard.requireUnique(List.of(
                        new McpToolNameGuard.Binding("alice-calendar", "search"),
                        new McpToolNameGuard.Binding("alice-web", "SEARCH"))));
        assertTrue(error.getMessage().contains("alice-calendar"));
        assertTrue(error.getMessage().contains("alice-web"));
        assertTrue(error.getMessage().contains("取消其中一个连接"));
    }

    @Test public void differentUsersMayEachUseTheSameToolName() {
        assertDoesNotThrow(() -> McpToolNameGuard.requireUnique(List.of(
                new McpToolNameGuard.Binding("alice-mcp", "search"))));
        assertDoesNotThrow(() -> McpToolNameGuard.requireUnique(List.of(
                new McpToolNameGuard.Binding("bob-mcp", "search"))));
    }

    @Test public void personalHealthSnapshotDoesNotProbeOtherOwners() {
        McpClientRegistry registry = new McpClientRegistry();
        McpSyncClient a = mock(McpSyncClient.class), b = mock(McpSyncClient.class);
        registry.register("a", AiClientToolMcpVO.builder().mcpName("a").build(), a, ignored -> a);
        registry.register("b", AiClientToolMcpVO.builder().mcpName("b").build(), b, ignored -> b);
        registry.recordSuccess("a");
        assertEquals(List.of("a"), registry.snapshotSelected(Set.of("a")).stream()
                .map(McpClientRegistry.ClientHealthSnapshot::mcpId).toList());
        verifyNoInteractions(b);
        assertTrue(registry.snapshotSelected(Set.of()).isEmpty());
    }

    @Test public void callbacksRequireBothOwnerAndAgentAssignment() {
        McpClientRegistry registry = new McpClientRegistry();
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        ReflectionTestUtils.setField(registry, "workspaceAccess", access);
        when(access.ownsMcp("alice", "mcp-a")).thenReturn(true);
        when(access.agentMcpIds("alice", "agent-a")).thenReturn(Set.of("mcp-a"));
        assertDoesNotThrow(() -> registry.assertAccessible("mcp-a", "alice", "agent-a"));
        assertThrows(IllegalStateException.class, () -> registry.assertAccessible("mcp-a", "bob", "agent-a"));
        assertThrows(IllegalStateException.class, () -> registry.assertAccessible("mcp-a", "alice", "builder"));
        assertThrows(IllegalStateException.class, () -> registry.assertAccessible("mcp-a", null));
    }

    @Test public void dynamicMatchCacheIsScopedAndRechecksRevokedAccess() {
        McpToolCatalogService service = new McpToolCatalogService();
        IAgentRepository repository = mock(IAgentRepository.class);
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        IToolVectorStore vectors = mock(IToolVectorStore.class);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "workspaceAccess", access);
        ReflectionTestUtils.setField(service, "toolVectorStore", vectors);
        ReflectionTestUtils.setField(service, "matchCacheTtlMs", 600_000L);
        when(vectors.isAvailable()).thenReturn(true);
        for (String owner : List.of("alice", "bob")) {
            String mcp = owner + "-mcp";
            when(access.agentMcpIds(owner, "agent-" + owner)).thenReturn(Set.of(mcp));
            when(access.ownsMcp(owner, mcp)).thenReturn(true);
            when(vectors.searchOwned(anyString(), anySet(), anyInt(), eq(Set.of(mcp))))
                    .thenReturn(List.of(AiMcpToolCatalogVO.builder().mcpId(mcp).toolName("search").build()));
        }
        MDC.put("userId", "alice"); MDC.put("agentId", "agent-alice");
        assertEquals("alice-mcp", service.previewMatchedTools("search", "", Set.of()).get(0).getMcpId());
        MDC.put("userId", "bob"); MDC.put("agentId", "agent-bob");
        assertEquals("bob-mcp", service.previewMatchedTools("search", "", Set.of()).get(0).getMcpId());
        MDC.put("userId", "alice"); MDC.put("agentId", "agent-alice");
        when(access.ownsMcp("alice", "alice-mcp")).thenReturn(false);
        assertTrue(service.previewMatchedTools("search", "", Set.of()).isEmpty());
        MDC.clear();
        assertTrue(service.previewMatchedTools("search", "", Set.of()).isEmpty());
    }

    @Test public void cachedDynamicCallbackCannotBypassOwnership() {
        McpToolCatalogService service = new McpToolCatalogService();
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        ReflectionTestUtils.setField(service, "workspaceAccess", access);
        @SuppressWarnings("unchecked") Map<String, ToolCallback> cache =
                (Map<String, ToolCallback>) ReflectionTestUtils.getField(service, "dynamicWrapperCache");
        cache.put("bob-mcp::search", tool("search"));
        MDC.put("userId", "alice"); MDC.put("agentId", "agent-a");
        assertNull(ReflectionTestUtils.invokeMethod(service, "ensureToolCallback",
                AiMcpToolCatalogVO.builder().mcpId("bob-mcp").toolName("search").build()));
    }

    @Test public void staleCatalogScanCannotReconnectDeletedMcpConfiguration() {
        McpToolCatalogService service = new McpToolCatalogService();
        IAgentRepository repo = mock(IAgentRepository.class);
        McpClientRegistry registry = mock(McpClientRegistry.class);
        var factory = mock(cn.bugstack.ai.domain.agent.service.armory.node.AiClientToolMcpNode.class);
        ReflectionTestUtils.setField(service, "repository", repo);
        ReflectionTestUtils.setField(service, "mcpClientRegistry", registry);
        ReflectionTestUtils.setField(service, "aiClientToolMcpNode", factory);
        ToolCallback[] callbacks = ReflectionTestUtils.invokeMethod(service, "ensureMcpCallbacks",
                AiClientToolMcpVO.builder().mcpId("deleted-mcp").mcpName("old configuration").build());
        assertEquals(0, callbacks.length);
        verify(repo).queryAiClientToolMcpVOByMcpId("deleted-mcp");
        verifyNoInteractions(registry, factory);
    }

    @Test public void dispatcherRejectsForgedAgentBeforeArmingOrCreatingRun() {
        AgentDispatchDispatchService service = new AgentDispatchDispatchService();
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        IAgentRepository repo = mock(IAgentRepository.class);
        ReflectionTestUtils.setField(service, "workspaceAccess", access);
        ReflectionTestUtils.setField(service, "repository", repo);
        assertThrows(Exception.class, () -> service.dispatch(ExecuteCommandEntity.builder()
                .userId("alice").aiAgentId("bob-agent").sessionId("alice-session").build(), new ResponseBodyEmitter()));
        verifyNoInteractions(repo);
    }

    @Test public void selectorFiltersBeforeSingleAgentFastPath() {
        AgentPoolSelector selector = new AgentPoolSelector();
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        ReflectionTestUtils.setField(selector, "workspaceAccess", access);
        when(access.ownedAgentIds("alice")).thenReturn(Set.of("agent-a"));
        MDC.put("userId", "alice");
        assertNull(selector.select("hello", List.of(AiAgentVO.builder().agentId("agent-b").build())));
        assertEquals("agent-a", selector.select("hello", List.of(AiAgentVO.builder().agentId("agent-b").build(),
                AiAgentVO.builder().agentId("agent-a").build())));
    }

    @Test public void connectionInvalidationWaitsForOldAssemblyAndForcesFreshAssembly() throws Exception {
        ArmoryService armory = new ArmoryService();
        IAgentRepository repo = mock(IAgentRepository.class);
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        DefaultArmoryStrategyFactory factory = mock(DefaultArmoryStrategyFactory.class);
        @SuppressWarnings("unchecked") StrategyHandler<cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity,
                DefaultArmoryStrategyFactory.DynamicContext, String> handler = mock(StrategyHandler.class);
        ReflectionTestUtils.setField(armory, "repository", repo);
        ReflectionTestUtils.setField(armory, "workspaceAccess", access);
        ReflectionTestUtils.setField(armory, "defaultArmoryStrategyFactory", factory);
        when(access.ownsAgent("alice", "agent-a")).thenReturn(true);
        when(repo.queryAiAgentClientsByAgentId("agent-a")).thenReturn(List.of(
                AiAgentClientFlowConfigVO.builder().clientId("client-a").build()));
        when(factory.armoryStrategyHandler()).thenReturn(handler);
        CountDownLatch oldAssemblyEntered = new CountDownLatch(1), releaseOldAssembly = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1), cleanupRan = new CountDownLatch(1);
        AtomicInteger configuration = new AtomicInteger(1), runtimeConnection = new AtomicInteger();
        when(handler.apply(any(), any())).thenAnswer(invocation -> {
            int capturedVersion = configuration.get();
            if (capturedVersion == 1) {
                oldAssemblyEntered.countDown();
                assertTrue(releaseOldAssembly.await(5, TimeUnit.SECONDS));
            }
            runtimeConnection.set(capturedVersion);
            return "ok";
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var old = workers.submit(() -> {
                MDC.put("userId", "alice");
                try { armory.ensureArmed("agent-a"); } finally { MDC.clear(); }
            });
            assertTrue(oldAssemblyEntered.await(5, TimeUnit.SECONDS));
            configuration.set(2); // Committed configuration while an old connection is initializing.
            var invalidated = workers.submit(() -> {
                cleanupStarted.countDown();
                armory.invalidateAgents(Set.of("agent-a"), () -> {
                    runtimeConnection.set(0);
                    cleanupRan.countDown();
                });
            });
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
            assertFalse(cleanupRan.await(100, TimeUnit.MILLISECONDS));
            releaseOldAssembly.countDown();
            old.get(5, TimeUnit.SECONDS);
            invalidated.get(5, TimeUnit.SECONDS);
            assertEquals(0, runtimeConnection.get());
            assertFalse(armory.isAgentArmed("agent-a"));
            MDC.put("userId", "alice");
            armory.ensureArmed("agent-a");
            assertEquals(2, runtimeConnection.get());
        } finally {
            releaseOldAssembly.countDown();
            workers.shutdownNow();
        }
    }
}
