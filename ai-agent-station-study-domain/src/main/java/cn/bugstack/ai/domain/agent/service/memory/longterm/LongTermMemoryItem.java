package cn.bugstack.ai.domain.agent.service.memory.longterm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 长期记忆管理视图。只包含用户可以查看和编辑的元数据，不暴露向量内部字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryItem {

    private String memoryId;
    private String topic;
    private String content;
    private String source;
    private String sourceSession;
    private Integer accessCount;
    private LocalDateTime lastAccessed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 仅语义搜索结果有值，越大表示越相关。 */
    private Double similarity;
}
