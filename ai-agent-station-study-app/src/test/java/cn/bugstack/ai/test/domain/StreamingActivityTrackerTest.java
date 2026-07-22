package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertTrue;

public class StreamingActivityTrackerTest {

    @Test
    public void decodedResponsesRenewOnlyTheirOwnAttemptWatchdog() {
        StreamingActivityTracker.Activity active = StreamingActivityTracker.start("flow-step-1");
        StreamingActivityTracker.timeoutOnInactivity(
                Flux.interval(Duration.ofMillis(10)).take(8)
                        .doOnNext(ignored -> active.markDecodedResponse()),
                active, Duration.ofMillis(50)).blockLast();

        StreamingActivityTracker.Activity stuck = StreamingActivityTracker.start("flow-step-2");
        RuntimeException error = null;
        try {
                StreamingActivityTracker.timeoutOnInactivity(
                                Flux.never(), stuck, Duration.ofMillis(40))
                        .blockLast();
        } catch (RuntimeException e) {
            error = e;
        }
        assertTrue("stuck stream must fail", error != null);
        assertTrue(hasCause(error, TimeoutException.class));
    }

    @Test
    public void reasoningActivityRenewsIdleClock() throws Exception {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("reasoning-call");
        Thread.sleep(25);
        activity.markReasoning();
        Thread.sleep(25);
        assertTrue("reasoning must renew the watchdog", !activity.isIdle(Duration.ofMillis(40)));
    }

    @Test
    public void rawReasoningSseRenewsTheBoundAttempt() throws Exception {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("raw-reasoning-call");
        Thread.sleep(45);
        ReasoningContentFilter filter = new ReasoningContentFilter();
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/chat")).build();
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"still thinking\"}}]}\n\n";

        try (AutoCloseable ignored = StreamingActivityTracker.scope(activity)) {
            ClientResponse response = filter.filter(request, req -> Mono.just(
                    ClientResponse.create(HttpStatus.OK).body(sse).build())).block();
            assertTrue(response != null);
            response.bodyToMono(String.class).block();
        }

        assertTrue("raw reasoning_content must renew the same attempt", !activity.isIdle(Duration.ofMillis(40)));
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
