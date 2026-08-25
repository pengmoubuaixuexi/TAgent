package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCaseResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCodeVersion;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDatasetVersion;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeJob;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IAiEvalOpsDao {

    int insertDataset(AiEvalDataset dataset);

    AiEvalDataset findDataset(@Param("datasetId") String datasetId);

    List<AiEvalDataset> listDatasets(@Param("ownerUserId") String ownerUserId,
                                     @Param("limit") int limit);

    int updateDataset(@Param("datasetId") String datasetId,
                      @Param("name") String name,
                      @Param("description") String description,
                      @Param("executionMode") String executionMode);

    int updateDatasetLatestVersion(@Param("datasetId") String datasetId,
                                   @Param("latestVersionNo") int latestVersionNo);

    int countActiveRunsByDataset(@Param("datasetId") String datasetId);

    int deleteRunsByDataset(@Param("datasetId") String datasetId);

    int deleteDataset(@Param("datasetId") String datasetId);

    int insertVersion(AiEvalDatasetVersion version);

    AiEvalDatasetVersion findVersion(@Param("versionId") String versionId);

    AiEvalDatasetVersion findDraftVersion(@Param("datasetId") String datasetId);

    List<AiEvalDatasetVersion> listVersions(@Param("datasetId") String datasetId);

    int publishVersion(@Param("versionId") String versionId,
                       @Param("caseCount") int caseCount,
                       @Param("checksum") String checksum,
                       @Param("publishedAt") LocalDateTime publishedAt);

    int insertCase(AiEvalCase evalCase);

    int updateCase(AiEvalCase evalCase);

    int deleteCase(@Param("caseId") String caseId,
                   @Param("versionId") String versionId);

    AiEvalCase findCase(@Param("caseId") String caseId);

    List<AiEvalCase> listCases(@Param("versionId") String versionId,
                               @Param("enabledOnly") boolean enabledOnly);

    int countCases(@Param("versionId") String versionId,
                   @Param("enabledOnly") boolean enabledOnly);

    int insertRun(AiEvalRun run);

    AiEvalRun findRun(@Param("evalRunId") String evalRunId);

    List<AiEvalRun> listRuns(@Param("datasetId") String datasetId,
                             @Param("limit") int limit);

    int markRunStarted(@Param("evalRunId") String evalRunId,
                       @Param("startedAt") LocalDateTime startedAt);

    int refreshRunProgress(@Param("evalRunId") String evalRunId);

    int finishRuleRun(@Param("evalRunId") String evalRunId,
                      @Param("status") String status,
                      @Param("finishedAt") LocalDateTime finishedAt,
                      @Param("errorMessage") String errorMessage);

    int updateRunStatus(@Param("evalRunId") String evalRunId,
                        @Param("status") String status,
                        @Param("errorMessage") String errorMessage);

    int restoreRuleCompletedForJudgeRetry(@Param("evalRunId") String evalRunId);

    int updateRunJudgeScore(@Param("evalRunId") String evalRunId,
                            @Param("judgeScore") BigDecimal judgeScore,
                            @Param("status") String status);

    int cancelPendingCaseResults(@Param("evalRunId") String evalRunId,
                                 @Param("finishedAt") LocalDateTime finishedAt,
                                 @Param("errorMessage") String errorMessage);

    int deleteRun(@Param("evalRunId") String evalRunId);

    int insertCodeVersion(AiEvalCodeVersion codeVersion);

    AiEvalCodeVersion findCodeVersion(@Param("evalRunId") String evalRunId);

    List<AiEvalCodeVersion> listPendingCodeVersions(@Param("limit") int limit);

    int updateCodeVersionBinding(@Param("evalRunId") String evalRunId,
                                 @Param("bindingStatus") String bindingStatus,
                                 @Param("boundTag") String boundTag,
                                 @Param("boundCommitSha") String boundCommitSha,
                                 @Param("matchedTagsJson") String matchedTagsJson,
                                 @Param("bindingMethod") String bindingMethod,
                                 @Param("bindingNote") String bindingNote,
                                 @Param("boundAt") LocalDateTime boundAt,
                                 @Param("lastCheckedAt") LocalDateTime lastCheckedAt);

    int touchCodeVersionCheck(@Param("evalRunId") String evalRunId,
                              @Param("lastCheckedAt") LocalDateTime lastCheckedAt);

    int insertCaseResult(AiEvalCaseResult result);

    AiEvalCaseResult findCaseResult(@Param("resultId") String resultId);

    List<AiEvalCaseResult> listCaseResults(@Param("evalRunId") String evalRunId);

    int markCaseResultRunning(@Param("resultId") String resultId,
                              @Param("agentRunId") String agentRunId,
                              @Param("sessionId") String sessionId,
                              @Param("startedAt") LocalDateTime startedAt);

    int finishCaseResult(AiEvalCaseResult result);

    int insertJudgeJob(AiEvalJudgeJob job);

    AiEvalJudgeJob findJudgeJob(@Param("judgeJobId") String judgeJobId);

    List<AiEvalJudgeJob> listJudgeJobs(@Param("evalRunId") String evalRunId);

    int markJudgeJobStarted(@Param("judgeJobId") String judgeJobId,
                            @Param("totalCases") int totalCases,
                            @Param("startedAt") LocalDateTime startedAt);

    int finishJudgeJob(@Param("judgeJobId") String judgeJobId,
                       @Param("status") String status,
                       @Param("completedCases") int completedCases,
                       @Param("failedCases") int failedCases,
                       @Param("averageScore") BigDecimal averageScore,
                       @Param("finishedAt") LocalDateTime finishedAt,
                       @Param("errorMessage") String errorMessage);

    int cancelJudgeJobsByRun(@Param("evalRunId") String evalRunId,
                             @Param("finishedAt") LocalDateTime finishedAt,
                             @Param("errorMessage") String errorMessage);

    int insertJudgeResult(AiEvalJudgeResult result);

    List<AiEvalJudgeResult> listJudgeResults(@Param("judgeJobId") String judgeJobId);
}
