package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Repeat with -Dtest=McpConnectionCapacityBaselineTest. No network/DB/LLM calls.
 * Reports admission/lease correctness and local wall time, not real provider throughput. */
public class McpConnectionCapacityBaselineTest {
    @Test public void boundedConcurrentCallsRejectOverloadAndRecoverAfterIdleRelease() throws Exception {
        final int owners=4,perOwner=2,capacity=owners*perOwner,burst=64;
        McpClientRegistry registry=new McpClientRegistry();
        ReflectionTestUtils.setField(registry,"maxConnections",capacity);
        ReflectionTestUtils.setField(registry,"maxPerUser",perOwner);
        var access=mock(IWorkspaceAccessRepository.class);ReflectionTestUtils.setField(registry,"workspaceAccess",access);
        List<String> ids=IntStream.range(0,capacity).mapToObj(i->"u"+(i/perOwner)+"-"+i).toList();
        Function<String,String> owner=id->id.substring(0,id.indexOf('-'));
        when(access.mcpCapacityOwner(anyString())).thenAnswer(inv->owner.apply(inv.getArgument(0)));
        when(access.ownsMcp(anyString(),anyString())).thenAnswer(inv->inv.getArgument(0).equals(owner.apply(inv.getArgument(1))));
        when(access.agentMcpIds(anyString(),eq("baseline-agent"))).thenAnswer(inv->ids.stream().filter(id->owner.apply(id).equals(inv.getArgument(0))).collect(Collectors.toSet()));
        AtomicInteger created=new AtomicInteger(),closed=new AtomicInteger(),live=new AtomicInteger(),peak=new AtomicInteger(),externalCalls=new AtomicInteger(),rejected=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(capacity),release=new CountDownLatch(1);
        Function<AiClientToolMcpVO,McpSyncClient> factory=config->{
            McpSyncClient transport=mock(McpSyncClient.class);created.incrementAndGet();peak.accumulateAndGet(live.incrementAndGet(),Math::max);
            when(transport.getClientInfo()).thenReturn(new McpSchema.Implementation("baseline","1"));
            when(transport.getServerInfo()).thenReturn(new McpSchema.Implementation("mock-provider","1"));
            var definition=McpSchema.Tool.builder().name("read").description("simulated readonly provider")
                    .inputSchema(new McpSchema.JsonSchema("object",Map.of(),List.of(),false,null,null))
                    .annotations(new McpSchema.ToolAnnotations(null,true,false,null,true,false)).build();
            when(transport.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(definition),null));
            when(transport.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(inv->{
                externalCalls.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
                return McpSchema.CallToolResult.builder().addTextContent("baseline-ok").isError(false).build();
            });
            doAnswer(inv->{closed.incrementAndGet();live.decrementAndGet();return null;}).when(transport).close();
            return transport;
        };
        Map<String,ToolCallback> callbacks=new LinkedHashMap<>();ExecutorService workers=Executors.newFixedThreadPool(16);
        long started=System.nanoTime(),overloadStarted=0,overloadNanos=0,recoveryNanos=0;
        try{
            for(String id:ids){var config=AiClientToolMcpVO.builder().mcpId(id).mcpName(id).build();var transport=registry.ensureClient(config,factory);
                var tools=registry.getToolCallbacksForAssembly(id,transport);registry.registerCallbacks(id,tools);callbacks.put(id,tools[0]);}
            List<Future<String>> held=new ArrayList<>();
            for(String id:ids)held.add(workers.submit(()->callbacks.get(id).call("{}",new ToolContext(Map.of("userId",owner.apply(id),"agentId","baseline-agent")))));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(capacity,live.get());
            overloadStarted=System.nanoTime();List<Future<?>> attempts=new ArrayList<>();
            for(int i=0;i<burst;i++){String id="u"+(i%owners)+"-new-"+i;attempts.add(workers.submit(()->{
                try{registry.ensureClient(AiClientToolMcpVO.builder().mcpId(id).mcpName(id).build(),factory);fail("active capacity must reject admission");}
                catch(McpConnectionPool.McpCapacityException expected){rejected.incrementAndGet();}
            }));}
            for(Future<?> attempt:attempts)attempt.get(5,TimeUnit.SECONDS);overloadNanos=System.nanoTime()-overloadStarted;
            assertEquals(burst,rejected.get());assertEquals(capacity,created.get());assertEquals(0,closed.get());
            release.countDown();for(Future<String> call:held)assertTrue(call.get(5,TimeUnit.SECONDS).contains("baseline-ok"));
            McpConnectionPool pool=(McpConnectionPool)ReflectionTestUtils.getField(registry,"pool");
            for(String id:ids){assertEquals(0,pool.activeLeases(id));pool.current(id).lastUsed=0;}registry.evictIdleConnections();
            assertEquals(capacity,closed.get());assertEquals(0,live.get());
            long recoveryStarted=System.nanoTime();List<Future<String>> recovered=new ArrayList<>();
            for(String id:ids)recovered.add(workers.submit(()->callbacks.get(id).call("{}",new ToolContext(Map.of("userId",owner.apply(id),"agentId","baseline-agent")))));
            for(Future<String> call:recovered)assertTrue(call.get(5,TimeUnit.SECONDS).contains("baseline-ok"));
            recoveryNanos=System.nanoTime()-recoveryStarted;assertEquals(capacity*2,externalCalls.get());assertTrue(peak.get()<=capacity);
        }finally{release.countDown();workers.shutdownNow();registry.shutdown();}
        assertEquals(created.get(),closed.get());assertEquals(0,live.get());
        Map<String,Object> report=new LinkedHashMap<>();report.put("generatedAt",Instant.now().toString());
        report.put("environment",System.getProperty("os.name")+" / Java "+System.getProperty("java.version"));
        report.put("provider","mock: no network, database or LLM requests");report.put("scope","real registry, admission, lease, raw callback, idle eviction and lazy restoration");
        report.put("owners",owners);report.put("globalCapacity",capacity);report.put("perOwnerCapacity",perOwner);report.put("workerThreads",16);
        report.put("heldConcurrentCalls",capacity);report.put("overloadAttempts",burst);report.put("rejected",rejected.get());
        report.put("providerCalls",externalCalls.get());report.put("createdResources",created.get());report.put("releasedResources",closed.get());
        report.put("peakLiveResources",peak.get());report.put("remainingResources",live.get());
        report.put("overloadMillis",TimeUnit.NANOSECONDS.toMillis(overloadNanos));report.put("recoveryMillis",TimeUnit.NANOSECONDS.toMillis(recoveryNanos));
        report.put("totalMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));report.put("status","PASS");
        Path output=Path.of(System.getProperty("tagent.mcp.baseline.output","target/mcp-capacity-baseline.json")).toAbsolutePath();
        Files.createDirectories(output.getParent());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println("MCP_CAPACITY_BASELINE "+output);
    }
}
