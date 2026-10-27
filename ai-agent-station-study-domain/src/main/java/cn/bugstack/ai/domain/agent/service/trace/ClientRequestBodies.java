package cn.bugstack.ai.domain.agent.service.trace;

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
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the bytes a {@link ClientRequest} would send. Writing consumes the request's body inserter,
 * so a caller forwards {@link #withBody} instead of the original request.
 */
public final class ClientRequestBodies {
    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;
    // The full default writer set: a body already encoded as a DataBuffer must not be serialized as a POJO.
    private static final List<HttpMessageWriter<?>> WRITERS = ExchangeStrategies.withDefaults().messageWriters();

    private ClientRequestBodies() { }

    /** The encoded body, or null when it cannot be captured. */
    public static byte[] bytes(ClientRequest original) {
        try {
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            ClientHttpRequest capture = new ClientHttpRequest() {
                private final HttpHeaders headers = new HttpHeaders();
                { headers.set("Content-Type", "application/json"); }
                @Override public HttpMethod getMethod() { return original.method(); }
                @Override public URI getURI() { return original.url(); }
                @Override public HttpHeaders getHeaders() { return headers; }
                @Override public DataBufferFactory bufferFactory() { return BUFFERS; }
                @Override public MultiValueMap<String, HttpCookie> getCookies() { return new LinkedMultiValueMap<>(); }
                public Map<String, Object> getAttributes() { return new HashMap<>(); }
                @SuppressWarnings("unchecked") public <T> T getNativeRequest() { return null; }
                @Override public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) { }
                @Override public boolean isCommitted() { return false; }
                @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return DataBufferUtils.join(body).doOnNext(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        future.complete(bytes);
                    }).then();
                }
                @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return Mono.from(body).flatMap(this::writeWith);
                }
                @Override public Mono<Void> setComplete() {
                    future.complete(new byte[0]);
                    return Mono.empty();
                }
                public Flux<DataBuffer> getBody() { return Flux.empty(); }
            };
            original.body().insert(capture, new BodyInserter.Context() {
                @Override public List<HttpMessageWriter<?>> messageWriters() { return WRITERS; }
                @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                @Override public Map<String, Object> hints() { return Map.of(); }
            }).block();
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** The same request with the captured bytes as its body. */
    public static ClientRequest withBody(ClientRequest original, byte[] body) {
        return ClientRequest.from(original).body(BodyInserters.fromValue(BUFFERS.wrap(body))).build();
    }
}
