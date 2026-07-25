package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.dispatch.RunDispatchConflictException;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.infrastructure.dao.IAiBackgroundTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTask;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTaskExecution;
import cn.bugstack.ai.trigger.background.BackgroundTaskCommand;
import cn.bugstack.ai.trigger.background.BackgroundTaskCommandRouter;
import cn.bugstack.ai.trigger.background.BackgroundTaskScheduler;
import cn.bugstack.ai.trigger.background.BackgroundTaskService;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BackgroundTaskLifecycleTest {

    @Test
    public void executeCommandDefaultsImagesToEmptyList() {
        ExecuteCommandEntity command = ExecuteCommandEntity.builder().build();
        assertNotNull(command.getImages());
        assertEquals(0, command.getImages().size());

        ExecuteCommandEntity noArgsCommand = new ExecuteCommandEntity();
        assertNotNull(noArgsCommand.getImages());
        assertEquals(0, noArgsCommand.getImages().size());
    }

    @Test
    public void createCommandOnlyPersistsDraftUntilExplicitConfirmation() {
        IAiBackgroundTaskDao dao = mock(IAiBackgroundTaskDao.class);
        BackgroundTaskCommandRouter router = mock(BackgroundTaskCommandRouter.class);
        BackgroundTaskService service = new BackgroundTaskService(dao, router);
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("path", "v6.md");
        trigger.put("quiet_seconds", 120);
        when(router.route(any(), any())).thenReturn(BackgroundTaskCommand.builder()
                .matched(true)
                .confidence(0.95)
                .operation("CREATE")
                .taskDraft(BackgroundTaskCommand.TaskDraft.builder()
                        .taskType("FILE_CHANGE_STABLE")
                        .name("Watch v6")
                        .trigger(trigger)
                        .actionPrompt("Read and review the latest reply")
                        .runOnce(true)
                        .maxStep(5)
                        .build())
                .build());

        Map<String, Object> result = service.interpret(
                "watch v6", "session-test", "user-test", "default", null, 5);

        ArgumentCaptor<AiBackgroundTask> captor = ArgumentCaptor.forClass(AiBackgroundTask.class);
        verify(dao).insertTask(captor.capture());
        assertEquals("DRAFT", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getDraftExpiresAt());
        assertEquals("DRAFT", ((Map<?, ?>) result.get("task")).get("status"));
    }

    @Test
    public void dueFileTriggerWaitsWhenOriginalSessionAlreadyHasRun() throws Exception {
        IAiBackgroundTaskDao dao = mock(IAiBackgroundTaskDao.class);
        BackgroundTaskCommandRouter router = mock(BackgroundTaskCommandRouter.class);
        BackgroundTaskService service = new BackgroundTaskService(dao, router);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(dao, service, dispatch, publisher);

        Path file = Files.createTempFile("tagent-background-task-", ".md");
        file.toFile().deleteOnExit();
        Files.writeString(file, "background task test");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minusSeconds(10)));
        String currentHash = sha256(file);
        AiBackgroundTask task = AiBackgroundTask.builder()
                .taskId("task-test")
                .userId("user-test")
                .tenantId("default")
                .sessionId("session-test")
                .name("Watch v6")
                .taskType("FILE_CHANGE_STABLE")
                .status("ACTIVE")
                .triggerConfigJson(JSON.toJSONString(Map.of("path", file.toString(), "quiet_seconds", 5)))
                .actionPrompt("review")
                .runOnce(true)
                .baselineHash("different-baseline")
                .lastObservedHash(currentHash)
                .observedChangedAt(LocalDateTime.now().minusSeconds(10))
                .build();
        when(dao.findInFlightExecutions(anyInt())).thenReturn(List.of());
        when(dao.findRunnable(anyInt())).thenReturn(List.of(task));
        when(dispatch.isSessionBusy("session-test")).thenReturn(true);

        scheduler.scan();

        verify(dao).updateStatus(eq("task-test"), eq("WAITING_SESSION"), any());
    }

    @Test
    public void dispatchBusyRaceDefersReservedTaskInsteadOfFailingIt() throws Exception {
        IAiBackgroundTaskDao dao = mock(IAiBackgroundTaskDao.class);
        BackgroundTaskCommandRouter router = mock(BackgroundTaskCommandRouter.class);
        BackgroundTaskService service = new BackgroundTaskService(dao, router);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(dao, service, dispatch, publisher);

        LocalDateTime due = LocalDateTime.now().minusSeconds(5);
        AiBackgroundTask task = AiBackgroundTask.builder()
                .taskId("task-race")
                .userId("user-test")
                .tenantId("default")
                .sessionId("session-race")
                .name("Once")
                .taskType("SCHEDULE_ONCE")
                .status("ACTIVE")
                .triggerConfigJson(JSON.toJSONString(Map.of("run_at", due.toString())))
                .actionPrompt("run")
                .runOnce(true)
                .nextTriggerAt(due)
                .build();
        when(dao.findInFlightExecutions(anyInt())).thenReturn(List.of());
        when(dao.findRunnable(anyInt())).thenReturn(List.of(task));
        when(dispatch.isSessionBusy("session-race")).thenReturn(false);
        when(dao.markTriggered(eq("task-race"), eq("RUNNING"), any(), any(), any()))
                .thenReturn(1);
        org.mockito.Mockito.doThrow(RunDispatchConflictException.sessionBusy(
                        "session-race", "background-run", "foreground-run"))
                .when(dispatch).dispatch(any(), any());

        scheduler.scan();

        ArgumentCaptor<AiBackgroundTaskExecution> execution =
                ArgumentCaptor.forClass(AiBackgroundTaskExecution.class);
        verify(dao).insertExecution(execution.capture());
        assertEquals("STARTING", execution.getValue().getStatus());
        verify(dao).updateExecution(eq(execution.getValue().getRunId()), eq("SKIPPED"), any(), any());
        verify(dao).deferTriggered(
                eq("task-race"), eq(execution.getValue().getRunId()), eq(due), any());
        verify(dao, never()).updateStatus(eq("task-race"), eq("FAILED"), any());
    }

    @Test
    public void startingExecutionIsNotCompletedInsideStartupGraceWindow() {
        IAiBackgroundTaskDao dao = mock(IAiBackgroundTaskDao.class);
        BackgroundTaskCommandRouter router = mock(BackgroundTaskCommandRouter.class);
        BackgroundTaskService service = new BackgroundTaskService(dao, router);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(dao, service, dispatch, publisher);

        AiBackgroundTask task = AiBackgroundTask.builder()
                .taskId("task-starting")
                .sessionId("session-starting")
                .status("RUNNING")
                .build();
        AiBackgroundTaskExecution execution = AiBackgroundTaskExecution.builder()
                .taskId("task-starting")
                .runId("run-starting")
                .status("STARTING")
                .startedAt(LocalDateTime.now())
                .build();
        when(dao.findInFlightExecutions(anyInt())).thenReturn(List.of(execution));
        when(dao.findByTaskId("task-starting")).thenReturn(task);
        when(dao.findRunnable(anyInt())).thenReturn(List.of());

        scheduler.scan();

        verify(dao, never()).updateExecution(eq("run-starting"), any(), any(), any());
    }

    @Test
    public void successfulDispatchPromotesStartingExecutionToRunning() throws Exception {
        IAiBackgroundTaskDao dao = mock(IAiBackgroundTaskDao.class);
        BackgroundTaskCommandRouter router = mock(BackgroundTaskCommandRouter.class);
        BackgroundTaskService service = new BackgroundTaskService(dao, router);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(dao, service, dispatch, publisher);

        LocalDateTime due = LocalDateTime.now().minusSeconds(5);
        AiBackgroundTask task = AiBackgroundTask.builder()
                .taskId("task-start")
                .userId("user-test")
                .tenantId("default")
                .sessionId("session-start")
                .name("Once")
                .taskType("SCHEDULE_ONCE")
                .status("ACTIVE")
                .triggerConfigJson(JSON.toJSONString(Map.of("run_at", due.toString())))
                .actionPrompt("run")
                .runOnce(true)
                .nextTriggerAt(due)
                .build();
        when(dao.findInFlightExecutions(anyInt())).thenReturn(List.of());
        when(dao.findRunnable(anyInt())).thenReturn(List.of(task));
        when(dispatch.isSessionBusy("session-start")).thenReturn(false);
        when(dao.markTriggered(eq("task-start"), eq("RUNNING"), any(), any(), any()))
                .thenReturn(1);

        scheduler.scan();

        ArgumentCaptor<AiBackgroundTaskExecution> execution =
                ArgumentCaptor.forClass(AiBackgroundTaskExecution.class);
        verify(dao).insertExecution(execution.capture());
        assertEquals("STARTING", execution.getValue().getStatus());
        ArgumentCaptor<ExecuteCommandEntity> command =
                ArgumentCaptor.forClass(ExecuteCommandEntity.class);
        verify(dispatch).dispatch(command.capture(), any());
        assertNotNull(command.getValue().getImages());
        assertEquals(0, command.getValue().getImages().size());
        verify(dao).markExecutionRunning(execution.getValue().getRunId());
    }

    private static String sha256(Path path) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(java.nio.file.Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
