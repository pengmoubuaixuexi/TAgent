package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** A full prompt may only be retried before it has produced output or started a tool. */
public final class StreamingRetryPolicy {
    private StreamingRetryPolicy() { }

    public static CancellationException cancellation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof CancellationException cancelled) return cancelled;
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new CancellationException("execution interrupted during streaming");
            }
        }
        return null;
    }

    public static boolean canRetry(Throwable failure, boolean toolStarted, int outputChars,
                                   int attempt, int maxAttempts) {
        if (toolStarted || outputChars > 0 || attempt >= maxAttempts
                || Thread.currentThread().isInterrupted() || cancellation(failure) != null) return false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof IOException) return true;
            if (t instanceof WebClientResponseException http) {
                return http.getStatusCode().value() == 429 || http.getStatusCode().is5xxServerError();
            }
        }
        return false;
    }

    public static RuntimeException failed(String step, Throwable cause) {
        String reason = cause == null ? "stream ended without an answer" : cause.getMessage();
        if (reason == null || reason.isBlank()) reason = cause == null ? "unknown" : cause.getClass().getSimpleName();
        return new IllegalStateException("Streaming step " + step + " failed: " + reason, cause);
    }
}
