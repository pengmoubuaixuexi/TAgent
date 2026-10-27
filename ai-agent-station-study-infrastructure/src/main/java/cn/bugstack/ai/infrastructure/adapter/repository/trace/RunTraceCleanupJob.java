package cn.bugstack.ai.infrastructure.adapter.repository.trace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trace retention. Traces are kept for good by default; with a retention period, older traces are
 * removed unless the run has feedback, an evaluation result or an improvement suggestion.
 */
@Slf4j
@Component
public class RunTraceCleanupJob {

    private final RunTraceStore traces;

    /** 0 keeps traces forever. */
    @Value("${agent.trace.retention-days:0}")
    private int retentionDays;

    public RunTraceCleanupJob(RunTraceStore traces) {
        this.traces = traces;
    }

    @Scheduled(fixedDelayString = "${agent.trace.cleanup.interval-ms:21600000}",
            initialDelayString = "${agent.trace.cleanup.initial-delay-ms:600000}")
    public void cleanup() {
        if (retentionDays <= 0) return;
        try {
            int removed = traces.purgeOlderThan(retentionDays, 500);
            if (removed > 0) log.info("[RunTrace] removed traces of {} runs older than {} days", removed, retentionDays);
        } catch (RuntimeException failure) {
            log.warn("[RunTrace] cleanup failed: {}", failure.toString());
        }
    }
}
