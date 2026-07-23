package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.multimodal.OpenAiMultimodalRequestNormalizer;
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
     * 所有 filter 实例的弱注册表：filter 是 per-API 实例，{@link #clearRun(String)} 要跨所有实例清掉某 runId 的缓存，
     * 故需静态登记。runId 隔离后 {@code sessionReasonings} 以 runId 为 key 只增不减，必须在执行结束按 runId 清理防泄漏。
     */
    private static final java.util.Set<ReasoningContentFilter> INSTANCES =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 执行结束时清掉该 runId 的注入缓存（跨所有 per-API filter 实例）。在 execute() finally 调用，与 cleanupRun(runId) 同处。 */
    public static void clearRun(String runId) {
        if (runId == null || runId.isBlank()) return;
        for (ReasoningContentFilter f : INSTANCES) {
            f.sessionReasonings.remove(runId);
        }
    }

    /**
     * 立即回答专用旁路缓存：按 sessionId 记录"最近一次（含被 cancel 截断的半截）reasoning_content"，供 finalize 读取。
     * <p>
     * 与上面 per-instance 的 {@link #sessionReasonings}（roundtrip 注入用）<b>相互独立</b>：static 跨所有 filter 实例共享，
     * 让 finalize 代码不必持有具体 filter 实例就能读到半截思考。只写不影响原注入逻辑。
     */
    private static final ConcurrentMap<String, String> LATEST_REASONING = new ConcurrentHashMap<>();
    private static final int MAX_LATEST_SESSIONS = 1024;

    /**
     * 关思考（disable thinking）要 deep-merge 进出站请求体的 JSON 片段；null = 不注入（零影响）。
     * 仅当调用方用 {@link #scopeNoThinking()} 标记本次调用、且本字段非空时才注入。
     * 由 {@link cn.bugstack.ai.domain.agent.service.armory.node.AiClientApiNode} 从 yml
     * {@code agent.no-think.body-params} 读出 JSON 传入（MiMo 官方 API 的关思考参数因 serving 而异，故做成可配置）。
     */
    private final Map<String, Object> disableThinkingFragment;

    /** 默认构造：不注入关思考参数（保持历史行为，零影响）。 */
    public ReasoningContentFilter() {
        this(null);
    }

    /** @param disableThinkingBodyJson 关思考要注入出站 body 的 JSON 片段；null/空白 = 禁用注入。 */
    public ReasoningContentFilter(String disableThinkingBodyJson) {
        Map<String, Object> frag = null;
        if (disableThinkingBodyJson != null && !disableThinkingBodyJson.isBlank()) {
            try {
                frag = OBJECT_MAPPER.readValue(disableThinkingBodyJson, MAP_TYPE);
            } catch (Exception e) {
                log.warn("[ReasoningFilter] agent.no-think.body-params 解析失败，关思考注入禁用: {}", e.getMessage());
            }
        }
        this.disableThinkingFragment = frag;
        if (frag != null) {
            log.info("[ReasoningFilter] 关思考注入已启用，片段={}", frag);
        }
        INSTANCES.add(this);
    }

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

    /**
     * 2026-06-23 修跨题串扰：注入缓存 {@link #sessionReasonings} 改按"每次执行唯一的 runId"隔离，而非 sessionId。
     * <p>根因：E2E/生产同一 sessionId 下连续/并发跑多道题时，reasoning_content 缓存按 sessionId 共享且跨题累加，
     * 请求侧"取末尾 K 条"会把<b>别题</b>的 reasoning 注入本题的 tool_calls assistant，模型顺着被污染的"自己的思考"
     * 答成别题（如 JVM 题答出编程语言排名）。runId 在一次执行内稳定、跨执行唯一，天然隔离并发与累加。
     * <p>为空时回退 sessionId（保持旧行为，零影响）。{@link #LATEST_REASONING}/no-think 仍按 sessionId。</p>
     */
    private static final ThreadLocal<String> CURRENT_RUN_ID = new ThreadLocal<>();

    /** 调用方在 streaming LLM 调用前后包一层。{@code @return} AutoCloseable 用于 try-with-resources 清理。*/
    public static AutoCloseable scopeSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            CURRENT_SESSION_ID.set(sessionId);
        }
        return CURRENT_SESSION_ID::remove;
    }

    /**
     * 同时绑定 sessionId（供 LATEST_REASONING / 日志）与 runId（供注入缓存隔离）。
     * runId 空白时注入缓存回退到 sessionId（旧行为）。
     */
    public static AutoCloseable scopeSession(String sessionId, String runId) {
        if (sessionId != null && !sessionId.isBlank()) CURRENT_SESSION_ID.set(sessionId);
        if (runId != null && !runId.isBlank()) CURRENT_RUN_ID.set(runId);
        return () -> { CURRENT_SESSION_ID.remove(); CURRENT_RUN_ID.remove(); };
    }

    /** 注入缓存隔离键：优先 runId（每次执行唯一），回退 sessionId。 */
    private String resolveReasoningKey(String sessionId) {
        String rid = CURRENT_RUN_ID.get();
        if (rid != null && !rid.isBlank()) return rid;
        String mdcRun = MDC.get("runId");
        if (mdcRun != null && !mdcRun.isBlank()) return mdcRun;
        return sessionId;
    }

    /**
     * 关思考标记：按 <b>sessionId</b> 记到一个 static 集合，<b>不靠</b> ThreadLocal/MDC 跨线程传播——本 filter 的 {@link #filter}
     * 实际跑在 WebClient 的 reactor 线程（boundedElastic / HttpClient-Worker），per-thread 通道不可靠；但 filter 一定能拿到
     * sessionId（{@link #resolveSessionId()} 已验证可靠），故用 sessionId 作 key 最稳，tool-call 多跳整发 finalize 都命中。
     * 调用方在 finalize 那一发外包：
     * <pre>try (var __nt = ReasoningContentFilter.scopeNoThinking(sessionId)) { spec.stream()...blockLast(); }</pre>
     * 集合不含该 session → 永不注入 → 零影响。
     */
    private static final java.util.Set<String> NO_THINK_SESSIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记某 session 本次关思考，返回 AutoCloseable 供 try-with-resources 清理。sessionId 空则 no-op。 */
    public static AutoCloseable scopeNoThinking(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return () -> {};
        NO_THINK_SESSIONS.add(sessionId);
        return () -> NO_THINK_SESSIONS.remove(sessionId);
    }

    private static boolean isNoThinkSession(String sessionId) {
        return sessionId != null && NO_THINK_SESSIONS.contains(sessionId);
    }

    /** 立即回答：读取某 session 最近一次（含 mid-stream 截断的半截）reasoning_content，无则 null。 */
    public static String getLatestReasoning(String sessionId) {
        if (sessionId == null) return null;
        return LATEST_REASONING.get(sessionId);
    }

    /** 轮末清理旁路缓存，避免 session 堆积（与 LongTermMemoryTurnSnapshot.clearSession 同处调用）。 */
    public static void clearLatestReasoning(String sessionId) {
        if (sessionId != null) LATEST_REASONING.remove(sessionId);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // filter 入口在调用者同步线程上跑（subscribe 时触发），此时 ThreadLocal/MDC 仍可用
        String sessionId = resolveSessionId();
        // 注入缓存按 runId 隔离（跨题/并发不串扰）；sessionId 仍用于 LATEST_REASONING / 日志。
        String reasoningKey = resolveReasoningKey(sessionId);
        // Captured on the subscribing thread. The Activity object itself is thread-safe and is
        // passed into the async response-body pipeline explicitly (no Reactor ThreadLocal reliance).
        StreamingActivityTracker.Activity streamingActivity = StreamingActivityTracker.current();
        List<String> reasonings = sessionReasonings.computeIfAbsent(reasoningKey,
                k -> Collections.synchronizedList(new ArrayList<>()));

        // 简单 LRU：sessionMap 过大时清掉一些（仅一致性提醒，不严格 LRU，避免锁）
        if (sessionReasonings.size() > MAX_SESSIONS) {
            log.warn("[ReasoningFilter] session cache size {} exceeded {}, please check session lifecycle",
                    sessionReasonings.size(), MAX_SESSIONS);
        }

        // ============ Request 侧：reasoning_content 注入 + 关思考参数注入（一次解析/重建）============
        ClientRequest finalRequest;
        try {
            ClientRequest modified = rewriteRequestBody(request, reasonings, sessionId, isNoThinkSession(sessionId));
            finalRequest = modified != null ? modified : request;
        } catch (Exception e) {
            log.warn("[ReasoningFilter] request rewrite failed session={}: {}", sessionId, e.getMessage());
            finalRequest = request;
        }

        // ============ Response 侧：抓取 reasoning_content append 到 session 列表 ============
        return next.exchange(finalRequest).map(resp -> wrapResponse(resp, reasonings, sessionId, streamingActivity));
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

    private ClientResponse wrapResponse(ClientResponse response, List<String> reasonings, String sessionId,
                                        StreamingActivityTracker.Activity streamingActivity) {
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
                            processSseEvent(event, reasoningBuffer, streamingActivity);
                        }
                    } catch (Exception e) {
                        log.debug("[ReasoningFilter] SSE parse error: {}", e.getMessage());
                    }
                    return BUFFER_FACTORY.wrap(bytes);
                })
                .doFinally(signal -> {
                    if (sseBuffer.length() > 0) {
                        processSseEvent(sseBuffer.toString(), reasoningBuffer, streamingActivity);
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
                        // 立即回答旁路：记录半截/完整思考供 finalize 读取。signal=cancel（mid-stream 截断）时也会走到这里。
                        if (sessionId != null && !"unknown-session".equals(sessionId)) {
                            if (LATEST_REASONING.size() > MAX_LATEST_SESSIONS) LATEST_REASONING.clear();
                            LATEST_REASONING.put(sessionId, captured);
                        }
                        log.info("[ReasoningFilter] reasoning_content captured ({} chars) session={} cacheSize={} signal={}",
                                captured.length(), sessionId, reasonings.size(), signal);
                    }
                });

        return ClientResponse.from(response).body(wrappedBody).build();
    }

    @SuppressWarnings("unchecked")
    private void processSseEvent(String event, StringBuilder reasoningBuffer,
                                 StreamingActivityTracker.Activity streamingActivity) {
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
                        if (streamingActivity != null) streamingActivity.markReasoning();
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ====================================================================
    // Request 侧：按 messages 中 tool_calls assistant 顺序，按对齐索引注入
    // ====================================================================

    /**
     * 统一改写出站请求体：① reasoning_content 注入（mimo tool-call roundtrip）；② 关思考参数注入（finalize）。
     * 一次解析、一次重建；两者都不命中 → 返回 null（调用方用原 request，零影响）。
     */
    private ClientRequest rewriteRequestBody(ClientRequest original, List<String> reasonings, String sessionId, boolean noThink) {
        byte[] bodyBytes = extractBodyBytes(original);
        if (bodyBytes == null || bodyBytes.length == 0) return null;
        try {
            Map<String, Object> requestMap = OBJECT_MAPPER.readValue(bodyBytes, MAP_TYPE);
            // Spring AI serializes UserMessage as text -> media, while MiMo's
            // documented and verified path uses image_url -> text. Normalize
            // only this wire copy; ChatMemory and Message objects are unchanged.
            boolean changed = OpenAiMultimodalRequestNormalizer.imagesBeforeText(requestMap);
            // 关思考时跳过 reasoning_content 注入：思考关了 mimo 不产 reasoning，再回填反而可能 400。
            changed |= !noThink && injectReasoningInto(requestMap, reasonings, sessionId);
            if (noThink && disableThinkingFragment != null) {
                deepMerge(requestMap, disableThinkingFragment);
                changed = true;
                log.info("[ReasoningFilter] 关思考参数已注入出站 body session={}", sessionId);
            }
            if (!changed) return null;
            byte[] newBody = OBJECT_MAPPER.writeValueAsBytes(requestMap);
            return ClientRequest.from(original)
                    .body(BodyInserters.fromValue(BUFFER_FACTORY.wrap(newBody)))
                    .build();
        } catch (Exception e) {
            log.warn("[ReasoningFilter] body rewrite parse failed session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 把 reasoning_content 按顺序注入 requestMap 里的 tool_calls assistant 消息（原 injectReasoningContents 逻辑，改为就地操作 map）。
     * @return 是否改动了 requestMap
     */
    @SuppressWarnings("unchecked")
    private boolean injectReasoningInto(Map<String, Object> requestMap, List<String> reasonings, String sessionId) {
        Object messagesObj = requestMap.get("messages");
        if (!(messagesObj instanceof List<?> messages)) return false;

        // 收集 messages 中所有 tool_calls assistant 的索引
        List<Integer> toolCallAssistantIdx = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Object msgObj = messages.get(i);
            if (!(msgObj instanceof Map<?, ?> msg)) continue;
            if (!"assistant".equals(msg.get("role"))) continue;
            if (msg.get("tool_calls") == null) continue;
            toolCallAssistantIdx.add(i);
        }
        if (toolCallAssistantIdx.isEmpty()) return false;

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

        log.info("[ReasoningFilter] injected reasoning_content into {} tool_calls assistant(s) (fromCache={}, placeholder={}) session={} cacheSize={}",
                K, injectedFromCache, placeholderCount, sessionId, J);
        return true;
    }

    /** 递归合并 overlay 进 base：两边都是 Map 时深合并，否则 overlay 覆盖。 */
    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            Object bv = base.get(e.getKey());
            Object ov = e.getValue();
            if (bv instanceof Map && ov instanceof Map) {
                deepMerge((Map<String, Object>) bv, (Map<String, Object>) ov);
            } else {
                base.put(e.getKey(), ov);
            }
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
