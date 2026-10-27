package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Locale;

/** agent.attachments.storage: auto (OSS when agent.multimodal.oss.enabled, else local), oss or local. */
@Slf4j
@Configuration
public class ChatFileStorageConfig {

    @Bean
    public ChatFileStorage chatFileStorage(
            @Value("${agent.attachments.storage:auto}") String mode,
            @Value("${agent.multimodal.oss.enabled:false}") boolean ossEnabled,
            @Value("${agent.multimodal.oss.region:cn-beijing}") String region,
            @Value("${agent.multimodal.oss.endpoint:https://oss-cn-beijing.aliyuncs.com}") String endpoint,
            @Value("${agent.multimodal.oss.bucket:}") String bucket,
            @Value("${agent.multimodal.oss.signed-url-ttl-seconds:1800}") long ttlSeconds,
            @Value("${agent.attachments.local-dir:./data/chat-files}") String localDir) {
        String selected = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        boolean oss = "oss".equals(selected) || ("auto".equals(selected) && ossEnabled);
        if (oss) {
            log.info("[ChatFile] attachments stored in OSS bucket={}", bucket);
            return new OssChatFileStorage(region, endpoint, bucket, ttlSeconds);
        }
        Path root = Path.of(localDir);
        log.info("[ChatFile] attachments stored locally dir={}", root.toAbsolutePath().normalize());
        return new LocalChatFileStorage(root);
    }
}
