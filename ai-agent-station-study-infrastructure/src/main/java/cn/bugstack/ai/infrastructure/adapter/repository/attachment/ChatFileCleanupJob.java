package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Platform safety net for attachments; there is no per-user quota. Removes uploads that were
 * never sent, purges the least recently used ones above the total threshold, and sweeps caches.
 */
@Slf4j
@Component
public class ChatFileCleanupJob {

    private final ChatFileService files;

    @Value("${agent.attachments.pending-ttl-hours:24}")
    private long pendingTtlHours = 24;

    /** 0 disables the size threshold. */
    @Value("${agent.attachments.cleanup.max-total-bytes:10737418240}")
    private long maxTotalBytes = 10L * 1024 * 1024 * 1024;

    public ChatFileCleanupJob(ChatFileService files) {
        this.files = files;
    }

    @Scheduled(fixedDelayString = "${agent.attachments.cleanup.interval-ms:3600000}",
            initialDelayString = "${agent.attachments.cleanup.initial-delay-ms:300000}")
    public void cleanup() {
        try {
            int expired = files.deleteExpiredPending(Duration.ofHours(Math.max(1, pendingTtlHours)));
            int purged = files.purgeLeastRecentlyUsed(maxTotalBytes);
            int caches = files.sweepCache();
            if (expired + purged + caches > 0) {
                log.info("[ChatFile] cleanup expiredPending={} purged={} caches={}", expired, purged, caches);
            }
        } catch (RuntimeException failure) {
            log.warn("[ChatFile] cleanup failed: {}", failure.toString());
        }
    }
}
