package cn.bugstack.ai.domain.agent.service.execute.common;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抓取每次 LLM HTTP 调用的真实请求/响应体（含 advisor 注入后的最终 messages、tools 列表，
 * 以及 Spring AI 内部 tool-call 循环里每一轮 tool_result 重新发起的请求）。
 * <p>
 * 一次 HTTP round-trip = 两行 INFO 日志（{@code llm.wire dir=request} / {@code dir=response}），
 * 通过 logback Logstash appender 推送到 ES，MDC 自动带上 traceId / qid / sessionId / step / agentId。
 * <p>
 * 必须挂在 {@link ReasoningContentFilter} <b>之后</b>，这样：
 * <ul>
 *   <li>request 侧：先 reasoning 注入 → 再 wire log，看到的就是真正发出去的 body</li>
 *   <li>response 侧：先 wire log 累加 → 再 reasoning capture，两者都拿到原始 SSE 流，互不影响</li>
 * </ul>
 * tee 模式参考 {@link ReasoningContentFilter#wrapResponse}：复制每个 DataBuffer 后用新 buffer 重发，
 * 下游 Spring AI 解析器看不出区别。
 */
@Slf4j
public class WireTraceRecorder implements ExchangeFilterFunction {

    /** Dedicated logger so logback / Kibana can filter by logger name. */
    private static final Logger WIRE = LoggerFactory.getLogger("llm.wire");
    private static final DataBufferFactory BUFFER_FACTORY = DefaultDataBufferFactory.sharedInstance;

    /** Single-line body cap; tuned so a single Logstash JSON doc stays under ES default mapping limits. */
    private static final int MAX_BODY_CHARS = 32_000;

    private static final java.util.regex.Pattern IMAGE_DATA_URL =
            java.util.regex.Pattern.compile(
                    "data:image/[a-zA-Z0-9.+-]+;base64,[a-zA-Z0-9+/=\\\\r\\\\n]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Process-wide hop counter; lets Kibana sort multiple HTTP round-trips of one Spring AI .call(). */
    private static final AtomicLong HOP_SEQ = new AtomicLong();

    /** MDC keys to copy from the call-site thread into the netty/doFinally thread when logging. */
    private static final String[] PROPAGATED_MDC_KEYS = {
            "traceId", "qid", "spanId", "requestId",
            "sessionId", "userId", "tenantId", "agentId",
            "step", "model", "clientId", "billingScope"
    };

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        long hopId = HOP_SEQ.incrementAndGet();
        long start = System.currentTimeMillis();
        Map<String, String> mdcSnapshot = snapshotPropagatedMdc();

        // 2026-05-20 关键修复：extractBodyBytes 会通过 original.body().insert(...) 消费原 BodyInserter，
        // 如果直接把 request 透传给 next.exchange()，下游再次 insert 拿到的可能是空 body（Spring AI 序列化的
        // OpenAi chat request 内含 model / messages / tools 等字段全没了 → LLM gateway 返回 400 "unknown-model"）。
        // 正确做法：抓到 bytes 后用 BodyInserters.fromValue(DataBuffer) 重建一个等价的 ClientRequest 传给下游，
        // 这样原 BodyInserter 被消费完就丢弃，下游用的是我们重建的新 body（内容字节完全一样）。
        // 参考 ReasoningContentFilter.injectReasoningContents 同样的模式。
        byte[] bodyBytes = extractBodyBytes(request);
        String reqBody = bodyBytes == null || bodyBytes.length == 0 ? "" : new String(bodyBytes, StandardCharsets.UTF_8);
        logWire("request", hopId, request.method().name(), request.url().toString(),
                reqBody, -1, mdcSnapshot);

        ClientRequest forwardRequest = (bodyBytes == null || bodyBytes.length == 0)
                ? request
                : ClientRequest.from(request)
                        .body(org.springframework.web.reactive.function.BodyInserters
                                .fromValue(BUFFER_FACTORY.wrap(bodyBytes)))
                        .build();

        return next.exchange(forwardRequest)
                .map(resp -> wrapResponse(resp, hopId, request.url().toString(), start, mdcSnapshot));
    }

    private ClientResponse wrapResponse(ClientResponse response, long hopId, String url,
                                        long start, Map<String, String> mdcSnapshot) {
        Flux<DataBuffer> originalBody = response.body(BodyExtractors.toDataBuffers());
        StringBuilder respBuffer = new StringBuilder();

        Flux<DataBuffer> wrappedBody = originalBody
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    // 仅累加到上限，超出后丢弃但仍透传给下游（不影响 Spring AI 解析）
                    if (respBuffer.length() < MAX_BODY_CHARS) {
                        try {
                            respBuffer.append(new String(bytes, StandardCharsets.UTF_8));
                        } catch (Exception ignored) {
                        }
                    }
                    return BUFFER_FACTORY.wrap(bytes);
                })
                .doFinally(signal -> {
                    long latency = System.currentTimeMillis() - start;
                    logWire("response", hopId, "<-", url, respBuffer.toString(), latency, mdcSnapshot);
                });

        return ClientResponse.from(response).body(wrappedBody).build();
    }

    private void logWire(String direction, long hopId, String method, String url,
                         String body, long latencyMs, Map<String, String> mdcSnapshot) {
        // doFinally 可能在 netty/reactor 线程跑，MDC 是空的；先快照当前线程 MDC，临时套用调用方 snapshot，写完恢复
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
            else MDC.clear();
            MDC.put("llmHop", String.valueOf(hopId));
            String safe = normalizeForSingleLine(body);
            if (latencyMs >= 0) {
                WIRE.info("llm.wire dir={} hop={} method={} url={} latencyMs={} body={}",
                        direction, hopId, method, url, latencyMs, safe);
            } else {
                WIRE.info("llm.wire dir={} hop={} method={} url={} body={}",
                        direction, hopId, method, url, safe);
            }
        } finally {
            if (previous != null) MDC.setContextMap(previous);
            else MDC.clear();
        }
    }

    /**
     * 抽取 ClientRequest 的 raw body bytes（参考 {@link ReasoningContentFilter#extractBodyBytes}）。
     * 该实现做了一次内存写入捕获，不消耗原始 BodyInserter；下游正常运行。
     */
    private byte[] extractBodyBytes(ClientRequest original) {
        try {
            CompletableFuture<byte[]> future = new CompletableFuture<>();

            ClientHttpRequest captureRequest = new ClientHttpRequest() {
                private final HttpHeaders headers = new HttpHeaders();

                {
                    headers.set("Content-Type", "application/json");
                }

                @Override public HttpMethod getMethod() { return original.method(); }
                @Override public URI getURI() { return original.url(); }
                @Override public HttpHeaders getHeaders() { return headers; }
                @Override public DataBufferFactory bufferFactory() { return BUFFER_FACTORY; }
                @Override public MultiValueMap<String, HttpCookie> getCookies() { return new LinkedMultiValueMap<>(); }
                public Map<String, Object> getAttributes() { return new HashMap<>(); }
                @SuppressWarnings("unchecked") public <T> T getNativeRequest() { return null; }
                public Flux<DataBuffer> getBody() { return Flux.empty(); }
                @Override public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) {}
                @Override public boolean isCommitted() { return false; }

                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return DataBufferUtils.join(body)
                            .doOnNext(buf -> {
                                byte[] bytes = new byte[buf.readableByteCount()];
                                buf.read(bytes);
                                DataBufferUtils.release(buf);
                                future.complete(bytes);
                            })
                            .then();
                }

                @Override
                public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return Mono.from(body).flatMap(this::writeWith);
                }

                @Override
                public Mono<Void> setComplete() {
                    future.complete(new byte[0]);
                    return Mono.empty();
                }
            };

            // 2026-05-20 修：必须用 Spring 默认的完整 writer 集合，否则 BodyInserters.fromValue(DataBuffer)
            // 这种已经预编码的 body（ReasoningContentFilter inject 后走这条）会被当成 POJO 用 Jackson 序列化，
            // 产生 {"nativeBuffer":"base64..."} 无法直读。withDefaults() 包含 ByteBufferEncoder / DataBufferEncoder
            // / ByteArrayEncoder / Jackson2JsonEncoder，能识别所有 body 类型，落 ES 的就是真实 JSON 文本。
            List<HttpMessageWriter<?>> writers = ExchangeStrategies.withDefaults().messageWriters();
            original.body().insert(captureRequest, new BodyInserter.Context() {
                @Override public List<HttpMessageWriter<?>> messageWriters() { return writers; }
                @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                @Override public Map<String, Object> hints() { return Map.of(); }
            }).block();

            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> snapshotPropagatedMdc() {
        Map<String, String> out = new HashMap<>();
        for (String key : PROPAGATED_MDC_KEYS) {
            String v = MDC.get(key);
            if (v != null) out.put(key, v);
        }
        return out;
    }

    private String normalizeForSingleLine(String body) {
        if (body == null || body.isEmpty()) return "";
        // Redact before truncation so neither the head nor tail can leak image bytes.
        String s = IMAGE_DATA_URL.matcher(body).replaceAll("[IMAGE_BASE64_REDACTED]");
        if (s.length() > MAX_BODY_CHARS) {
            String marker = "...(truncated middle, full=" + body.length() + ")...";
            int headChars = MAX_BODY_CHARS / 2;
            int tailChars = Math.max(0, MAX_BODY_CHARS - headChars - marker.length());
            s = s.substring(0, headChars) + marker + s.substring(s.length() - tailChars);
        }
        // 把换行字面化，避免 Logstash 按行切日志
        return s.replace("\r", "").replace("\n", "\\n");
    }
}
