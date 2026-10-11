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
                || Thread.currentThread().isInterrupted() || budgetFailure(failure) != null
                || cancellation(failure) != null) return false;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable t = failure; t != null && seen.size() < 32 && seen.add(t); t = t.getCause()) {
            if (t instanceof ModelStreamException stream) return stream.canRetryAt(attempt);
        }
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof IOException) return true;
            if (t instanceof WebClientResponseException http) {
                return http.getStatusCode().value() == 429 || http.getStatusCode().is5xxServerError();
            }
        }
        return false;
    }

    public static RuntimeException failed(String step, Throwable cause) {
        // Spring AI can wrap a local budget rejection as "Stream processing failed".
        // Prefer this trusted, user-actionable reason without exposing arbitrary provider causes.
        RunBudgetExceededException budget = budgetFailure(cause);
        if (budget != null) return new IllegalStateException(budget.getMessage(), cause);
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = cause; current != null && seen.size() < 32 && seen.add(current); current = current.getCause()) {
            if (current instanceof ModelStreamException stream)
                return new IllegalStateException(stream.getMessage(), cause);
            if (current instanceof StreamingActivityTracker.WaitTimeoutException timeout)
                return new IllegalStateException(timeout.getMessage(), cause);
            if (current instanceof WebClientResponseException http) {
                String message = switch (http.getStatusCode().value()) {
                    case 400, 422 -> "模型服务拒绝了请求参数（HTTP " + http.getStatusCode().value() + "），本次生成未完成。请重试；若重复出现，请检查模型与工具参数兼容性。";
                    case 401, 403 -> "模型服务认证或授权失败，请检查模型连接设置。";
                    case 429 -> "模型服务当前限流或额度不足，请稍后重试或检查提供商额度。";
                    default -> "模型服务请求失败（HTTP " + http.getStatusCode().value() + "），请稍后重试。";
                };
                return new IllegalStateException(message, cause);
            }
        }
        String reason = budget != null ? budget.getMessage()
                : cause == null ? "stream ended without an answer" : cause.getMessage();
        if (reason == null || reason.isBlank()) reason = cause == null ? "unknown" : cause.getClass().getSimpleName();
        return new IllegalStateException("Streaming step " + step + " failed: " + reason, cause);
    }

    private static RunBudgetExceededException budgetFailure(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = failure; current != null && seen.size() < 32 && seen.add(current); current = current.getCause()) {
            if (current instanceof RunBudgetExceededException budget) return budget;
        }
        return null;
    }
}
