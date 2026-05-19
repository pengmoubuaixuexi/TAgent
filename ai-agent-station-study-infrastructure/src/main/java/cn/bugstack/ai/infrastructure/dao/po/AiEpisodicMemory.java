package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 跨会话摘要记忆 PO（P2.1 Episodic Memory）。
 * <p>
 * 配套 {@code ai_episodic_memory} 表；每条记录是一次会话的 1-2 句摘要。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiEpisodicMemory {

    private Long id;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String topic;
    private String summary;
    private LocalDateTime createdAt;
    /** V019：最后更新时间，跨会话注入排序按此（每次 upsert 自动刷新） */
    private LocalDateTime updatedAt;
    /** V020：上次摘要时记录的消息总数，用于"每 2 轮"节流（lastSummarizedAt 命名只是历史，实际是 msgCount） */
    private Integer lastSummarizedMsgCount;
}
