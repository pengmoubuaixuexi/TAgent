package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ModelStreamException;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingRetryPolicy;
import org.junit.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ModelStreamErrorTest {
    @Test public void processingHeartbeatThenRateLimitInHttp200KeepsRealReason() {
        String body = ": PROCESSING\n\ndata: {\"error\":{\"code\":\"429\",\"type\":\"rate_limit_error\",\"message\":\"secret echoed\"}}\n\ndata: [DONE]\n\n";
        var error = assertThrows(ModelStreamException.class, () -> read(List.of(body.getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().contains("429"));
        assertFalse(error.getMessage().contains("secret"));
        assertTrue(StreamingRetryPolicy.canRetry(error, false, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(error, true, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(error, false, 10, 1, 3));
        assertEquals(error.getMessage(), StreamingRetryPolicy.failed("flow", new IllegalStateException("Stream processing failed", error)).getMessage());
    }

    @Test public void fragmentedUtf8ErrorAndUndelimitedLastFrameAreStillFailures() {
        String body = "data: {\"error\":{\"code\":401,\"message\":\"敏感正文\"}}\r\n\r\n";
        List<byte[]> bytes = new ArrayList<>();
        for (byte value : body.getBytes(StandardCharsets.UTF_8)) bytes.add(new byte[]{value});
        var error = assertThrows(ModelStreamException.class, () -> read(bytes));
        assertTrue(error.getMessage().contains("认证"));
        assertFalse(StreamingRetryPolicy.canRetry(error, false, 0, 1, 3));
        assertThrows(ModelStreamException.class, () -> read(List.of("data: {\"error\":\"failed\"}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void ordinaryContentContainingErrorIsPassedThroughUnchanged() {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"error is a word\"}}]}\n\ndata: [DONE]\n\n";
        assertEquals(body, read(List.of(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void emptyAnswerGetsOnlyOneRetryBeforeOutputOrTools() {
        var error = ModelStreamException.emptyAnswer();
        assertTrue(StreamingRetryPolicy.canRetry(error, false, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(error, false, 0, 2, 3));
        assertFalse(StreamingRetryPolicy.canRetry(new java.io.IOException("wrapped", error), false, 0, 2, 3));
        assertFalse(StreamingRetryPolicy.canRetry(error, false, 0, 1, 1));
        assertFalse(StreamingRetryPolicy.canRetry(error, true, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(error, false, 1, 1, 3));
    }

    private String read(List<byte[]> chunks) {
        var request = ClientRequest.create(HttpMethod.GET, URI.create("https://provider.test/chat")).build();
        var response = ClientResponse.create(HttpStatus.OK).body(Flux.fromIterable(chunks)
                .map(DefaultDataBufferFactory.sharedInstance::wrap)).build();
        return new ReasoningContentFilter().filter(request, ignored -> Mono.just(response))
                .flatMap(value -> value.bodyToMono(String.class)).block(Duration.ofSeconds(5));
    }
}
