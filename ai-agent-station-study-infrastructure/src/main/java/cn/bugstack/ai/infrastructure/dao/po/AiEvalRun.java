package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiEvalRun {
    private Long id;
    private String evalRunId;
    private String datasetId;
    private String versionId;
    private Integer versionNo;
    private String name;
    private String status;
    private String executionMode;
    private String userId;
    private String tenantId;
    private String selectedAgentId;
    private String memoryPolicy;
    private Integer concurrency;
    private String evaluatorVersion;
    private String configSnapshotJson;
    private Integer totalCases;
    private Integer completedCases;
    private Integer passedCases;
    private Integer failedCases;
    private BigDecimal ruleScore;
    private BigDecimal judgeScore;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
