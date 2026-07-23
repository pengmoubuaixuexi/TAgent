package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Spring AI ChatMemory 持久化表 PO（P0.2.1）。
 * <p>
 * 一条消息一行；conversation_id 即业务 sessionId。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiChatMemory {

    private Long id;

    private String conversationId;

    private String userId;

    private String agentId;

    private String runId;

    /** USER / ASSISTANT / SYSTEM */
    private String messageType;

    private String content;

    /** Provider-neutral text/image part references; never contains Base64. */
    private String contentParts;

    private Integer mediaCount;

    private LocalDateTime createdAt;
}
