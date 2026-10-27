package cn.bugstack.ai.domain.agent.service.model.failover;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Request-body helpers shared by the synchronous and streaming failover paths. */
final class ModelRequestBodies {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;
    private static final List<HttpMessageWriter<?>> WRITERS = ExchangeStrategies.withDefaults().messageWriters();

    private ModelRequestBodies() { }

    /** A chat completion body, or null for embeddings and anything else that must pass through. */
    static Map<String, Object> chat(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            Map<String, Object> json = JSON.readValue(body, MAP);
            return json.get("messages") instanceof List<?> && json.get("model") instanceof String model
                    && !model.isBlank() ? json : null;
        } catch (IOException notJson) {
            return null;
        }
    }

    /** A candidate that cannot be tried at all; our own wording, safe to show. */
    static ModelFailure unusable(ModelTarget target, String reason) {
        return new ModelFailure(target, ModelFailure.Kind.MODEL_UNAVAILABLE, 0, reason);
    }

    static Map<String, Object> withModel(Map<String, Object> body, String model) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put("model", model);
        return copy;
    }

    static byte[] bytes(Map<String, Object> body) {
        try {
            return JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("模型请求体无法序列化，未发送请求", e);
        }
    }

    /** The requested target first, then substitutes; unhealthy entries are skipped unless all are. */
    static List<ModelTarget> route(ModelTarget requested, ModelRequestTraits traits,
                                   ModelFallbackPlanner planner, ModelFailoverObserver observer) {
        List<ModelTarget> all = new ArrayList<>();
        all.add(requested);
        for (ModelTarget target : planner.fallbacks(requested, traits))
            if (target != null && !all.contains(target)) all.add(target);
        List<ModelTarget> healthy = all.stream().filter(observer::available).toList();
        return healthy.isEmpty() ? all : healthy;
    }

    static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException httpDate) {
            return null;
        }
    }

    /**
     * Serialize a WebClient body on the calling thread, with the full default writer set (an inserter
     * holding a DataBuffer would otherwise be serialised as a POJO). Object-valued inserters stay
     * reusable. Never blocks: null when the body is not available synchronously, so the caller can
     * pass the request through unchanged even on an event-loop thread.
     */
    static byte[] readNow(ClientRequest original) {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        ClientHttpRequest capture = new ClientHttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();
            { headers.set(HttpHeaders.CONTENT_TYPE, "application/json"); }
            @Override public HttpMethod getMethod() { return original.method(); }
            @Override public URI getURI() { return original.url(); }
            @Override public HttpHeaders getHeaders() { return headers; }
            @Override public DataBufferFactory bufferFactory() { return BUFFERS; }
            @Override public MultiValueMap<String, HttpCookie> getCookies() { return new LinkedMultiValueMap<>(); }
            @Override public Map<String, Object> getAttributes() { return new HashMap<>(); }
            @Override public <T> T getNativeRequest() { return null; }
            @Override public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) { }
            @Override public boolean isCommitted() { return false; }
            @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(Flux.from(body)).doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    captured.set(bytes);
                }).then();
            }
            @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return Flux.from(body).concatMap(this::writeWith).then();
            }
            @Override public Mono<Void> setComplete() { return Mono.empty(); }
        };
        try {
            var written = original.body().insert(capture, new BodyInserter.Context() {
                @Override public List<HttpMessageWriter<?>> messageWriters() { return WRITERS; }
                @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                @Override public Map<String, Object> hints() { return Map.of(); }
            }).toFuture();
            return written.isDone() && !written.isCompletedExceptionally() ? captured.get() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
