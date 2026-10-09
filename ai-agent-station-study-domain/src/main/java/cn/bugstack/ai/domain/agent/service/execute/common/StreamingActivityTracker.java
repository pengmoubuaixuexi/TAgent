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

    /**
     * Apply an inactivity timeout that can be renewed externally by raw reasoning
     * SSE chunks. Callers mark decoded text/tool-call deltas before passing the
     * source here; metadata-only/empty keepalive frames intentionally do not renew it.
     */
    public static <T> Flux<T> timeoutOnInactivity(Flux<T> source, Activity activity, Duration idleTimeout) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Map<String, String> reasoningContext = ReasoningContentFilter.captureContext();
        Flux<T> watched = source;
        if (!idleTimeout.isZero() && !idleTimeout.isNegative()) {
            long pollMillis = Math.max(25L, Math.min(1000L, idleTimeout.toMillis() / 4L));
            Mono<Void> timeoutSignal = Flux.interval(Duration.ofMillis(pollMillis))
                    .filter(ignored -> activity.isIdle(idleTimeout))
                    .next()
                    .flatMap(ignored -> Mono.error(new TimeoutException(
                            "No model content, reasoning, or tool-call activity for "
                                    + idleTimeout.toSeconds() + "s (" + activity.callLabel() + ")")));
            watched = source.takeUntilOther(timeoutSignal);
        }
        // Terminal callbacks run before the signal reaches the blocking caller, so a retry can
        // never race with the old attempt still being marked usable.
        return watched.doOnCancel(activity::cancel)
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
        private final ConcurrentHashMap<Object, Long> userWaitDeadlines = new ConcurrentHashMap<>();

        private Activity(String callLabel) {
            this.callLabel = callLabel == null || callLabel.isBlank() ? "streaming-call" : callLabel;
        }

        public String callLabel() {
            return callLabel;
        }

        public void markDecodedResponse() {
            if (!cancelled.get()) lastActivityNanos.set(System.nanoTime());
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
            if (cancelled.compareAndSet(false, true)) cancellation.tryEmitEmpty();
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
            userWaitDeadlines.put(token, System.nanoTime() + maximumWait.toNanos());
            return () -> {
                userWaitDeadlines.remove(token);
                markDecodedResponse();
            };
        }

        public boolean isIdle(Duration idleTimeout) {
            long now = System.nanoTime();
            if (userWaitDeadlines.values().stream().anyMatch(deadline -> now < deadline)) return false;
            return now - lastActivityNanos.get() >= idleTimeout.toNanos();
        }
    }
}
