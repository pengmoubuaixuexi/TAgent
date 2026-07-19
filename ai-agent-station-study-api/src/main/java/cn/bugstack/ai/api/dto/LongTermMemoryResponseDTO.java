package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 长期记忆管理页单条记录。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memoryId;
    private String topic;
    private String content;
    private String source;
    private String sourceSession;
    private Integer accessCount;
    private LocalDateTime lastAccessed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Double similarity;
}
