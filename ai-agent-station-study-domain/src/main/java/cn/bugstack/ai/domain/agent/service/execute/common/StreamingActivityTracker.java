package cn.bugstack.ai.domain.agent.service.execute.common;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import org.springframework.ai.chat.client.ChatClientResponse;

import java.time.Duration;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks meaningful activity for one streaming LLM attempt.
 *
 * <p>The normal Reactor {@code timeout(Duration)} operator only sees decoded
 * {@code ChatClientResponse} items. Some OpenAI-compatible providers stream
 * {@code reasoning_content} at the raw SSE layer, where Spring AI may discard it.
 * This tracker lets the HTTP filter renew the same per-attempt watchdog when
 * reasoning arrives, without letting another parallel Flow step renew it.</p>
 */
public final class StreamingActivityTracker {

    /** Trusted application diagnostics, safe to show without transport/Java wrapper details. */
    public static final class WaitTimeoutException extends TimeoutException {
        public WaitTimeoutException(String message) { super(message); }
    }

    /** Private application state: never include this object in MCP JSON-RPC metadata. */
    public static final String CONTEXT_KEY = "agent.streaming_activity";
    private static final ThreadLocal<Activity> CURRENT = new ThreadLocal<>();

    private StreamingActivityTracker() {
    }

    public static Activity start(String callLabel) {
        return new Activity(callLabel);
    }

    /** Bind an activity object only while the request is subscribed. */
    public static Scope scope(Activity activity) {
        Activity previous = CURRENT.get();
        if (activity == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(activity);
        }
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** Called synchronously by {@link ReasoningContentFilter} when a request starts. */
    public static Activity current() {
        return CURRENT.get();
    }

    public static void restore(Activity activity) {
        if (activity == null) CURRENT.remove();
        else CURRENT.set(activity);
    }

    public static void clear() { CURRENT.remove(); }

    /** Fixed presentation labels for pre-model work; never include a query or provider payload. */
    public enum AdvisorPhase {
        MEMORY_RETRIEVAL("memory_retrieval", "正在检索记忆"),
        RAG_ROUTING("rag_routing", "正在判断知识库检索策略"),
        KNOWLEDGE_RETRIEVAL("knowledge_retrieval", "正在检索知识库"),
        KNOWLEDGE_RERANKING("knowledge_reranking", "正在筛选检索结果");

        private final String phase;
        private final String message;
        AdvisorPhase(String phase, String message) { this.phase = phase; this.message = message; }
        public String phase() { return phase; }
        public String message() { return message; }
    }

    /** A missing streaming UI does not change a synchronous Advisor's execution. */
    public static Scope advisorPhase(AdvisorPhase phase) {
        Objects.requireNonNull(phase, "phase");
        Activity activity = current();
        return activity == null ? () -> {} : activity.advisorPhase(phase);
    }

    public static Activity from(Map<String, Object> context) {
        Object explicit = context == null ? null : context.get(CONTEXT_KEY);
        return explicit instanceof Activity activity ? activity : current();
    }

    public static void checkActive(Activity activity) {
        if (activity != null) activity.checkActive();
    }

    /** Cancellation is control flow, not a tool failure that can be fed back to the model. */
    public static void rethrowCancellation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RunBudgetExceededException exhausted) throw exhausted;
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Streaming tool execution interrupted");
            }
            if (cause == cause.getCause()) break;
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    /** Safe UI state: no arguments, credentials, prompt text or provider payloads. */
    public record Progress(String phase, String message, long waitSeconds) { }

    /**
     * Apply an inactivity timeout that can be renewed externally by raw reasoning
     * SSE chunks. Callers mark decoded text/tool-call deltas before passing the
     * source here; metadata-only/empty keepalive frames intentionally do not renew it.
     */
    public static <T> Flux<T> timeoutOnInactivity(Flux<T> source, Activity activity, Duration idleTimeout) {
        return timeoutOnInactivity(source, activity, idleTimeout, idleTimeout, Duration.ofMinutes(5));
    }

    /** First model response and a real tool operation have separate, finite clocks. */
    public static <T> Flux<T> timeoutOnInactivity(Flux<T> source, Activity activity, Duration idleTimeout,
                                               Duration firstResponseTimeout, Duration toolTimeout) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        activity.configureWaits(firstResponseTimeout, toolTimeout);
        Map<String, String> reasoningContext = ReasoningContentFilter.captureContext();
        Flux<T> watched = source;
        if (!idleTimeout.isZero() && !idleTimeout.isNegative()) {
            long pollMillis = Math.max(25L, Math.min(1000L, idleTimeout.toMillis() / 4L));
            Mono<Void> timeoutSignal = Flux.interval(Duration.ofMillis(pollMillis))
                    .doOnNext(ignored -> activity.publishProgress())
                    .map(ignored -> activity.timeoutReason(idleTimeout))
                    .filter(reason -> !reason.isEmpty())
                    .next()
                    .flatMap(reason -> Mono.error(new WaitTimeoutException(reason)));
            watched = source.takeUntilOther(timeoutSignal);
        }
        // Terminal callbacks run before the signal reaches the blocking caller, so a retry can
        // never race with the old attempt still being marked usable.
        return watched.doOnSubscribe(ignored -> activity.publishProgress())
                .doOnCancel(activity::cancel)
                .doOnError(ignored -> activity.cancel())
                .doOnComplete(activity::cancel)
                .contextWrite(context -> context.put(CONTEXT_KEY, activity)
                        .putAllMap(reasoningContext));
    }

    public static final class Activity {
        private final String callLabel;
        private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean toolActivity = new AtomicBoolean();
        private final Sinks.One<Void> cancellation = Sinks.one();
        private final ConcurrentHashMap<Object, UserWait> userWaitDeadlines = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Object, ToolWait> toolWaits = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Object, AdvisorWait> advisorWaits = new ConcurrentHashMap<>();
        private final AtomicLong advisorSequence = new AtomicLong();
        private volatile Duration firstResponseTimeout = Duration.ofMinutes(5);
        private volatile Duration toolTimeout = Duration.ofMinutes(5);
        private volatile long firstResponseStartedNanos;
        private volatile boolean modelActivityObserved;
        private volatile java.util.function.Consumer<Progress> progressListener;
        private volatile String lastProgressPhase;
        private volatile long lastProgressNanos;
        private final Object progressMonitor = new Object();
        private final java.util.concurrent.atomic.AtomicReference<ArtifactPhase> artifactPhase =
                new java.util.concurrent.atomic.AtomicReference<>();

        private record ToolWait(String name, long startedNanos, long deadlineNanos, long maximumWaitSeconds) { }
        private record UserWait(long startedNanos, long deadlineNanos) { }
        private record ArtifactPhase(String name, long startedNanos) { }
        private record AdvisorWait(AdvisorPhase phase, long startedNanos, long sequence) { }

        private Activity(String callLabel) {
            this.callLabel = callLabel == null || callLabel.isBlank() ? "streaming-call" : callLabel;
        }

        public String callLabel() {
            return callLabel;
        }

        public void onProgress(java.util.function.Consumer<Progress> listener) {
            if (!cancelled.get()) progressListener = listener;
        }

        private void publishProgress() {
            synchronized (progressMonitor) {
                java.util.function.Consumer<Progress> listener = progressListener;
                if (listener == null || cancelled.get()) return;
                long now = System.nanoTime();
                Progress update = progress(now);
                if (update.phase().equals(lastProgressPhase) && now - lastProgressNanos < Duration.ofSeconds(10).toNanos()) return;
                lastProgressPhase = update.phase();
                lastProgressNanos = now;
                if (cancelled.get()) return;
                try { listener.accept(update); }
                catch (RuntimeException ignored) { /* UI delivery cannot change execution or renew its timeout. */ }
            }
        }

        private Progress progress(long now) {
            long userStarted = userWaitDeadlines.values().stream().mapToLong(UserWait::startedNanos).min().orElse(0L);
            if (userStarted != 0L) return new Progress("awaiting_user", "等待你补充信息或确认；此期间不计模型静默超时", elapsed(now, userStarted));
            ArtifactPhase artifact = artifactPhase.get();
            if (artifact != null) return new Progress(artifact.name(), artifactMessage(artifact.name()), elapsed(now, artifact.startedNanos()));
            long toolStarted = toolWaits.values().stream().mapToLong(ToolWait::startedNanos).min().orElse(0L);
            if (toolStarted != 0L) return new Progress("tool_running", "正在执行工具或等待工具连接", elapsed(now, toolStarted));
            AdvisorWait advisor = advisorWaits.values().stream()
                    .max(java.util.Comparator.comparingLong(AdvisorWait::sequence)).orElse(null);
            if (advisor != null) return new Progress(advisor.phase().phase(), advisor.phase().message(), elapsed(now, advisor.startedNanos()));
            long firstWait = firstResponseStartedNanos;
            if (firstWait != 0L) return new Progress("awaiting_model", "请求已发送，等待模型开始返回（最长 "
                    + firstResponseTimeout.toSeconds() + " 秒）", elapsed(now, firstWait));
            if (!modelActivityObserved) return new Progress("preparing_model", "正在准备模型请求", elapsed(now, lastActivityNanos.get()));
            return new Progress("model_streaming", "正在接收模型回答或思考内容", elapsed(now, lastActivityNanos.get()));
        }

        private static long elapsed(long now, long started) { return Math.max(0L, (now - started) / 1_000_000_000L); }

        /**
         * Presentation-only scoped state. Closing a scope restores the still-active parent;
         * out-of-order closes cannot remove a sibling or another Flow step's state.
         * Neither entry nor exit touches any inactivity, first-response, tool or run deadline.
         */
        public Scope advisorPhase(AdvisorPhase phase) {
            Objects.requireNonNull(phase, "phase");
            Object token = new Object();
            synchronized (progressMonitor) {
                if (cancelled.get()) return () -> {};
                advisorWaits.put(token, new AdvisorWait(phase, System.nanoTime(), advisorSequence.incrementAndGet()));
                if (cancelled.get()) advisorWaits.remove(token);
                publishProgress();
            }
            return () -> {
                synchronized (progressMonitor) {
                    if (advisorWaits.remove(token) != null) publishProgress();
                }
            };
        }

        /** UI-only phase; receiving progress must never renew an execution deadline. */
        public void markArtifactPhase(String phase) {
            artifactMessage(phase); // Only fixed application labels may reach the browser.
            if (cancelled.get()) return;
            artifactPhase.updateAndGet(previous -> previous != null && previous.name().equals(phase)
                    ? previous : new ArtifactPhase(phase, System.nanoTime()));
            publishProgress();
        }

        public void clearArtifactPhase() {
            if (artifactPhase.getAndSet(null) != null) publishProgress();
        }

        private static String artifactMessage(String phase) {
            if (phase == null) throw new IllegalArgumentException("Unknown artifact progress phase");
            return switch (phase) {
                case "artifact_generating" -> "正在生成产物内容";
                case "artifact_repairing" -> "产物参数有误，正在修复格式";
                case "artifact_saving" -> "正在保存产物";
                case "artifact_fallback" -> "产物未保存，正在保留回答正文";
                default -> throw new IllegalArgumentException("Unknown artifact progress phase");
            };
        }

        public void markDecodedResponse() {
            if (!cancelled.get()) {
                modelActivityObserved = true;
                firstResponseStartedNanos = 0L;
                lastActivityNanos.set(System.nanoTime());
            }
        }

        /** Called once at each HTTP model exchange, including a recursive tool follow-up. */
        public void beginModelRequest() {
            checkActive();
            modelActivityObserved = false;
            firstResponseStartedNanos = System.nanoTime();
        }

        private void configureWaits(Duration firstResponse, Duration tool) {
            // A typo must not turn a quiet provider or a hung tool into an unlimited wait.
            firstResponseTimeout = positive(firstResponse, Duration.ofMinutes(5));
            toolTimeout = positive(tool, Duration.ofMinutes(5));
        }

        private static Duration positive(Duration duration, Duration fallback) {
            return duration == null || duration.isZero() || duration.isNegative() ? fallback : duration;
        }

        /** Empty/metadata-only frames are not meaningful model activity. */
        public void markDecodedResponse(ChatClientResponse response) {
            if (response == null || response.chatResponse() == null
                    || response.chatResponse().getResult() == null
                    || response.chatResponse().getResult().getOutput() == null) return;
            var output = response.chatResponse().getResult().getOutput();
            String text = output.getText();
            if ((text != null && !text.isEmpty()) || output.hasToolCalls()) {
                markDecodedResponse();
            }
        }

        public void markReasoning() {
            markDecodedResponse();
        }

        public void markToolActivity() {
            checkActive();
            toolActivity.set(true);
            markDecodedResponse();
        }

        public boolean hasToolActivity() { return toolActivity.get(); }

        public void cancel() {
            // Execution cancellation must never wait for a browser's progress delivery lock.
            if (cancelled.compareAndSet(false, true)) {
                progressListener = null;
                advisorWaits.clear();
                cancellation.tryEmitEmpty();
            }
        }

        public Mono<Void> cancellation() { return cancellation.asMono(); }

        public boolean isCancelled() { return cancelled.get(); }

        public void checkActive() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Streaming attempt is no longer active (" + callLabel + ")");
            }
        }

        /** Human response time is governed by the gate's finite deadline, not model inactivity. */
        public Scope awaitingUser(Duration maximumWait) {
            checkActive();
            Object token = new Object();
            long now = System.nanoTime();
            userWaitDeadlines.put(token, new UserWait(now, now + maximumWait.toNanos()));
            return () -> {
                userWaitDeadlines.remove(token);
                markDecodedResponse();
            };
        }

        /** One operation includes connection queueing/retries; activity cannot renew its hard deadline. */
        public Scope executingTool(String toolName) {
            return executingTool(toolName, toolTimeout);
        }

        public Scope executingTool(String toolName, Duration maximumWait) {
            checkActive();
            markToolActivity();
            Object token = new Object();
            Duration bound = positive(maximumWait, toolTimeout);
            long now = System.nanoTime();
            toolWaits.put(token, new ToolWait(toolName == null ? "tool" : toolName,
                    now, now + bound.toNanos(), bound.toSeconds()));
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    toolWaits.remove(token);
                    markDecodedResponse();
                }
            };
        }

        public boolean isIdle(Duration idleTimeout) {
            return !timeoutReason(idleTimeout).isEmpty();
        }

        private String timeoutReason(Duration idleTimeout) {
            long now = System.nanoTime();
            for (ToolWait wait : toolWaits.values()) {
                if (now >= wait.deadlineNanos()) {
                    return "工具执行或等待连接超过 " + wait.maximumWaitSeconds() + " 秒（" + wait.name()
                            + "），已停止当前步骤。请检查工具状态；未自动重复执行。";
                }
            }
            if (userWaitDeadlines.values().stream().anyMatch(wait -> now < wait.deadlineNanos())) return "";
            if (!toolWaits.isEmpty()) return "";
            long firstWait = firstResponseStartedNanos;
            if (firstWait != 0L) {
                return now - firstWait >= firstResponseTimeout.toNanos()
                        ? "模型服务在 " + firstResponseTimeout.toSeconds()
                        + " 秒内未返回正文、思考或工具调用，已停止等待。可稍后重试或更换模型。"
                        : "";
            }
            return now - lastActivityNanos.get() >= idleTimeout.toNanos()
                    ? "模型响应已连续 " + idleTimeout.toSeconds()
                    + " 秒没有新内容，已停止等待。可稍后重试或更换模型。" : "";
        }
    }
}
