package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline transport mocks exercise admission, the actual callback wrapper and revocation. */
public class McpConnectionLifecycleTest {
    private final IWorkspaceAccessRepository access=mock(IWorkspaceAccessRepository.class);
    private McpClientRegistry registry(int global,int owner) {
        McpClientRegistry registry=new McpClientRegistry();
        ReflectionTestUtils.setField(registry,"workspaceAccess",access);
        ReflectionTestUtils.setField(registry,"maxConnections",global);
        ReflectionTestUtils.setField(registry,"maxPerUser",owner);
        when(access.mcpCapacityOwner(anyString())).thenReturn("alice");
        when(access.ownsMcp(eq("alice"),anyString())).thenReturn(true);
        when(access.agentMcpIds("alice","agent")).thenReturn(Set.of("a","b"));
        return registry;
    }
    private AiClientToolMcpVO config(String id){return AiClientToolMcpVO.builder().mcpId(id).mcpName(id).build();}
    private McpSyncClient client() {
        McpSyncClient client=mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("test-client","1"));
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("test-server","1"));
        McpSchema.Tool tool=McpSchema.Tool.builder().name("lookup").description("lookup")
                .inputSchema(new McpSchema.JsonSchema("object",Map.of(),List.of(),false,null,null))
                .annotations(new McpSchema.ToolAnnotations(null,true,false,null,true,false)).build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool),null));
        return client;
    }
    private McpConnectionPool pool(McpClientRegistry registry){return (McpConnectionPool)ReflectionTestUtils.getField(registry,"pool");}
    private void old(McpClientRegistry registry,String id){pool(registry).current(id).lastUsed=0;}
    private ToolCallback callback(McpClientRegistry registry,String id,McpSyncClient client) throws Exception {
        ToolCallback[] tools=registry.getToolCallbacksForAssembly(id,client);registry.registerCallbacks(id,tools);return tools[0];
    }
    private ToolContext context(){return new ToolContext(Map.of("userId","alice","agentId","agent"));}

    @Test public void pendingAndRetiredGenerationsRemainChargedUntilAbortOrFinalRelease() {
        McpConnectionPool pool=new McpConnectionPool();
        var pending=pool.reserve("a","alice",2,1);
        assertThrows(McpConnectionPool.McpCapacityException.class,()->pool.reserve("b","alice",2,1));
        pool.abort(pending);McpSyncClient first=client();var live=pool.reserve("a","alice",2,1);pool.commit(live,first);
        var lease=pool.acquireCurrent("a",true);assertNull(pool.retire("a",first,false));
        assertThrows(McpConnectionPool.McpCapacityException.class,()->pool.reserve("b","alice",2,1));
        verify(first,never()).close();lease.close();lease.close();verify(first,times(1)).close();
        assertNotNull(pool.reserve("b","alice",2,1));
    }

    @Test public void missingOwnerAndThrowingFactoryNeverLeakAnAdmissionSlot() {
        McpClientRegistry registry=registry(1,1);AtomicInteger creations=new AtomicInteger();
        when(access.mcpCapacityOwner("a")).thenReturn(null);
        assertThrows(IllegalStateException.class,()->registry.ensureClient(config("a"),c->{creations.incrementAndGet();return client();}));
        assertEquals(0,creations.get());
        when(access.mcpCapacityOwner("a")).thenReturn("alice");
        assertThrows(IllegalStateException.class,()->registry.ensureClient(config("a"),c->{throw new IllegalStateException("initialize failed");}));
        assertThrows(AssertionError.class,()->registry.ensureClient(config("a"),c->{throw new AssertionError("factory error");}));
        assertNotNull(registry.ensureClient(config("a"),c->client()));registry.shutdown();
    }

    @Test public void capacityCannotEvictAnInFlightCallButReclaimsItAfterRelease() {
        McpClientRegistry registry=registry(1,1);McpSyncClient first=client(),second=client();AtomicInteger creations=new AtomicInteger();
        registry.ensureClient(config("a"),c->first);
        try(var lease=registry.borrowCall("a")){
            old(registry,"a");registry.evictIdleConnections();verify(first,never()).close();
            assertThrows(IllegalStateException.class,()->registry.ensureClient(config("b"),c->{creations.incrementAndGet();return second;}));
            assertEquals(0,creations.get());
        }
        registry.ensureClient(config("b"),c->second);verify(first,times(1)).close();assertSame(second,registry.getClient("b"));registry.shutdown();
    }

    @Test public void idleEvictionRestoresTheSameConnectionFactoryAndRefreshesCallbacks() throws Exception {
        McpClientRegistry registry=registry(2,2);McpSyncClient first=client(),restored=client(),other=client();AtomicInteger aCreated=new AtomicInteger();
        Function<AiClientToolMcpVO,McpSyncClient> aFactory=c->aCreated.getAndIncrement()==0?first:restored;
        registry.ensureClient(config("a"),aFactory);ToolCallback before=callback(registry,"a",first);
        registry.ensureClient(config("b"),c->other);old(registry,"a");registry.evictIdleConnections();
        verify(first).close();assertNull(registry.getClient("a"));assertNotNull(registry.lifecycle("a").get("evictedAt"));
        try(var lease=registry.borrowCall("a")){
            assertSame(restored,lease.client());assertEquals(2,aCreated.get());
            assertNotSame(before,registry.getCurrentCallback("a",before.getToolDefinition().name()));
            assertEquals("a",((McpToolCallMetadata)registry.getCurrentCallback("a",before.getToolDefinition().name()).getToolMetadata()).mcpId());
        }
        registry.shutdown();
    }

    @Test public void callbackAlreadyRunningSurvivesRevokeButNewCallsCannotResurrectIt() throws Exception {
        McpClientRegistry registry=registry(2,2);McpSyncClient transport=client();registry.ensureClient(config("a"),c->transport);
        ToolCallback tool=callback(registry,"a",transport);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(transport.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(inv->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));throw new IllegalStateException("mock call completed");});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<?> call=executor.submit(()->tool.call("{}",context()));assertTrue(entered.await(3,TimeUnit.SECONDS));
            old(registry,"a");registry.evictIdleConnections();registry.unregister("a");verify(transport,never()).close();
            release.countDown();assertThrows(ExecutionException.class,()->call.get(3,TimeUnit.SECONDS));verify(transport,times(1)).close();
            when(access.mcpCapacityOwner("a")).thenReturn(null);when(access.ownsMcp("alice","a")).thenReturn(false);
            assertThrows(IllegalStateException.class,()->tool.call("{}",context()));verify(transport,times(1)).callTool(any(McpSchema.CallToolRequest.class));
        }finally{release.countDown();executor.shutdownNow();registry.shutdown();}
    }

    @Test public void existingRawCallbackRechecksOwnerAndAgentBindingBeforeAnyExternalAttempt() throws Exception {
        McpClientRegistry registry=registry(2,2);McpSyncClient transport=client();registry.ensureClient(config("a"),c->transport);ToolCallback tool=callback(registry,"a",transport);
        when(access.agentMcpIds("alice","agent")).thenReturn(Set.of());
        assertThrows(IllegalStateException.class,()->tool.call("{}",context()));verify(transport,never()).callTool(any(McpSchema.CallToolRequest.class));
        when(access.agentMcpIds("alice","agent")).thenReturn(Set.of("a"));when(access.ownsMcp("alice","a")).thenReturn(false);
        assertThrows(IllegalStateException.class,()->tool.call("{}",context()));verify(transport,never()).callTool(any(McpSchema.CallToolRequest.class));registry.shutdown();
    }

    @Test public void nestedLeasesKeepGenerationAndFreshDeletedConfigPreventsReuse() {
        McpClientRegistry registry=registry(2,2);McpSyncClient first=client(),second=client();registry.ensureClient(config("a"),c->first);
        try(var outer=registry.borrowCall("a")){
            pool(registry).retire("a",first,false);
            registry.ensureClient(config("a"),c->second);
            try(var nested=registry.borrowCall("a")){assertSame(first,nested.client());assertSame(first,outer.client());}
            verify(first,never()).close();
        }
        verify(first,times(1)).close();
        IAgentRepository repository=mock(IAgentRepository.class);ReflectionTestUtils.setField(registry,"agentRepository",repository);
        assertThrows(IllegalStateException.class,()->registry.borrowCall("a"));registry.shutdown();
    }

    @Test public void retireBeforeClientMapRemovalDoesNotReturnTheClosedGeneration() {
        McpClientRegistry registry=registry(2,2);McpSyncClient first=client(),second=client();AtomicInteger factory=new AtomicInteger();
        registry.ensureClient(config("a"),c->factory.getAndIncrement()==0?first:second);
        // Deterministic interleaving: sweeper retired the pool entry but has not removed the map.
        assertSame(first,pool(registry).retire("a",first,true));first.close();
        try(var borrowed=registry.borrowCall("a")){assertSame(second,borrowed.client());}
        assertEquals(2,factory.get());registry.shutdown();
    }

    @Test public void assemblyNeverListsToolsOnAnAlreadyRetiredManagedGeneration() throws Exception {
        McpClientRegistry registry=registry(2,2);McpSyncClient first=client(),second=client();AtomicInteger factory=new AtomicInteger();
        registry.ensureClient(config("a"),c->factory.getAndIncrement()==0?first:second);callback(registry,"a",first);
        assertSame(first,pool(registry).retire("a",first,true));first.close();
        assertNull(registry.getCurrentCallback("a","lookup"));
        assertEquals(1,registry.getToolCallbacksForAssembly("a",first).length);
        verify(first,times(1)).listTools();verify(second,times(1)).listTools();registry.shutdown();
    }

    @Test public void oldCallbackCannotSendToRecoveredToolWithChangedSchema() throws Exception {
        McpClientRegistry registry=registry(2,2);McpSyncClient first=client(),next=client();AtomicInteger created=new AtomicInteger();
        McpSchema.Tool changed=McpSchema.Tool.builder().name("lookup").description("lookup")
                .inputSchema(new McpSchema.JsonSchema("object",Map.of(),List.of("query"),false,null,null)).build();
        when(next.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(changed),null));
        try {
            registry.ensureClient(config("a"),c->created.getAndIncrement()==0?first:next);
            ToolCallback before=callback(registry,"a",first);old(registry,"a");registry.evictIdleConnections();
            assertThrows(IllegalStateException.class,()->before.call("{}",context()));
            assertEquals(2,created.get());
            verify(first,never()).callTool(any(McpSchema.CallToolRequest.class));
            verify(next,never()).callTool(any(McpSchema.CallToolRequest.class));
        } finally { registry.shutdown(); }
    }

    @Test public void timedOutBackgroundProbeKeepsItsLeaseUntilTheTransportReturns() throws Exception {
        McpClientRegistry registry=registry(2,2);McpSyncClient transport=client();registry.ensureClient(config("a"),c->transport);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        when(transport.listTools()).thenAnswer(inv->{entered.countDown();try{assertTrue(release.await(4,TimeUnit.SECONDS));return new McpSchema.ListToolsResult(List.of(),null);}finally{finished.countDown();}});
        try{
            assertFalse((Boolean)ReflectionTestUtils.invokeMethod(registry,"probe",transport));assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals(1,pool(registry).activeLeases("a"));old(registry,"a");registry.evictIdleConnections();verify(transport,never()).close();
            release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(pool(registry).activeLeases("a")>0&&System.nanoTime()<until)Thread.yield();
            assertEquals(0,pool(registry).activeLeases("a"));registry.evictIdleConnections();verify(transport,times(1)).close();
        }finally{release.countDown();registry.shutdown();}
    }

    @Test public void nextCallDetectsSourceRotationEvenIfExplicitInvalidationWasMissed() throws Exception {
        McpClientRegistry registry=registry(3,3);
        IAgentRepository repository=mock(IAgentRepository.class);
        ReflectionTestUtils.setField(registry,"agentRepository",repository);
        var old=config("a");old.setTransportConfig("old-synthetic-token");
        var rotated=config("a");rotated.setTransportConfig("rotated-synthetic-token");
        when(repository.queryAiClientToolMcpVOByMcpId("a")).thenReturn(old);
        McpSyncClient first=client(),second=client(),unrelated=client();
        AtomicInteger creates=new AtomicInteger();
        registry.ensureClient(old,c->{creates.incrementAndGet();return "old-synthetic-token".equals(c.getTransportConfig())?first:second;});
        ToolCallback previous=callback(registry,"a",first);
        when(repository.queryAiClientToolMcpVOByMcpId("b")).thenReturn(config("b"));
        registry.ensureClient(config("b"),c->unrelated); // a must retain its own factory during rotation.
        when(repository.queryAiClientToolMcpVOByMcpId("a")).thenReturn(rotated);
        try(var lease=registry.borrowCall("a")) {
            assertSame(second,lease.client());
            assertNotSame(previous,registry.getCurrentCallback("a",previous.getToolDefinition().name()));
            assertEquals(2,creates.get());
        }
        verify(first,times(1)).close();verify(first,never()).callTool(any(McpSchema.CallToolRequest.class));
        assertSame(unrelated,registry.getClient("b"));registry.shutdown();
    }

    @Test public void sourceRotationDoesNotInterruptOrReplayAnAlreadyLeasedCall() {
        McpClientRegistry registry=registry(3,3);
        IAgentRepository repository=mock(IAgentRepository.class);
        ReflectionTestUtils.setField(registry,"agentRepository",repository);
        var old=config("a");old.setTransportConfig("old");
        var rotated=config("a");rotated.setTransportConfig("new");
        when(repository.queryAiClientToolMcpVOByMcpId("a")).thenReturn(old);
        McpSyncClient first=client(),second=client();
        registry.ensureClient(old,c->first);
        try(var outer=registry.borrowCall("a")) {
            when(repository.queryAiClientToolMcpVOByMcpId("a")).thenReturn(rotated);
            assertSame(second,registry.ensureClient(old,c->{assertEquals("new",c.getTransportConfig());return second;}));
            try(var nested=registry.borrowCall("a")) { assertSame(first,nested.client()); }
            assertSame(first,outer.client());verify(first,never()).close();
        }
        verify(first,times(1)).close();assertSame(second,registry.getClient("a"));registry.shutdown();
    }
}
