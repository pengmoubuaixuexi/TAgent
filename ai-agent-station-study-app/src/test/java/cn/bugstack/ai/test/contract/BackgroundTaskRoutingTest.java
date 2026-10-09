package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.router.RouteDecision;
import cn.bugstack.ai.domain.agent.service.router.UnifiedAgentRouter;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiBackgroundTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTask;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTaskExecution;
import cn.bugstack.ai.trigger.background.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BackgroundTaskRoutingTest {
    private final IAiBackgroundTaskDao dao = mock(IAiBackgroundTaskDao.class);
    private final IAiAgentDao agents = mock(IAiAgentDao.class);
    private final BackgroundTaskCommandRouter parser = mock(BackgroundTaskCommandRouter.class);
    private final RunSnapshotService snapshots = mock(RunSnapshotService.class);
    private final BackgroundTaskService service = new BackgroundTaskService(dao, parser, agents, Optional.of(snapshots));

    @Test
    public void newDraftIgnoresChatSelectionAndParserGeneratedAgent() {
        when(parser.route(anyString(), anyString())).thenReturn(BackgroundTaskCommand.builder()
                .matched(true).operation("CREATE")
                .taskDraft(BackgroundTaskCommand.TaskDraft.builder()
                        .name("News").taskType("CRON")
                        .trigger(new java.util.LinkedHashMap<>(Map.of("cron_expression", "0 0 9 * * *")))
                        .actionPrompt("Search AI news and summarize").actionAgentId("parser-agent")
                        .build()).build());
        service.interpret("Every morning", "session", "user", "default", "chat-agent", 5);
        ArgumentCaptor<AiBackgroundTask> task = ArgumentCaptor.forClass(AiBackgroundTask.class);
        verify(dao).insertTask(task.capture());
        assertNull(task.getValue().getActionAgentId());
        assertEquals("DRAFT", task.getValue().getStatus());
    }

    @Test
    public void legacyTaskCanSwitchToAutomaticAndOmittedChoiceIsPreserved() {
        AiBackgroundTask task = task("legacy-agent");
        when(dao.findOwned("task", "user")).thenReturn(task);
        when(agents.queryByAgentId("legacy-agent")).thenReturn(AiAgent.builder().status(1).build());
        when(dao.updateDraftOwned(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        service.edit("task", "user", null, null, null, null, null, null);
        verify(dao).updateDraftOwned(eq("task"), eq("user"), any(), any(), any(), eq("legacy-agent"), any(), any(), any(), any());
        service.edit("task", "user", null, null, null, "", null, null);
        verify(dao).updateDraftOwned(eq("task"), eq("user"), any(), any(), any(), isNull(), any(), any(), any(), any());
    }

    @Test
    public void explicitChoiceMustBeAnEnabledAgent() {
        when(dao.findOwned("task", "user")).thenReturn(task(null));
        when(agents.queryByAgentId("chosen")).thenReturn(AiAgent.builder().status(1).build());
        when(dao.updateDraftOwned(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        service.edit("task", "user", null, null, null, "chosen", null, null);
        verify(dao).updateDraftOwned(eq("task"), eq("user"), any(), any(), any(), eq("chosen"), any(), any(), any(), any());
        when(agents.queryByAgentId("disabled")).thenReturn(AiAgent.builder().status(0).build());
        for (String id : List.of("disabled", "missing")) {
            try {
                service.edit("task", "user", null, null, null, id, null, null);
                fail("Invalid agent must not be saved");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Agent"));
            }
        }
    }

    @Test
    public void eachAutomaticTriggerRoutesActionAndDoesNotPinTheTask() throws Exception {
        runThroughRealDispatcher(null);
    }

    @Test
    public void explicitTaskUsesItsAgentWithoutCallingIntentRouter() throws Exception {
        runThroughRealDispatcher("chosen");
    }

    private void runThroughRealDispatcher(String selected) throws Exception {
        AiBackgroundTask task = task(selected);
        AgentDispatchDispatchService dispatch = new AgentDispatchDispatchService();
        var access = mock(cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository.class);
        when(access.ownsAgent(eq("user"), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "workspaceAccess", access);
        UnifiedAgentRouter router = mock(UnifiedAgentRouter.class);
        IAgentRepository repository = mock(IAgentRepository.class);
        IExecuteStrategy strategy = mock(IExecuteStrategy.class);
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ReflectionTestUtils.setField(dispatch, "unifiedAgentRouter", router);
        ReflectionTestUtils.setField(dispatch, "repository", repository);
        ReflectionTestUtils.setField(dispatch, "executeStrategyMap", Map.of("flow", strategy));
        ReflectionTestUtils.setField(dispatch, "threadPoolExecutor", executor);
        ReflectionTestUtils.setField(dispatch, "runEventPublisher", events);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any());
        when(router.routeDecision(task.getActionPrompt()))
                .thenReturn(new RouteDecision("routed-1", List.of(), 0.95, ""))
                .thenReturn(new RouteDecision("routed-2", List.of(), 0.96, ""));
        when(repository.queryAiAgentByAgentId(anyString())).thenAnswer(call -> AiAgentVO.builder()
                .agentId(call.getArgument(0)).agentName("Test agent").strategy("flow").status(1).build());
        when(dao.findRunnable(anyInt())).thenReturn(List.of(task));
        when(dao.markTriggered(eq("task"), any(), any(), any(), any())).thenReturn(1);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(dao, service, dispatch, events);
        scheduler.scan();
        scheduler.scan();
        ArgumentCaptor<ExecuteCommandEntity> commands = ArgumentCaptor.forClass(ExecuteCommandEntity.class);
        verify(strategy, times(2)).execute(commands.capture(), any());
        assertEquals(task.getActionPrompt(), commands.getAllValues().get(0).getMessage());
        assertEquals(selected == null ? "routed-1" : selected, commands.getAllValues().get(0).getAiAgentId());
        assertEquals(selected == null ? "routed-2" : selected, commands.getAllValues().get(1).getAiAgentId());
        assertEquals(selected, task.getActionAgentId());
        verify(router, times(selected == null ? 2 : 0)).routeDecision(anyString());
    }

    @Test
    public void historyShowsActualRunChoiceRatherThanCurrentTaskChoice() {
        when(dao.findOwned("task", "user")).thenReturn(task("changed-choice"));
        when(dao.listExecutions("task", 30)).thenReturn(List.of(AiBackgroundTaskExecution.builder()
                .taskId("task").runId("run").build()));
        when(snapshots.find("run")).thenReturn(Optional.of(RunSnapshot.builder()
                .agentId("actual-agent").agentName("Actual").agentType("flow").build()));
        var history = service.history("task", "user", 30).get(0);
        assertEquals("actual-agent", history.get("resolvedAgentId"));
        assertEquals("flow", history.get("resolvedStrategy"));
        when(snapshots.find("run")).thenReturn(Optional.empty());
        assertFalse(service.history("task", "user", 30).get(0).containsKey("resolvedAgentId"));
    }

    @Test
    public void historyStillWorksWhenRunSnapshotsAreDisabled() {
        var withoutSnapshots = new BackgroundTaskService(dao, parser, agents, Optional.empty());
        when(dao.findOwned("task", "user")).thenReturn(task(null));
        when(dao.listExecutions("task", 30)).thenReturn(List.of(AiBackgroundTaskExecution.builder()
                .taskId("task").runId("run").build()));
        assertEquals("run", withoutSnapshots.history("task", "user", 30).get(0).get("runId"));
    }

    private AiBackgroundTask task(String agentId) {
        return AiBackgroundTask.builder().taskId("task").userId("user").tenantId("default")
                .sessionId("session").name("Every morning create a task").taskType("CRON").status("ACTIVE")
                .triggerConfigJson("{\"cron_expression\":\"0 0 9 * * *\"}")
                .nextTriggerAt(LocalDateTime.now().minusSeconds(2)).runOnce(false).maxStep(5)
                .actionPrompt("Search AI news and summarize").actionAgentId(agentId).build();
    }
}
