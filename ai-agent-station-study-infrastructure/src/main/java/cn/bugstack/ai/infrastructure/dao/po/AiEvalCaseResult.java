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
public class AiEvalCaseResult {
    private Long id;
    private String resultId;
    private String evalRunId;
    private String caseId;
    private String stableKey;
    private Integer attemptNo;
    private String status;
    private String agentRunId;
    private String sessionId;
    private String agentId;
    private String strategy;
    private String finalAnswer;
    private String traceJson;
    private String signalsJson;
    private BigDecimal routeScore;
    private BigDecimal answerScore;
    private BigDecimal stepScore;
    private BigDecimal toolScore;
    private BigDecimal groundingScore;
    private BigDecimal memoryScore;
    private BigDecimal stabilityScore;
    private BigDecimal efficiencyScore;
    private BigDecimal safetyScore;
    private BigDecimal overallScore;
    private String grade;
    private String warningsJson;
    private Long latencyMs;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
