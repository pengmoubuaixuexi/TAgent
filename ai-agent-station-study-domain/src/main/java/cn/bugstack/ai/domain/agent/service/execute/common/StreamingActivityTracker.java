package cn.bugstack.ai.domain.agent.service.execute.common;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.ai.chat.client.ChatClientResponse;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
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

    private static final ThreadLocal<Activity> CURRENT = new ThreadLocal<>();

    private StreamingActivityTracker() {
    }

    public static Activity start(String callLabel) {
        return new Activity(callLabel);
    }

    /** Bind an activity object only while the request is subscribed. */
    public static AutoCloseable scope(Activity activity) {
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
    static Activity current() {
        return CURRENT.get();
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
        if (idleTimeout.isZero() || idleTimeout.isNegative()) return source;

        long pollMillis = Math.max(25L, Math.min(1000L, idleTimeout.toMillis() / 4L));
        Mono<Void> timeoutSignal = Flux.interval(Duration.ofMillis(pollMillis))
                .filter(ignored -> activity.isIdle(idleTimeout))
                .next()
                .flatMap(ignored -> Mono.error(new TimeoutException(
                        "No model content, reasoning, or tool-call activity for "
                                + idleTimeout.toSeconds() + "s (" + activity.callLabel() + ")")));

        return source.takeUntilOther(timeoutSignal);
    }

    public static final class Activity {
        private final String callLabel;
        private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());

        private Activity(String callLabel) {
            this.callLabel = callLabel == null || callLabel.isBlank() ? "streaming-call" : callLabel;
        }

        public String callLabel() {
            return callLabel;
        }

        public void markDecodedResponse() {
            lastActivityNanos.set(System.nanoTime());
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
            lastActivityNanos.set(System.nanoTime());
        }

        public boolean isIdle(Duration idleTimeout) {
            return System.nanoTime() - lastActivityNanos.get() >= idleTimeout.toNanos();
        }
    }
}
