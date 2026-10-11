package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import org.junit.Test;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class ReasoningCallIsolationTest {
    @Test public void immediateAnswerAndNoThinkCannotBorrowAnotherParallelNodesScope() throws Exception {
        var filter=new ReasoningContentFilter();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<2;i++) { final String expected="node-"+i;
                jobs.add(pool.submit(()->{
                    org.slf4j.MDC.put("runId","shared-run");
                    try {
                        try(var scope=ReasoningContentFilter.scopeCall("same-session","shared-run");var noThink=ReasoningContentFilter.scopeNoThinking("same-session")) {
                            var key=context().get("agent.reasoning_call");
                            var event="data: {\"choices\":[{\"delta\":{\"reasoning_content\":\""+expected+"\"}}]}\n\n";
                            filter.filter(org.springframework.web.reactive.function.client.ClientRequest.create(org.springframework.http.HttpMethod.GET,java.net.URI.create("http://unused")).build(),r->reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK).body(event).build()))
                                    .flatMap(r->r.bodyToMono(String.class)).block();
                            Method noThinking=ReasoningContentFilter.class.getDeclaredMethod("isNoThinkSession",String.class);noThinking.setAccessible(true);
                            assertEquals(true,noThinking.invoke(null,key));assertEquals(false,noThinking.invoke(null,"same-session"));
                            barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);
                        }
                        return ReasoningContentFilter.getLatestReasoning("same-session");
                    } finally {ReasoningContentFilter.clearLatestReasoning("same-session");org.slf4j.MDC.clear();}
                }));
            }
            assertEquals("node-0",jobs.get(0).get());assertEquals("node-1",jobs.get(1).get());
        } finally {pool.shutdownNow();ReasoningContentFilter.clearRun("shared-run");}
    }
    @Test public void unicodeSplitAcrossNetworkBuffersAndCrLfKeepsExactReasoning() throws Exception {
        var filter=new ReasoningContentFilter();
        var data="data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"北京路线\",\"tool_calls\":[{\"index\":0,\"id\":\"abc\"}]}}]}\r\n\r\n";
        byte[] bytes=data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var frames=reactor.core.publisher.Flux.range(0,bytes.length).<org.springframework.core.io.buffer.DataBuffer>map(i -> org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(new byte[]{bytes[i]}));
        try(var scope=ReasoningContentFilter.scopeCall("s","unicode")) {
            String key=context().get("agent.reasoning_call");
            var response=filter.filter(org.springframework.web.reactive.function.client.ClientRequest.create(org.springframework.http.HttpMethod.GET,java.net.URI.create("http://unused")).build(),
                    request->reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK).body(frames).build())).block();
            response.bodyToMono(String.class).block();
            var field=ReasoningContentFilter.class.getDeclaredField("toolReasonings");field.setAccessible(true);
            var cache=(Map<?,?>)field.get(filter);
            assertEquals("北京路线",((Map<?,?>)cache.get(key)).get("abc"));
        }
    }
    @Test public void unreadablePostNeverBypassesBudgetOrSendsExternalRequest() {
        var filter=new ReasoningContentFilter();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var request=org.springframework.web.reactive.function.client.ClientRequest.create(org.springframework.http.HttpMethod.POST,java.net.URI.create("http://unused")).build();
        assertThrows(RuntimeException.class,()->filter.filter(request, req->{calls.incrementAndGet();return reactor.core.publisher.Mono.empty();}).subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block());
        assertEquals(0,calls.get());
    }
    @SuppressWarnings("unchecked")
    private Map<String,String> context() throws Exception {
        Method method = ReasoningContentFilter.class.getDeclaredMethod("captureContext");
        method.setAccessible(true);
        return (Map<String,String>) method.invoke(null);
    }
    @Test public void sameRunCallsHaveDistinctScopesAndNestedScopeRestoresParent() throws Exception {
        try (var outer = ReasoningContentFilter.scopeCall("s", "run")) {
            var a = context();
            try (var inner = ReasoningContentFilter.scopeCall("s", "run")) {
                var b = context();
                assertEquals(a.get("agent.reasoning_run"), b.get("agent.reasoning_run"));
                assertNotEquals(a.get("agent.reasoning_call"), b.get("agent.reasoning_call"));
            }
            assertEquals(a, context());
        }
    }
    @Test public void reorderingOrTrimmingHistoryDoesNotShiftReasoning() throws Exception {
        var filter = new ReasoningContentFilter();
        Method inject = ReasoningContentFilter.class.getDeclaredMethod("injectReasoningInto", Map.class, Map.class, String.class);
        inject.setAccessible(true);
        var later = message("B"); var earlier = message("A"); var unknown = message("foreign");
        var request = new HashMap<String,Object>();
        request.put("messages", List.of(later, earlier, unknown));
        inject.invoke(filter, request, Map.of("A", "reason-A", "B", "reason-B"), "session");
        assertEquals("reason-B", later.get("reasoning_content"));
        assertEquals("reason-A", earlier.get("reasoning_content"));
        assertEquals("[reasoning unavailable]", unknown.get("reasoning_content"));
    }
    @Test public void existingProviderReasoningIsPreserved() throws Exception {
        var filter = new ReasoningContentFilter();
        Method inject = ReasoningContentFilter.class.getDeclaredMethod("injectReasoningInto", Map.class, Map.class, String.class);
        inject.setAccessible(true);
        var message = message("A"); message.put("reasoning_content", "already bound");
        inject.invoke(filter, Map.of("messages", List.of(message)), Map.of("A", "other"), "s");
        assertEquals("already bound", message.get("reasoning_content"));
    }
    private Map<String,Object> message(String callId) {
        return new HashMap<>(Map.of("role","assistant", "tool_calls", List.of(Map.of("id",callId))));
    }
}
