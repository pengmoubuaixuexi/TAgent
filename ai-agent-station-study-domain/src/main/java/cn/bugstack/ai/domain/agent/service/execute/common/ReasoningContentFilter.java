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
 *   <li><b>Response</b>：完整 UTF-8 SSE 帧中的 reasoning_content 按独立模型调用域和 tool-call id 保存。</li>
 *   <li><b>Request</b>：根据 tool-call id 精确匹配，历史删减或并行节点完成顺序不参与匹配。</li>
 * </ul>
 * <p>
 * 出站请求在最终 HTTP 边界执行上下文和运行预算检查。调用结束清理注入缓存；
 * 立即回答只能读取调用线程刚完成的同一 run 内容，不能借用并行节点的内容。
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
     * 按模型调用域保留的诊断列表，不作为注入对应关系的依据。
     * <p>
     * 每个 filter 实例（per-API）持有自己的 map，避免不同 API 互相干扰。
     */
    private final ConcurrentMap<String, List<String>> sessionReasonings = new ConcurrentHashMap<>();
    /** Exact tool-call identity, scoped to one top-level model invocation (including its tool loop). */
    private final ConcurrentMap<String, Map<String, String>> toolReasonings = new ConcurrentHashMap<>();

    /**
     * 所有 filter 实例的弱注册表：filter 是 per-API 实例，{@link #clearRun(String)} 要跨所有实例清掉某 runId 的缓存，
     * 故需静态登记。runId 隔离后 {@code sessionReasonings} 以 runId 为 key 只增不减，必须在执行结束按 runId 清理防泄漏。
     */
    private static final java.util.Set<ReasoningContentFilter> INSTANCES =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static List<ReasoningContentFilter> instances() {
        synchronized(INSTANCES) { return new ArrayList<>(INSTANCES); }
    }

    /** 执行结束时清掉该 runId 的注入缓存（跨所有 per-API filter 实例）。在 execute() finally 调用，与 cleanupRun(runId) 同处。 */
    public static void clearRun(String runId) {
        if (runId == null || runId.isBlank()) return;
        for (ReasoningContentFilter f : instances()) {
            f.sessionReasonings.remove(runId);
            f.sessionReasonings.keySet().removeIf(key -> key.startsWith(runId + "::call::"));
            f.toolReasonings.keySet().removeIf(key -> key.equals(runId) || key.startsWith(runId + "::call::"));
        }
        LATEST_REASONING.keySet().removeIf(key -> key.equals(runId) || key.startsWith(runId+"::call::"));
        if(LAST_COMPLETED.get()!=null && runId.equals(LAST_COMPLETED.get().run())) LAST_COMPLETED.remove();
    }

    /**
     * 立即回答专用旁路缓存：按 sessionId 记录"最近一次（含被 cancel 截断的半截）reasoning_content"，供 finalize 读取。
     * <p>
     * 与上面 per-instance 的 {@link #sessionReasonings}（roundtrip 注入用）<b>相互独立</b>：static 跨所有 filter 实例共享，
     * 让 finalize 代码不必持有具体 filter 实例就能读到半截思考。只写不影响原注入逻辑。
     */
    private static final ConcurrentMap<String, String> LATEST_REASONING = new ConcurrentHashMap<>();
    private record CompletedReasoning(String session,String run,String text) {}
    private static final ThreadLocal<CompletedReasoning> LAST_COMPLETED = new ThreadLocal<>();
    private static final int MAX_LATEST_SESSIONS = 1024;

    /**
     * 关思考（disable thinking）要 deep-merge 进出站请求体的 JSON 片段；null = 不注入（零影响）。
     * 仅当调用方用 {@link #scopeNoThinking()} 标记本次调用、且本字段非空时才注入。
     * 由 {@link cn.bugstack.ai.domain.agent.service.armory.node.AiClientApiNode} 从 yml
     * {@code agent.no-think.body-params} 读出 JSON 传入（MiMo 官方 API 的关思考参数因 serving 而异，故做成可配置）。
     */
    private final Map<String, Object> disableThinkingFragment;
    private cn.bugstack.ai.domain.agent.service.model.ModelUsageContext usageContext;
    public ReasoningContentFilter withUsageContext(cn.bugstack.ai.domain.agent.service.model.ModelUsageContext context) {
        this.usageContext=context; return this;
    }

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
    private static final ThreadLocal<String> CURRENT_CALL_ID = new ThreadLocal<>();
    public static final String SESSION_CONTEXT_KEY = "agent.reasoning_session";
    public static final String RUN_CONTEXT_KEY = "agent.reasoning_run";
    public static final String CALL_CONTEXT_KEY = "agent.reasoning_call";

    /** Capture the lexical scope before an advisor (or a model retry) moves subscription to another thread. */
    public static Map<String, String> captureContext() {
        String sessionId = resolveSessionId();
        String runKey = resolveReasoningKey(sessionId);
        String callKey = CURRENT_CALL_ID.get();
        return Map.of(SESSION_CONTEXT_KEY, sessionId, RUN_CONTEXT_KEY, runKey,
                CALL_CONTEXT_KEY, callKey == null ? runKey : callKey);
    }

    /** Isolate parallel nodes, repeated steps and steer/finalize calls within the same run. */
    public static AutoCloseable scopeCall(String sessionId, String runId) {
        String previousSession = CURRENT_SESSION_ID.get();
        String previousRun = CURRENT_RUN_ID.get();
        String previousCall = CURRENT_CALL_ID.get();
        if (sessionId != null && !sessionId.isBlank()) CURRENT_SESSION_ID.set(sessionId);
        if (runId != null && !runId.isBlank()) CURRENT_RUN_ID.set(runId);
        String key = resolveReasoningKey(resolveSessionId()) + "::call::" + java.util.UUID.randomUUID();
        String completedRun=resolveReasoningKey(resolveSessionId());
        CURRENT_CALL_ID.set(key);
        return () -> {
            LAST_COMPLETED.set(new CompletedReasoning(sessionId,completedRun,LATEST_REASONING.remove(key)));
            NO_THINK_SESSIONS.remove(key);
            for (ReasoningContentFilter filter : instances()) {
                filter.sessionReasonings.remove(key);
                filter.toolReasonings.remove(key);
            }
            restore(CURRENT_SESSION_ID, previousSession);
            restore(CURRENT_RUN_ID, previousRun);
            restore(CURRENT_CALL_ID, previousCall);
        };
    }

    private static void restore(ThreadLocal<String> local, String value) {
        if (value == null) local.remove(); else local.set(value);
    }

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
    private static String resolveReasoningKey(String sessionId) {
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
        String key=CURRENT_CALL_ID.get()==null?sessionId:CURRENT_CALL_ID.get();
        boolean added=NO_THINK_SESSIONS.add(key);
        return () -> { if(added) NO_THINK_SESSIONS.remove(key); };
    }

    private static boolean isNoThinkSession(String sessionId) {
        return sessionId != null && NO_THINK_SESSIONS.contains(sessionId);
    }

    /** 立即回答：读取某 session 最近一次（含 mid-stream 截断的半截）reasoning_content，无则 null。 */
    public static String getLatestReasoning(String sessionId) {
        if (sessionId == null) return null;
        CompletedReasoning completed=LAST_COMPLETED.get();
        if(completed!=null && sessionId.equals(completed.session()) && resolveReasoningKey(sessionId).equals(completed.run())) return completed.text();
        if(CURRENT_CALL_ID.get()!=null) return LATEST_REASONING.get(CURRENT_CALL_ID.get());
        if(MDC.get("runId")!=null || CURRENT_RUN_ID.get()!=null) return null;
        return LATEST_REASONING.get(sessionId);
    }

    /** 轮末清理旁路缓存，避免 session 堆积（与 LongTermMemoryTurnSnapshot.clearSession 同处调用）。 */
    public static void clearLatestReasoning(String sessionId) {
        if (sessionId != null) LATEST_REASONING.remove(sessionId);
        if(LAST_COMPLETED.get()!=null && java.util.Objects.equals(sessionId,LAST_COMPLETED.get().session())) LAST_COMPLETED.remove();
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        Map<String, String> captured = captureContext();
        StreamingActivityTracker.Activity capturedActivity = StreamingActivityTracker.current();
        return Mono.deferContextual(context -> filterActive(request, next,
                context.getOrDefault(StreamingActivityTracker.CONTEXT_KEY, capturedActivity),
                context.getOrDefault(SESSION_CONTEXT_KEY, captured.get(SESSION_CONTEXT_KEY)),
                context.getOrDefault(CALL_CONTEXT_KEY,
                        context.getOrDefault(RUN_CONTEXT_KEY, captured.get(CALL_CONTEXT_KEY))),
                RunBudgetRegistry.find(context.getOrDefault(RUN_CONTEXT_KEY, captured.get(RUN_CONTEXT_KEY)))))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private Mono<ClientResponse> filterActive(ClientRequest request, ExchangeFunction next,
                                             StreamingActivityTracker.Activity streamingActivity,
                                             String sessionId, String reasoningKey, RunBudgetRegistry.State budget) {
        // Resolve per-attempt state at subscription, after advisor publishOn/thread switches.
        StreamingActivityTracker.checkActive(streamingActivity);
        // 注入缓存按 runId 隔离（跨题/并发不串扰）；sessionId 仍用于 LATEST_REASONING / 日志。
        List<String> reasonings = sessionReasonings.computeIfAbsent(reasoningKey,
                k -> Collections.synchronizedList(new ArrayList<>()));
        Map<String, String> byToolCall = toolReasonings.computeIfAbsent(reasoningKey, k -> new ConcurrentHashMap<>());

        // 简单 LRU：sessionMap 过大时清掉一些（仅一致性提醒，不严格 LRU，避免锁）
        if (sessionReasonings.size() > MAX_SESSIONS) {
            log.warn("[ReasoningFilter] session cache size {} exceeded {}, please check session lifecycle",
                    sessionReasonings.size(), MAX_SESSIONS);
        }

        // ============ Request 侧：reasoning_content 注入 + 关思考参数注入（一次解析/重建）============
        if (budget != null) budget.checkActive();
        PreparedRequest prepared = rewriteRequestBody(request, byToolCall, sessionId, isNoThinkSession(reasoningKey), budget);
        ClientRequest finalRequest = prepared.request();

        // ============ Response 侧：抓取 reasoning_content append 到 session 列表 ============
        Mono<ClientResponse> exchange = Mono.defer(() -> {
            // The first response of every tool follow-up can involve provider queueing/thinking.
            // Empty keepalives do not end this phase; meaningful SSE deltas below do.
            if (streamingActivity != null) streamingActivity.beginModelRequest();
            return next.exchange(finalRequest);
        });
        if (streamingActivity != null) exchange = exchange.takeUntilOther(streamingActivity.cancellation());
        if (budget != null) exchange = exchange.takeUntilOther(budget.termination());
        java.util.concurrent.atomic.AtomicBoolean bodyOwnsTicket=new java.util.concurrent.atomic.AtomicBoolean();
        return exchange.map(resp -> {
                    bodyOwnsTicket.set(true);
                    return wrapResponse(resp, reasonings, byToolCall, sessionId, reasoningKey, streamingActivity, budget, prepared.reservation(),prepared.usageTicket());
                })
                .onErrorResume(error -> {
                    if(prepared.reservation()!=null)prepared.reservation().settle(null);
                    return settleUsage(prepared.usageTicket(),null,"ERROR").then(Mono.error(error));
                })
                .doOnCancel(()->{
                    if(!bodyOwnsTicket.get()) {
                        if(prepared.reservation()!=null)prepared.reservation().settle(null);
                        settleUsage(prepared.usageTicket(),null,"CANCELLED").subscribe();
                    }
                });
    }

    /** 优先级：ThreadLocal > MDC.sessionId > MDC.requestId > "unknown-session" */
    private static String resolveSessionId() {
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

    private ClientResponse wrapResponse(ClientResponse response, List<String> reasonings, Map<String, String> byToolCall, String sessionId, String reasoningKey,
                                        StreamingActivityTracker.Activity streamingActivity, RunBudgetRegistry.State budget,
                                        RunBudgetRegistry.Reservation reservation,
                                        cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting.Ticket usageTicket) {
        Flux<DataBuffer> originalBody = response.body(BodyExtractors.toDataBuffers());
        // An internal recursive model request may outlive the outer ChatClient subscription.
        // The explicit attempt signal also cancels this HTTP body, without waiting for another chunk.
        if (streamingActivity != null) originalBody = originalBody.takeUntilOther(streamingActivity.cancellation());
        if (budget != null) originalBody = originalBody.takeUntilOther(budget.termination());
        SseFrames sseBuffer = new SseFrames();
        StringBuilder reasoningBuffer = new StringBuilder();
        Map<Integer, String> toolCallIds = new HashMap<>();
        // Names only: argument fragments may contain private document/code contents.
        Map<Integer, String> toolCallNames = new HashMap<>();
        java.util.concurrent.atomic.AtomicReference<Long> usage = new java.util.concurrent.atomic.AtomicReference<>();
        // A rejected request (4xx) was never processed: settle it at zero rather than as unknown usage.
        if (response.statusCode().is4xxClientError()) usage.set(0L);
        // Spring AI cancels the body after [DONE]; such a stream completed and settles as SUCCESS.
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();

        Flux<DataBuffer> wrappedBody = originalBody
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    StreamingActivityTracker.checkActive(streamingActivity);
                    try {
                        for (String event : sseBuffer.accept(bytes)) {
                            processSseEvent(event, reasoningBuffer, toolCallIds, toolCallNames, byToolCall, usage, finished, streamingActivity);
                        }
                        if(reasoningBuffer.length()>0 && toolReasonings.containsKey(reasoningKey)) {
                            if(LATEST_REASONING.size()>MAX_LATEST_SESSIONS) LATEST_REASONING.clear();
                            LATEST_REASONING.put(reasoningKey,reasoningBuffer.toString());
                        }
                    } catch (Exception e) {
                        StreamingActivityTracker.rethrowCancellation(e);
                        if (e instanceof ModelStreamException failure) throw failure;
                        log.debug("[ReasoningFilter] SSE parse error: {}", e.getMessage());
                    }
                    return BUFFER_FACTORY.wrap(bytes);
                })
                .doOnComplete(() -> {
                    if (!sseBuffer.remainder().isEmpty()) {
                        processSseEvent(sseBuffer.remainder(), reasoningBuffer, toolCallIds, toolCallNames, byToolCall, usage, finished, streamingActivity);
                    }
                    // The model's tool loop can subscribe to the next request from onComplete.
                    // Release this request's estimate before forwarding completion, not in doFinally.
                    if (reservation != null) reservation.settle(usage.get());
                })
                .doOnError(error -> { if (reservation != null) reservation.settle(usage.get()); })
                .doFinally(signal -> {
                    if (reservation != null) reservation.settle(usage.get());
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
                        if (sessionId != null && !"unknown-session".equals(sessionId) && toolReasonings.containsKey(reasoningKey)) {
                            if (LATEST_REASONING.size() > MAX_LATEST_SESSIONS) LATEST_REASONING.clear();
                            LATEST_REASONING.put(reasoningKey, captured);
                        }
                        log.info("[ReasoningFilter] reasoning_content captured ({} chars) session={} cacheSize={} signal={}",
                                captured.length(), sessionId, reasonings.size(), signal);
                    }
                });

        if(usageTicket!=null) {
            Flux<DataBuffer> body=wrappedBody;
            wrappedBody=Flux.usingWhen(Mono.just(usageTicket),ignored->body,
                    ticket->settleUsage(ticket,usage.get(),response.statusCode().is2xxSuccessful()?"SUCCESS":"ERROR"),
                    (ticket,error)->settleUsage(ticket,usage.get(),"ERROR"),
                    ticket->settleUsage(ticket,usage.get(),finished.get()&&response.statusCode().is2xxSuccessful()?"SUCCESS":"CANCELLED"));
        }
        return ClientResponse.from(response).body(wrappedBody).build();
    }

    private static Mono<Void> settleUsage(cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting.Ticket ticket,Long tokens,String outcome) {
        if(ticket==null)return Mono.empty();
        // JDBC settlement is an asynchronous cleanup resource, never Netty event-loop work.
        return Mono.<Void>fromRunnable(()->ticket.settle(tokens,outcome))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .onErrorResume(error->{log.warn("[ModelUsage] settlement deferred; conservative reservation retained: {}",error.getClass().getSimpleName());return Mono.empty();});
    }

    /** Decode only complete SSE frames: UTF-8 characters may span network buffers. */
    private static final class SseFrames {
        private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        private int trailing;
        List<String> accept(byte[] bytes) {
            List<String> frames = new ArrayList<>();
            for(byte value:bytes) {
                pending.write(value);
                trailing=(trailing<<8)|(value&255);
                if((trailing&65535)==0x0a0a || trailing==0x0d0a0d0a) {
                    frames.add(pending.toString(StandardCharsets.UTF_8)); pending.reset(); trailing=0;
                }
                if(pending.size()>1024*1024) throw new RunBudgetExceededException("模型流式数据帧过大，已停止接收");
            }
            return frames;
        }
        String remainder() { return pending.toString(StandardCharsets.UTF_8); }
    }

    @SuppressWarnings("unchecked")
    private void processSseEvent(String event, StringBuilder reasoningBuffer,
                                 Map<Integer, String> toolCallIds, Map<Integer, String> toolCallNames, Map<String, String> byToolCall,
                                 java.util.concurrent.atomic.AtomicReference<Long> usage,
                                 java.util.concurrent.atomic.AtomicBoolean finished,
                                 StreamingActivityTracker.Activity streamingActivity) {
        for (String line : event.split("\n")) {
            if (!line.startsWith("data:")) continue;
            String json = line.substring(5).trim();
            if ("[DONE]".equals(json)) finished.set(true);
            if (json.isEmpty() || "[DONE]".equals(json)) continue;
            try {
                Map<String, Object> chunk = OBJECT_MAPPER.readValue(json, MAP_TYPE);
                if (chunk.get("error") != null) throw ModelStreamException.providerError(chunk.get("error"));
                Long tokens = ModelRequestGuard.usage(chunk);
                if (tokens != null) usage.set(tokens);
                Object choicesObj = chunk.get("choices");
                if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) continue;
                Object first = choices.get(0);
                if (!(first instanceof Map<?, ?> choice)) continue;
                Object delta = choice.get("delta");
                if (delta instanceof Map<?, ?> d) {
                    if (streamingActivity != null &&
                            ((d.get("content") instanceof String text && !text.isEmpty())
                                    || (d.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()))) {
                        // Internal tool-call frames never reach ChatClientResponse: Spring AI
                        // consumes them to execute tools before emitting the final answer.
                        streamingActivity.markDecodedResponse();
                    }
                    Object rc = d.get("reasoning_content");
                    if (rc instanceof String s && !s.isEmpty()) {
                        reasoningBuffer.append(s);
                        if (streamingActivity != null) streamingActivity.markReasoning();
                    }
                    if (d.get("tool_calls") instanceof List<?> calls) {
                        for (Object value : calls) {
                            if (!(value instanceof Map<?, ?> call)) continue;
                            int index = call.get("index") instanceof Number n ? n.intValue() : 0;
                            if (call.get("id") instanceof String id && !id.isBlank()) {
                                toolCallIds.merge(index, id, (old, part) -> old.equals(part) ? old : old + part);
                            }
                            if (call.get("function") instanceof Map<?, ?> function
                                    && function.get("name") instanceof String name && !name.isEmpty()) {
                                toolCallNames.merge(index, name.length() > 128 ? name.substring(0, 128) : name,
                                        ReasoningContentFilter::mergeToolName);
                            }
                            if (streamingActivity != null && RunArtifactService.TOOL_NAME.equalsIgnoreCase(toolCallNames.get(index))) {
                                streamingActivity.markArtifactPhase("artifact_generating");
                            }
                        }
                    }
                    // Publish before delivering the frame: recursive tool follow-ups can subscribe
                    // before doFinally. Never infer the match from list position or completion order.
                    if (reasoningBuffer.length() > 0) {
                        String captured = reasoningBuffer.toString();
                        toolCallIds.values().forEach(id -> byToolCall.put(id, captured));
                    }
                }
            } catch (ModelStreamException failure) {
                throw failure;
            } catch (Exception ignored) {
            }
        }
    }

    /** Providers may send a fragmented, repeated or cumulative tool name. Bound retained metadata. */
    private static String mergeToolName(String previous, String part) {
        String result;
        if (previous.equals(part) || previous.endsWith(part)) result = previous;
        else if (part.startsWith(previous)) result = part;
        else result = previous + part;
        return result.length() > 128 ? result.substring(0, 128) : result;
    }

    // ====================================================================
    // Request 侧：按 messages 中 tool_calls assistant 顺序，按对齐索引注入
    // ====================================================================

    /**
     * 统一改写出站请求体：① reasoning_content 注入（mimo tool-call roundtrip）；② 关思考参数注入（finalize）。
     * 一次解析、一次重建；两者都不命中 → 返回 null（调用方用原 request，零影响）。
     */
    private record PreparedRequest(ClientRequest request, RunBudgetRegistry.Reservation reservation,
            cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting.Ticket usageTicket) {}

    private PreparedRequest rewriteRequestBody(ClientRequest original, Map<String, String> byToolCall, String sessionId,
                                               boolean noThink, RunBudgetRegistry.State budget) {
        byte[] bodyBytes = extractBodyBytes(original);
        if (bodyBytes == null || bodyBytes.length == 0) {
            if (original.method() == HttpMethod.POST)
                throw new IllegalStateException("无法读取模型请求体，未发送未经预算检查的请求");
            return new PreparedRequest(original, null, null);
        }
        try {
            Map<String, Object> requestMap = OBJECT_MAPPER.readValue(bodyBytes, MAP_TYPE);
            // Spring AI serializes UserMessage as text -> media, while MiMo's
            // documented and verified path uses image_url -> text. Normalize
            // only this wire copy; ChatMemory and Message objects are unchanged.
            boolean changed = OpenAiMultimodalRequestNormalizer.imagesBeforeText(requestMap);
            // 关思考时跳过 reasoning_content 注入：思考关了 mimo 不产 reasoning，再回填反而可能 400。
            changed |= !noThink && injectReasoningInto(requestMap, byToolCall, sessionId);
            if (noThink && disableThinkingFragment != null) {
                deepMerge(requestMap, disableThinkingFragment);
                changed = true;
                log.info("[ReasoningFilter] 关思考参数已注入出站 body session={}", sessionId);
            }
            ModelRequestGuard.Prepared guarded = ModelRequestGuard.prepare(requestMap, budget,usageContext);
            return new PreparedRequest(ClientRequest.from(original)
                    .headers(headers -> headers.remove(HttpHeaders.CONTENT_LENGTH))
                    .body(BodyInserters.fromValue(BUFFER_FACTORY.wrap(guarded.body())))
                    .build(), guarded.reservation(),guarded.usageTicket());
        } catch (RunBudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型请求上下文处理失败，未发送请求", e);
        }
    }

    /**
     * 把 reasoning_content 按顺序注入 requestMap 里的 tool_calls assistant 消息（原 injectReasoningContents 逻辑，改为就地操作 map）。
     * @return 是否改动了 requestMap
     */
    @SuppressWarnings("unchecked")
    private boolean injectReasoningInto(Map<String, Object> requestMap, Map<String, String> byToolCall, String sessionId) {
        Object messagesObj = requestMap.get("messages");
        if (!(messagesObj instanceof List<?> messages)) return false;
        boolean changed = false;
        for (Object value : messages) {
            if (!(value instanceof Map<?, ?> raw) || !"assistant".equals(raw.get("role"))
                    || !(raw.get("tool_calls") instanceof List<?> calls) || calls.isEmpty()) continue;
            Map<String, Object> message = (Map<String, Object>) raw;
            if (message.get("reasoning_content") instanceof String existing && !existing.isBlank()) continue;
            String reasoning = null;
            for (Object tool : calls) {
                if (tool instanceof Map<?, ?> call && call.get("id") instanceof String id) {
                    String match = byToolCall.get(id);
                    if (match != null) { reasoning = match; break; }
                }
            }
            message.put("reasoning_content", reasoning == null ? "[reasoning unavailable]" : reasoning);
            changed = true;
        }
        return changed;
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
            }).block(java.time.Duration.ofSeconds(5));

            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[ReasoningFilter] failed to extract request body: {}", e.getMessage());
            return null;
        }
    }
}
