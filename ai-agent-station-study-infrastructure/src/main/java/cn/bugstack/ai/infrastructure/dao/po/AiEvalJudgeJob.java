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
public class AiEvalJudgeJob {
    private Long id;
    private String judgeJobId;
    private String evalRunId;
    private String scopeType;
    private String status;
    private String modelId;
    private String apiId;
    private String rubricVersion;
    private String configJson;
    private Integer totalCases;
    private Integer completedCases;
    private Integer failedCases;
    private BigDecimal averageScore;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
