package cn.bugstack.ai.infrastructure.adapter.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Keeps text-only deployments bootable until their own OSS bucket and RAM
 * credentials are configured. Image requests fail explicitly instead of
 * silently persisting payloads in the database.
 */
@Service
@ConditionalOnProperty(
        name = "agent.multimodal.oss.enabled",
        havingValue = "false",
        matchIfMissing = true)
class DisabledChatImageStorage implements ChatImageObjectStorage {

    private static final String MESSAGE =
            "Image storage is disabled. Configure OSS and set TAGENT_OSS_ENABLED=true.";

    @Override
    public StoredObject put(String objectKey, byte[] data, String mimeType) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public String createSignedGetUrl(String objectKey) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public void delete(String objectKey) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public String provider() {
        return "DISABLED";
    }

    @Override
    public String bucket() {
        return "";
    }
}
