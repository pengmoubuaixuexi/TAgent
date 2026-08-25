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
public class AiEvalJudgeResult {
    private Long id;
    private String judgeResultId;
    private String judgeJobId;
    private String resultId;
    private String status;
    private Integer correctness;
    private Integer relevance;
    private Integer completeness;
    private Integer usefulness;
    private Integer safety;
    private Integer overall;
    private String verdict;
    private String reason;
    private String issuesJson;
    private String rawResponse;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
