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
public class AiEvalCase {
    private Long id;
    private String caseId;
    private String versionId;
    private String stableKey;
    private Integer sequenceNo;
    private String conversationGroup;
    private String category;
    private String tagsJson;
    private String question;
    private String configJson;
    private String referenceAnswer;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
