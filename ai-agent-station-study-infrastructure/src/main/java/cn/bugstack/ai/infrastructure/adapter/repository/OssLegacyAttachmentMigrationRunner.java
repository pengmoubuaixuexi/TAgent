package cn.bugstack.ai.infrastructure.adapter.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class OssLegacyAttachmentMigrationRunner implements ApplicationRunner {

    private final ChatImageAttachmentService attachmentService;
    private final boolean enabled;

    OssLegacyAttachmentMigrationRunner(
            ChatImageAttachmentService attachmentService,
            @Value("${agent.multimodal.oss.migrate-legacy-on-startup:false}") boolean enabled) {
        this.attachmentService = attachmentService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        int migrated = attachmentService.migrateLegacyToOss();
        log.info("legacy chat images migrated to OSS count={}", migrated);
    }
}
