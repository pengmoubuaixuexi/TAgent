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
public class AiEvalCodeVersion {
    private Long id;
    private String evalRunId;
    private String repositoryName;
    private String branchName;
    private String capturedHeadSha;
    private String capturedTagsJson;
    private Boolean dirty;
    private String sourceTreeHash;
    private String hashAlgorithm;
    private String scopeVersion;
    private String ignoreRulesJson;
    private String snapshotJson;
    private String bindingStatus;
    private String boundTag;
    private String boundCommitSha;
    private String matchedTagsJson;
    private String bindingMethod;
    private String bindingNote;
    private LocalDateTime boundAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
