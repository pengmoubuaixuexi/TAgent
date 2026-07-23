package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stable image reference stored with a ChatMemory user message.
 *
 * <p>The binary payload is populated only while executing or restoring a
 * message. It is deliberately excluded from the provider-neutral
 * {@code content_parts} JSON stored in {@code ai_chat_memory}.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatImageRef {

    private String attachmentId;

    /** URL or BASE64. */
    private String sourceType;

    private String sourceUrl;

    /**
     * Short-lived URL used only for the current model/browser request.
     * Never persist it in ChatMemory because OSS signatures expire.
     */
    private String accessUrl;

    private String mimeType;

    private String name;

    private Long size;

    private String sha256;

    /** In-memory payload for local uploads; never serialize into ChatMemory JSON. */
    private byte[] data;
}
