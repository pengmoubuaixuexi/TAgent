package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户级语义记忆元数据 PO（P1.7）。
 * <p>
 * 配套 {@code ai_long_term_memory} 表；与 PgVector 中的 embedding 行通过 {@code memory_id} 反查。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiLongTermMemory {

    private Long id;
    /** 全局 UUID，PgVector metadata.memory_id 走它 */
    private String memoryId;
    private String userId;
    private String tenantId;
    /** preference / fact / decision / skill / other */
    private String topic;
    private String content;
    /** auto / manual */
    private String source;
    private String sourceSession;
    private Integer accessCount;
    private LocalDateTime lastAccessed;
    private Integer archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
