package cn.bugstack.ai.trigger.background;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.dispatch.RunDispatchConflictException;
import cn.bugstack.ai.domain.agent.service.execute.event.RunAwareResponseBodyEmitter;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.infrastructure.dao.IAiBackgroundTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTask;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundTaskScheduler {

    private final IAiBackgroundTaskDao dao;
    private final BackgroundTaskService taskService;
    private final IAgentDispatchService dispatchService;
    private final RunEventPublisher runEventPublisher;
    private final ConcurrentHashMap<String, Boolean> evaluating = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private RunSnapshotService runSnapshotService;

    @org.springframework.beans.factory.annotation.Value(
            "${agent.background-task.starting-grace-seconds:30}")
    private long startingGraceSeconds;

    @Scheduled(fixedDelayString = "${agent.background-task.scan-interval-ms:5000}")
    public void scan() {
        try {
            completeFinishedExecutions();
            for (AiBackgroundTask task : dao.findRunnable(200)) {
                if (evaluating.putIfAbsent(task.getTaskId(), Boolean.TRUE) != null) continue;
                try {
                    if (!"RUNNING".equals(task.getStatus())) evaluate(task);
                } catch (Exception e) {
                    log.warn("[BackgroundTask] evaluate failed taskId={}: {}", task.getTaskId(), e.getMessage());
                    dao.updateStatus(task.getTaskId(), "FAILED", abbreviate(e.getMessage(), 1000));
                } finally {
                    evaluating.remove(task.getTaskId());
                }
            }
        } catch (Exception e) {
            log.error("[BackgroundTask] scheduler scan failed", e);
        }
    }

    public Map<String, Object> runNow(String taskId, String userId) {
        AiBackgroundTask task = taskService.requireOwned(taskId, userId);
        if (!java.util.List.of("ACTIVE", "PAUSED", "WAITING_SESSION", "FAILED").contains(task.getStatus())) {
            throw new IllegalArgumentException("当前任务状态不能立即运行：" + task.getStatus());
        }
        if (dispatchService.isSessionBusy(task.getSessionId())) {
            throw new IllegalArgumentException("当前会话已有 run，请等待结束后再点立即运行");
        }
        if (!java.util.List.of("ACTIVE", "WAITING_SESSION").contains(task.getStatus())) {
            dao.updateStatus(taskId, "ACTIVE", null);
            task = dao.findByTaskId(taskId);
        }
        trigger(task, "manual run-now");
        return taskService.toView(dao.findByTaskId(taskId));
    }

    private void evaluate(AiBackgroundTask task) {
        switch (task.getTaskType()) {
            case "FILE_CHANGE_STABLE" -> evaluateFile(task);
            case "SCHEDULE_ONCE", "CRON" -> evaluateTime(task);
            default -> dao.updateStatus(task.getTaskId(), "FAILED", "Unsupported task type: " + task.getTaskType());
        }
    }

    private void evaluateFile(AiBackgroundTask task) {
        Map<String, Object> trigger = taskService.trigger(task);
        Path path = Path.of(String.valueOf(trigger.get("path")));
        if (!Files.isRegularFile(path)) {
            dao.updateStatus(task.getTaskId(), "FAILED", "监视文件不存在：" + path);
            return;
        }
        String currentHash = BackgroundTaskService.sha256(path);
        LocalDateTime now = LocalDateTime.now();
        if (currentHash.equals(task.getBaselineHash())) {
            if (task.getLastObservedHash() != null || task.getObservedChangedAt() != null) {
                dao.updateObservation(task.getTaskId(), null, null, now, null);
            }
            return;
        }
        if (!currentHash.equals(task.getLastObservedHash())) {
            dao.updateObservation(task.getTaskId(), currentHash, now, now, null);
            return;
        }
        int quietSeconds = intValue(trigger.get("quiet_seconds"), 120);
        LocalDateTime lastWrite = LocalDateTime.ofInstant(
                fileLastModified(path), ZoneId.systemDefault());
        LocalDateTime stableSince = task.getObservedChangedAt() == null
                ? now : task.getObservedChangedAt();
        if (lastWrite.isAfter(stableSince)) stableSince = lastWrite;
        if (stableSince.plusSeconds(quietSeconds).isAfter(now)) {
            dao.updateObservation(task.getTaskId(), currentHash, task.getObservedChangedAt(), now, null);
            return;
        }
        if (dispatchService.isSessionBusy(task.getSessionId())) {
            dao.updateStatus(task.getTaskId(), "WAITING_SESSION", "会话中已有 run，等待空闲后触发");
            return;
        }
        trigger(task, "file changed and stable for " + quietSeconds + "s");
    }

    private void evaluateTime(AiBackgroundTask task) {
        LocalDateTime due = task.getNextTriggerAt();
        if (due == null || due.isAfter(LocalDateTime.now())) return;
        if (dispatchService.isSessionBusy(task.getSessionId())) {
            dao.updateStatus(task.getTaskId(), "WAITING_SESSION", "会话中已有 run，等待空闲后触发");
            return;
        }
        trigger(task, "scheduled trigger at " + due);
    }

    private void trigger(AiBackgroundTask task, String reason) {
        String runId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = null;
        if ("CRON".equals(task.getTaskType())) {
            Map<String, Object> trigger = taskService.trigger(task);
            ZonedDateTime after = ZonedDateTime.now(BackgroundTaskService.zone(trigger));
            next = BackgroundTaskService.schedulerLocal(BackgroundTaskService.nextCron(trigger, after));
        }
        int reserved = dao.markTriggered(task.getTaskId(), "RUNNING", runId, now, next);
        if (reserved != 1) return;

        AiBackgroundTaskExecution execution = AiBackgroundTaskExecution.builder()
                .executionId(UUID.randomUUID().toString())
                .taskId(task.getTaskId())
                .runId(runId)
                .triggerReason(reason)
                .status("STARTING")
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        dao.insertExecution(execution);
        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .aiAgentId(task.getActionAgentId())
                .message("[后台任务触发：" + task.getName() + "]\n" + task.getActionPrompt())
                .images(java.util.List.of())
                .sessionId(task.getSessionId())
                .runId(runId)
                .userId(task.getUserId())
                .tenantId(task.getTenantId())
                .maxStep(task.getMaxStep())
                .planReviewEnabled(false)
                .build();
        RunAwareResponseBodyEmitter emitter =
                new RunAwareResponseBodyEmitter(Long.MAX_VALUE, runId, task.getSessionId(), runEventPublisher);
        try {
            dispatchService.dispatch(command, emitter);
            dao.markExecutionRunning(runId);
            log.info("[BackgroundTask] triggered taskId={} runId={} sessionId={}",
                    task.getTaskId(), runId, task.getSessionId());
        } catch (RunDispatchConflictException e) {
            if (RunDispatchConflictException.Reason.SESSION_BUSY.equals(e.getReason())) {
                String message = abbreviate(e.getMessage(), 1000);
                dao.updateExecution(runId, "SKIPPED", LocalDateTime.now(), message);
                // markTriggered may already have advanced a CRON schedule.
                // Restore the original due time so WAITING_SESSION retries this
                // occurrence as soon as the session becomes free.
                dao.deferTriggered(task.getTaskId(), runId, task.getNextTriggerAt(), message);
                log.info("[BackgroundTask] deferred busy session taskId={} runId={} sessionId={}",
                        task.getTaskId(), runId, task.getSessionId());
                return;
            }
            dao.updateExecution(runId, "FAILED", LocalDateTime.now(), abbreviate(e.getMessage(), 1000));
            dao.updateStatus(task.getTaskId(), "FAILED", abbreviate(e.getMessage(), 1000));
            throw e;
        } catch (Exception e) {
            dao.updateExecution(runId, "FAILED", LocalDateTime.now(), abbreviate(e.getMessage(), 1000));
            dao.updateStatus(task.getTaskId(), "FAILED", abbreviate(e.getMessage(), 1000));
            throw new IllegalStateException("后台任务触发 run 失败：" + e.getMessage(), e);
        }
    }

    private void completeFinishedExecutions() {
        LocalDateTime now = LocalDateTime.now();
        for (AiBackgroundTaskExecution execution : dao.findInFlightExecutions(200)) {
            String runId = execution.getRunId();
            AiBackgroundTask task = dao.findByTaskId(execution.getTaskId());
            if (task == null) {
                dao.updateExecution(runId, "FAILED", LocalDateTime.now(), "Task no longer exists");
                continue;
            }
            String activeRunId = dispatchService.activeRunId(task.getSessionId());
            if (runId != null && runId.equals(activeRunId)) continue;

            boolean starting = "STARTING".equals(execution.getStatus());
            if (starting && execution.getStartedAt() != null
                    && execution.getStartedAt()
                    .plusSeconds(Math.max(1L, startingGraceSeconds))
                    .isAfter(now)) {
                continue;
            }

            String executionStatus = starting ? "FAILED" : "COMPLETED";
            String error = starting
                    ? "Run dispatch did not become active before the startup grace period elapsed"
                    : null;
            if (runSnapshotService != null && runId != null) {
                Optional<RunSnapshot> snapshot = runSnapshotService.find(runId);
                if (snapshot.isPresent()) {
                    String runStatus = snapshot.get().getStatus();
                    error = snapshot.get().getLastError();
                    if (RunSnapshotService.STATUS_COMPLETED.equals(runStatus)) executionStatus = "COMPLETED";
                    else if (RunSnapshotService.STATUS_FAILED.equals(runStatus)) executionStatus = "FAILED";
                    else if (RunSnapshotService.STATUS_CANCELLED.equals(runStatus)) executionStatus = "CANCELLED";
                    else if (RunSnapshotService.STATUS_RUNNING.equals(runStatus)) {
                        executionStatus = "FAILED";
                        error = "服务重启或执行上下文丢失，原 run 未能继续";
                    }
                }
            }
            dao.updateExecution(runId, executionStatus, LocalDateTime.now(), error);
            finishTask(task, executionStatus, error);
        }
    }

    private void finishTask(AiBackgroundTask task, String executionStatus, String error) {
        if ("CANCELLED".equals(task.getStatus()) || "PAUSED".equals(task.getStatus())) return;
        if (!"COMPLETED".equals(executionStatus)) {
            dao.updateStatus(task.getTaskId(), "FAILED", error);
            return;
        }
        if (Boolean.TRUE.equals(task.getRunOnce())) {
            dao.updateStatus(task.getTaskId(), "COMPLETED", null);
            return;
        }
        if ("FILE_CHANGE_STABLE".equals(task.getTaskType())) {
            Map<String, Object> trigger = taskService.trigger(task);
            Path path = Path.of(String.valueOf(trigger.get("path")));
            String baseline = Files.isRegularFile(path) ? BackgroundTaskService.sha256(path) : task.getLastObservedHash();
            dao.resetRecurringFileTask(task.getTaskId(), baseline);
        } else {
            dao.updateStatus(task.getTaskId(), "ACTIVE", null);
        }
    }

    private static Instant fileLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException("无法读取文件修改时间：" + e.getMessage(), e);
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
