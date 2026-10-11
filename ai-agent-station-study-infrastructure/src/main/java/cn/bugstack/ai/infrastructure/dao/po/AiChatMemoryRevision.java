package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/** Index-only fingerprint for append/delete changes; message bodies stay in Redis. */
@Data
public class AiChatMemoryRevision {
    private int messageCount;
    private Long latestMessageId;
}
