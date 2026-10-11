package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.model.*;
import org.junit.*;
import org.springframework.http.*;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ModelStreamAccountingTest {
    final Recorder recorder=new Recorder();String run;
    @Before public void setup(){ModelUsageLedger.install(recorder);run=UUID.randomUUID().toString();RunBudgetRegistry.start(run,"alice",new RunBudgetRegistry.Limits(Duration.ofMinutes(1),5,5,100000));}
    @After public void cleanup(){ModelUsageLedger.uninstall(recorder);RunBudgetRegistry.finish(run);ReasoningContentFilter.clearRun(run);}
    ClientRequest request(){return ClientRequest.create(HttpMethod.POST,URI.create("https://provider.test/v1/chat/completions")).header("Content-Type","application/json")
            .body(BodyInserters.fromValue(Map.of("model","test-model","messages",List.of(Map.of("role","user","content","hello"))))).build();}
    ReasoningContentFilter filter(){return new ReasoningContentFilter().withUsageContext(ModelUsageContext.api("api","MAIN","https://provider.test"));}
    @Test public void streamedUsageSettlesExactlyOnceOnBlockingWorkerWithTrustedOwner() throws Exception {
        try(var scope=ReasoningContentFilter.scopeCall("session",run)){
            String body=filter().filter(request(),r->Mono.just(ClientResponse.create(HttpStatus.OK).body("data: {\"usage\":{\"total_tokens\":123},\"choices\":[]}").build()))
                    .flatMap(r->r.bodyToMono(String.class)).block(Duration.ofSeconds(5));
            assertTrue(body.contains("123"));assertTrue(recorder.done.await(2,TimeUnit.SECONDS));
            assertEquals(Long.valueOf(123),recorder.actual);assertEquals("SUCCESS",recorder.outcome);assertEquals(1,recorder.settles);
            assertEquals("alice",recorder.attempt.userId());assertEquals(run,recorder.attempt.runId());assertTrue(recorder.thread.startsWith("boundedElastic-"));
        }
    }
    @Test public void synchronousExchangeFailureDoesNotLeaveAnUnsettledAttempt() throws Exception {
        try(var scope=ReasoningContentFilter.scopeCall("session",run)){
            assertThrows(RuntimeException.class,()->filter().filter(request(),r->{throw new IllegalStateException("connection unavailable");}).block(Duration.ofSeconds(5)));
            assertTrue(recorder.done.await(3,TimeUnit.SECONDS));assertEquals("ERROR",recorder.outcome);assertNull(recorder.actual);assertEquals(1,recorder.settles);
        }
    }
    @Test public void cancelBeforeResponseKeepsUnknownUsageInsteadOfZero() throws Exception {
        try(var scope=ReasoningContentFilter.scopeCall("session",run)){
            var subscription=filter().filter(request(),r->Mono.never()).subscribe();
            assertTrue(recorder.started.await(3,TimeUnit.SECONDS));subscription.dispose();assertTrue(recorder.done.await(3,TimeUnit.SECONDS));
            assertNull(recorder.actual);assertEquals("CANCELLED",recorder.outcome);assertEquals(1,recorder.settles);
        }
    }
    @Test public void responseBodyCancellationAlsoSettlesOnWorker() throws Exception {
        try(var scope=ReasoningContentFilter.scopeCall("session",run)){
            CountDownLatch bodySubscribed=new CountDownLatch(1);
            var subscription=filter().filter(request(),r->Mono.just(ClientResponse.create(HttpStatus.OK).body(Flux.<org.springframework.core.io.buffer.DataBuffer>never().doOnSubscribe(s->bodySubscribed.countDown())).build()))
                    .flatMap(r->r.bodyToMono(String.class)).subscribe();
            assertTrue(bodySubscribed.await(3,TimeUnit.SECONDS));subscription.dispose();assertTrue(recorder.done.await(3,TimeUnit.SECONDS));
            assertNull(recorder.actual);assertEquals("CANCELLED",recorder.outcome);assertTrue(recorder.thread.startsWith("boundedElastic-"));
        }
    }
    static class Recorder implements ModelUsageAccounting {
        final CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);volatile Attempt attempt;volatile Long actual;volatile String outcome,thread;volatile int settles;
        public void openRun(String r,String u,long d,boolean system){} public void changeRunState(String r,String u,String s){}
        public Ticket begin(Attempt a){attempt=a;started.countDown();return (tokens,status)->{actual=tokens;outcome=status;thread=Thread.currentThread().getName();settles++;done.countDown();};}
    }
}
