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
public class AiEvalDatasetVersion {
    private Long id;
    private String versionId;
    private String datasetId;
    private Integer versionNo;
    private String status;
    private String description;
    private Integer caseCount;
    private String evaluatorVersion;
    private String evaluatorConfigJson;
    private String checksum;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
