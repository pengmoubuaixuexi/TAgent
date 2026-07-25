package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTask;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTaskExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IAiBackgroundTaskDao {

    int insertTask(AiBackgroundTask task);

    AiBackgroundTask findOwned(@Param("taskId") String taskId, @Param("userId") String userId);

    AiBackgroundTask findByTaskId(@Param("taskId") String taskId);

    AiBackgroundTask findOwnedByReference(@Param("userId") String userId,
                                          @Param("taskReference") String taskReference);

    List<AiBackgroundTask> listOwned(@Param("userId") String userId,
                                     @Param("sessionId") String sessionId,
                                     @Param("limit") int limit);

    List<AiBackgroundTask> findRunnable(@Param("limit") int limit);

    int activateDraft(@Param("taskId") String taskId,
                      @Param("userId") String userId,
                      @Param("baselineHash") String baselineHash,
                      @Param("nextTriggerAt") LocalDateTime nextTriggerAt);

    int updateStatusOwned(@Param("taskId") String taskId,
                          @Param("userId") String userId,
                          @Param("status") String status,
                          @Param("lastError") String lastError);

    int updateStatus(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("lastError") String lastError);

    int updateDraftOwned(@Param("taskId") String taskId,
                         @Param("userId") String userId,
                         @Param("name") String name,
                         @Param("triggerConfigJson") String triggerConfigJson,
                         @Param("actionPrompt") String actionPrompt,
                         @Param("actionAgentId") String actionAgentId,
                         @Param("maxStep") Integer maxStep,
                         @Param("runOnce") Boolean runOnce,
                         @Param("nextTriggerAt") LocalDateTime nextTriggerAt,
                         @Param("draftExpiresAt") LocalDateTime draftExpiresAt);

    int updateObservation(@Param("taskId") String taskId,
                          @Param("lastObservedHash") String lastObservedHash,
                          @Param("observedChangedAt") LocalDateTime observedChangedAt,
                          @Param("lastCheckedAt") LocalDateTime lastCheckedAt,
                          @Param("lastError") String lastError);

    int markTriggered(@Param("taskId") String taskId,
                      @Param("status") String status,
                      @Param("runId") String runId,
                      @Param("lastTriggeredAt") LocalDateTime lastTriggeredAt,
                      @Param("nextTriggerAt") LocalDateTime nextTriggerAt);

    int deferTriggered(@Param("taskId") String taskId,
                       @Param("runId") String runId,
                       @Param("nextTriggerAt") LocalDateTime nextTriggerAt,
                       @Param("lastError") String lastError);

    int resetRecurringFileTask(@Param("taskId") String taskId,
                               @Param("baselineHash") String baselineHash);

    int insertExecution(AiBackgroundTaskExecution execution);

    int updateExecution(@Param("runId") String runId,
                        @Param("status") String status,
                        @Param("finishedAt") LocalDateTime finishedAt,
                        @Param("errorMessage") String errorMessage);

    int markExecutionRunning(@Param("runId") String runId);

    List<AiBackgroundTaskExecution> listExecutions(@Param("taskId") String taskId,
                                                   @Param("limit") int limit);

    List<AiBackgroundTaskExecution> findInFlightExecutions(@Param("limit") int limit);
}
