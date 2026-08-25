package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiEvalDataset {
    private Long id;
    private String datasetId;
    private String name;
    private String description;
    private String executionMode;
    private String ownerUserId;
    private String status;
    private Integer latestVersionNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
