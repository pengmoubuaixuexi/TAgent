package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Spring AI ChatMemory 滚动摘要表 PO（P0.2.2）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiChatMemorySummary {

    private Long id;

    /** 对话 ID（== sessionId），UNIQUE */
    private String conversationId;

    /** 用户 ID，从 conversationId 中提取 */
    private String userId;

    /** 摘要文本；读取历史时作为 SystemMessage 拼到最前面 */
    private String summary;

    /** 本次摘要覆盖到 ai_chat_memory.id 的最大值，用于增量摘要 */
    private Long lastMessageId;

    /** 版本号，每次刷新 +1 */
    private Integer version;

    /** 已纳入摘要的消息数（水位线）；渐进式摘要用 */
    private Integer summaryMsgCount;

    /** 上次触发摘要时的总消息数；用于计算下次触发时机 */
    private Integer detailMsgCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
