package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * mimo-v2.5-pro thinking mode 要求在 tool-call 多轮对话中**每一条 tool_calls assistant 消息都各自带回它当时的** reasoning_content。
 * <p>
 * Spring AI 1.0.0 的 ChatCompletionMessage 是 Record 且 @JsonIgnoreProperties(ignoreUnknown=true)，
 * SSE 解析时 reasoning_content 被 Jackson 静默丢弃，导致 follow-up 请求缺少此字段 → 400 错误。
 * <p>
 * 本 filter 在 HTTP 层拦截：
 * <ul>
 *   <li><b>Response</b>：解析 SSE 原始 DataBuffer 流，提取本次响应累加得到的 reasoning_content，按调用顺序 append 到 session 缓存</li>
 *   <li><b>Request</b>：按 messages 中 tool_calls assistant 出现的顺序，**依次**给每条注入对应序号的 reasoning_content</li>
 * </ul>
 * <p>
 * <b>v1.3.2 (2026-05-14) 修两个并发安全 bug：</b>
 * <ol>
 *   <li><b>Bug 1</b>：原 {@code AtomicReference<String>} 全局单例，DAG 并行子步骤共用一个 cache → 互相覆盖。<br>
 *       修复：改 {@code ConcurrentMap<sessionId, List<String>>}，sessionId 从 ThreadLocal/MDC 解析，每个 session 独立列表。</li>
 *   <li><b>Bug 2</b>：原代码遍历 messages 把同一个 reasoning 塞给所有 tool_calls assistant → mimo 校验"reasoning 和当时上下文对不上"还是 400。<br>
 *       修复：按 messages 中 tool_calls assistant 的位置顺序，从 List 里取对应索引的 reasoning_content 分别注入。</li>
 * </ol>
 */
@Slf4j
public class ReasoningContentFilter implements ExchangeFilterFunction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final DataBufferFactory BUFFER_FACTORY = DefaultDataBufferFactory.sharedInstance;

    /** 每个 session 最多缓存的 reasoning 条数，防止内存泄漏（一次复杂对话通常不超过 10 轮工具调用）。*/
    private static final int MAX_REASONINGS_PER_SESSION = 32;

    /** 同一 sessionId 最多保留多少 session（LRU 风格淘汰），防止 sessionId 爆炸。*/
    private static final int MAX_SESSIONS = 1024;

    /**
     * 按 sessionId 隔离的有序 reasoning 缓存。
     * List 顺序 = messages 中 tool_calls assistant 出现的次序（也就是 LLM 多轮调用产生 reasoning 的顺序）。
     * 第 i 个 element 对应 messages 中第 i 个 tool_calls assistant 的 reasoning_content。
     * <p>
     * 每个 filter 实例（per-API）持有自己的 map，避免不同 API 互相干扰。
     */
    private final ConcurrentMap<String, List<String>> sessionReasonings = new ConcurrentHashMap<>();

    /**
     * 调用方在 spec.stream() 前后 set/clear 的 sessionId。
     * static 是因为 filter 是 per-API 实例化的（{@link cn.bugstack.ai.domain.agent.service.armory.node.AiClientApiNode}），
     * 调用方拿不到具体实例，用 static API 调用方一行即可：
     * <pre>
     * try (var s = ReasoningContentFilter.scopeSession(sessionId)) {
     *     spec.stream().chatClientResponse()...blockLast();
     * }
     * </pre>
     */
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    /** 调用方在 streaming LLM 调用前后包一层。{@code @return} AutoCloseable 用于 try-with-resources 清理。*/
    public static AutoCloseable scopeSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            CURRENT_SESSION_ID.set(sessionId);
        }
        return CURRENT_SESSION_ID::remove;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // filter 入口在调用者同步线程上跑（subscribe 时触发），此时 ThreadLocal/MDC 仍可用
        String sessionId = resolveSessionId();
        List<String> reasonings = sessionReasonings.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));

        // 简单 LRU：sessionMap 过大时清掉一些（仅一致性提醒，不严格 LRU，避免锁）
        if (sessionReasonings.size() > MAX_SESSIONS) {
            log.warn("[ReasoningFilter] session cache size {} exceeded {}, please check session lifecycle",
                    sessionReasonings.size(), MAX_SESSIONS);
        }

        // ============ Request 侧：按顺序 inject ============
        ClientRequest finalRequest;
        try {
            ClientRequest modified = injectReasoningContents(request, reasonings, sessionId);
            finalRequest = modified != null ? modified : request;
        } catch (Exception e) {
            log.warn("[ReasoningFilter] inject failed session={}: {}", sessionId, e.getMessage());
            finalRequest = request;
        }

        // ============ Response 侧：抓取 reasoning_content append 到 session 列表 ============
        return next.exchange(finalRequest).map(resp -> wrapResponse(resp, reasonings, sessionId));
    }

    /** 优先级：ThreadLocal > MDC.sessionId > MDC.requestId > "unknown-session" */
    private String resolveSessionId() {
        String tl = CURRENT_SESSION_ID.get();
        if (tl != null && !tl.isBlank()) return tl;
        String mdcSid = MDC.get("sessionId");
        if (mdcSid != null && !mdcSid.isBlank()) return mdcSid;
        String mdcReq = MDC.get("requestId");
        if (mdcReq != null && !mdcReq.isBlank()) return mdcReq;
        return "unknown-session";
    }

    // ====================================================================
    // Response 侧：捕获本次响应累加的 reasoning_content，按调用次序 append
    // ====================================================================

    private ClientResponse wrapResponse(ClientResponse response, List<String> reasonings, String sessionId) {
        Flux<DataBuffer> originalBody = response.body(BodyExtractors.toDataBuffers());
        StringBuilder sseBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();

        Flux<DataBuffer> wrappedBody = originalBody
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    try {
                        sseBuffer.append(new String(bytes, StandardCharsets.UTF_8));
                        int sepIndex;
                        while ((sepIndex = sseBuffer.indexOf("\n\n")) >= 0) {
                            String event = sseBuffer.substring(0, sepIndex);
                            sseBuffer.delete(0, sepIndex + 2);
                            processSseEvent(event, reasoningBuffer);
                        }
                    } catch (Exception e) {
                        log.debug("[ReasoningFilter] SSE parse error: {}", e.getMessage());
                    }
                    return BUFFER_FACTORY.wrap(bytes);
                })
                .doFinally(signal -> {
                    if (sseBuffer.length() > 0) {
                        processSseEvent(sseBuffer.toString(), reasoningBuffer);
                    }
                    if (reasoningBuffer.length() > 0) {
                        String captured = reasoningBuffer.toString();
                        // 加锁是因为 reasonings 是 SynchronizedList，size+add+trim 复合操作需原子
                        synchronized (reasonings) {
                            reasonings.add(captured);
                            // LRU 风格淘汰：超过 MAX 时 drop 最早的（保留最新 N 条）
                            while (reasonings.size() > MAX_REASONINGS_PER_SESSION) {
                                reasonings.remove(0);
                            }
                        }
                        log.info("[ReasoningFilter] reasoning_content captured ({} chars) session={} cacheSize={} signal={}",
                                captured.length(), sessionId, reasonings.size(), signal);
                    }
                });

        return ClientResponse.from(response).body(wrappedBody).build();
    }

    @SuppressWarnings("unchecked")
    private void processSseEvent(String event, StringBuilder reasoningBuffer) {
        for (String line : event.split("\n")) {
            if (!line.startsWith("data:")) continue;
            String json = line.substring(5).trim();
            if (json.isEmpty() || "[DONE]".equals(json)) continue;
            try {
                Map<String, Object> chunk = OBJECT_MAPPER.readValue(json, MAP_TYPE);
                Object choicesObj = chunk.get("choices");
                if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) continue;
                Object first = choices.get(0);
                if (!(first instanceof Map<?, ?> choice)) continue;
                Object delta = choice.get("delta");
                if (delta instanceof Map<?, ?> d) {
                    Object rc = d.get("reasoning_content");
                    if (rc instanceof String s && !s.isEmpty()) {
                        reasoningBuffer.append(s);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ====================================================================
    // Request 侧：按 messages 中 tool_calls assistant 顺序，按对齐索引注入
    // ====================================================================

    @SuppressWarnings("unchecked")
    private ClientRequest injectReasoningContents(ClientRequest original, List<String> reasonings, String sessionId) {
        byte[] bodyBytes = extractBodyBytes(original);
        if (bodyBytes == null || bodyBytes.length == 0) return null;

        try {
            Map<String, Object> requestMap = OBJECT_MAPPER.readValue(bodyBytes, MAP_TYPE);
            Object messagesObj = requestMap.get("messages");
            if (!(messagesObj instanceof List<?> messages)) return null;

            // 收集 messages 中所有 tool_calls assistant 的索引
            List<Integer> toolCallAssistantIdx = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                Object msgObj = messages.get(i);
                if (!(msgObj instanceof Map<?, ?> msg)) continue;
                if (!"assistant".equals(msg.get("role"))) continue;
                if (msg.get("tool_calls") == null) continue;
                toolCallAssistantIdx.add(i);
            }
            if (toolCallAssistantIdx.isEmpty()) return null;

            int K = toolCallAssistantIdx.size();
            int J;
            // 拷贝快照，避免遍历时 response 侧再 append 改了 list
            List<String> snapshot;
            synchronized (reasonings) {
                snapshot = new ArrayList<>(reasonings);
                J = snapshot.size();
            }

            // 对齐策略：取 snapshot 的"最后 K 个" 一一对应到 messages 中 K 个 tool_calls assistant 的顺序。
            // reasoningIdx = J - K + k：
            //   - J >= K（cache 够）：范围 [J-K, J-1]，全部命中
            //   - J <  K（cache 不够，例如服务重启）：前 (K-J) 个为负数 → 用 "[reasoning unavailable]" 占位
            //     至少让 mimo 不报"缺字段"，避免历史断点导致永久 400
            int injectedFromCache = 0;
            int placeholderCount = 0;
            for (int k = 0; k < K; k++) {
                int msgIdx = toolCallAssistantIdx.get(k);
                Map<String, Object> msg = (Map<String, Object>) messages.get(msgIdx);
                int reasoningIdx = J - K + k;
                String reasoning;
                if (reasoningIdx >= 0 && reasoningIdx < J) {
                    reasoning = snapshot.get(reasoningIdx);
                    injectedFromCache++;
                } else {
                    reasoning = "[reasoning unavailable]";
                    placeholderCount++;
                }
                msg.put("reasoning_content", reasoning);
            }

            byte[] newBody = OBJECT_MAPPER.writeValueAsBytes(requestMap);

            log.info("[ReasoningFilter] injected reasoning_content into {} tool_calls assistant(s) (fromCache={}, placeholder={}) session={} cacheSize={}",
                    K, injectedFromCache, placeholderCount, sessionId, J);

            return ClientRequest.from(original)
                    .body(BodyInserters.fromValue(BUFFER_FACTORY.wrap(newBody)))
                    .build();
        } catch (Exception e) {
            log.warn("[ReasoningFilter] inject parse failed session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

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

            List<HttpMessageWriter<?>> writers = new ArrayList<>();
            writers.add(new EncoderHttpMessageWriter<>(new Jackson2JsonEncoder()));
            original.body().insert(captureRequest, new BodyInserter.Context() {
                @Override public List<HttpMessageWriter<?>> messageWriters() { return writers; }
                @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                @Override public Map<String, Object> hints() { return Map.of(); }
            }).block();

            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[ReasoningFilter] failed to extract request body: {}", e.getMessage());
            return null;
        }
    }
}
