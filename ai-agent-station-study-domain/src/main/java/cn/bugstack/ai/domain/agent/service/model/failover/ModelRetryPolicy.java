package cn.bugstack.ai.domain.agent.service.model.failover;

import java.time.Duration;

/** Retries of one target for transient failures. A switch to another target starts at attempt 1 again. */
public record ModelRetryPolicy(int attemptsPerTarget, Duration initialBackoff, Duration maxBackoff) {
    /** One attempt per target: transient failures are returned unchanged. */
    public static final ModelRetryPolicy SINGLE_ATTEMPT = new ModelRetryPolicy(1, Duration.ZERO, Duration.ZERO);

    public ModelRetryPolicy {
        if (attemptsPerTarget < 1 || initialBackoff == null || maxBackoff == null
                || initialBackoff.isNegative() || maxBackoff.isNegative())
            throw new IllegalArgumentException("Invalid model retry policy");
    }

    /** Exponential backoff; a provider Retry-After wins, still capped by {@link #maxBackoff}. */
    Duration delay(int failedAttempt, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) return min(retryAfter, maxBackoff);
        long factor = 1L << Math.min(16, Math.max(0, failedAttempt - 1));
        return min(initialBackoff.multipliedBy(factor), maxBackoff);
    }

    private static Duration min(Duration a, Duration b) { return a.compareTo(b) <= 0 ? a : b; }
}
