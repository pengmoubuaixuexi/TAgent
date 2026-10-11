package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.event.RunPreparationProgress;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AgentPreparationDispatchTest {
    @Test
    public void returnsBeforeAssemblyAndDuplicatePostCannotQueueSecondWorker() throws Exception {
        Fixture f = new Fixture();
        f.service.dispatch(f.command, new ResponseBodyEmitter());
        verifyNoInteractions(f.armory, f.strategy);
        assertEquals(f.run, f.service.activeRunId("session"));
        f.service.dispatch(f.command, new ResponseBodyEmitter());
        verify(f.executor).execute(any());
        doAnswer(call -> {
            assertEquals("alice", MDC.get("userId"));
            assertEquals(f.run, MDC.get("runId"));
            return null;
        }).when(f.armory).ensureArmed("agent");
        MDC.put("userId", "previous-worker");
        try {
            f.worker.get().run();
            assertEquals("previous-worker", MDC.get("userId"));
        } finally { MDC.clear(); }
        verify(f.strategy).execute(eq(f.command), any());
        assertNull(f.service.activeRunId("session"));
        verify(f.publisher).finishRun(f.run);
        verify(f.progress).ready(f.run);
    }

    @Test
    public void cancellationWhileQueuedPreventsAssemblyAndStrategy() throws Exception {
        Fixture f = new Fixture();
        f.service.dispatch(f.command, new ResponseBodyEmitter());
        assertTrue(f.service.cancelExecute("session", f.run));
        f.worker.get().run();
        verifyNoInteractions(f.armory);
        verify(f.strategy, never()).execute(any(), any());
        verify(f.snapshots).markStatus(eq(f.run), eq("CANCELLED"), any());
        verify(f.history).start(f.command);
        verify(f.history).status(eq(f.run), eq("CANCELLED"), any());
        assertNull(f.service.activeRunId("session"));
    }

    @Test
    public void cancellationDuringAssemblyDoesNotStartStrategy() throws Exception {
        Fixture f = new Fixture();
        f.service.dispatch(f.command, new ResponseBodyEmitter());
        doAnswer(call -> { f.service.cancelExecute("session", f.run); return null; })
                .when(f.armory).ensureArmed("agent");
        f.worker.get().run();
        verify(f.strategy, never()).execute(any(), any());
        verify(f.progress, never()).ready(f.run);
        verify(f.publisher).finishRun(f.run);
    }

    @Test
    public void assemblyFailurePublishesErrorBeforeClosingAndReleasesRun() throws Exception {
        Fixture f = new Fixture();
        f.service.dispatch(f.command, new ResponseBodyEmitter());
        doThrow(new IllegalStateException("assembly failed")).when(f.armory).ensureArmed("agent");
        f.worker.get().run();
        var order = inOrder(f.publisher);
        order.verify(f.publisher).registerRun(f.run, "session");
        order.verify(f.publisher).publish(eq(f.run), eq("session"), eq("message"), any());
        order.verify(f.publisher).finishRun(f.run);
        verify(f.strategy, never()).execute(any(), any());
        assertNull(f.service.activeRunId("session"));
    }

    @Test
    public void queueRejectionEmitsErrorAndReleasesAdmission() throws Exception {
        Fixture f = new Fixture();
        doThrow(new RejectedExecutionException("busy")).when(f.executor).execute(any());
        f.service.dispatch(f.command, new ResponseBodyEmitter());
        verify(f.publisher).publish(eq(f.run), eq("session"), eq("message"), any());
        verify(f.publisher).finishRun(f.run);
        verify(f.progress).close(f.run);
        verify(f.history).start(f.command);
        verify(f.history).status(eq(f.run), eq("FAILED"), any());
        verifyNoInteractions(f.armory, f.strategy);
        assertNull(f.service.activeRunId("session"));
    }

    private static final class Fixture {
        final String run = UUID.randomUUID().toString();
        final AgentDispatchDispatchService service = new AgentDispatchDispatchService();
        final ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        final AtomicReference<Runnable> worker = new AtomicReference<>();
        final ArmoryService armory = mock(ArmoryService.class);
        final IExecuteStrategy strategy = mock(IExecuteStrategy.class);
        final RunEventPublisher publisher = mock(RunEventPublisher.class);
        final RunPreparationProgress progress = mock(RunPreparationProgress.class);
        final RunSnapshotService snapshots = mock(RunSnapshotService.class);
        final cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository history =
                mock(cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository.class);
        final ExecuteCommandEntity command = ExecuteCommandEntity.builder().runId(run).sessionId("session")
                .userId("alice").tenantId("tenant").aiAgentId("agent").message("test").build();
        Fixture() {
            IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
            when(access.ownsAgent("alice", "agent")).thenReturn(true);
            IAgentRepository repository = mock(IAgentRepository.class);
            when(repository.queryAiAgentByAgentId("agent")).thenReturn(AiAgentVO.builder()
                    .agentId("agent").agentName("旅行助手").strategy("flow").build());
            when(snapshots.find(anyString())).thenReturn(Optional.empty());
            doAnswer(call -> { worker.set(call.getArgument(0)); return null; }).when(executor).execute(any());
            ReflectionTestUtils.setField(service, "workspaceAccess", access);
            ReflectionTestUtils.setField(service, "repository", repository);
            ReflectionTestUtils.setField(service, "threadPoolExecutor", executor);
            ReflectionTestUtils.setField(service, "armoryService", armory);
            ReflectionTestUtils.setField(service, "executeStrategyMap", Map.of("flow", strategy));
            ReflectionTestUtils.setField(service, "runEventPublisher", publisher);
            ReflectionTestUtils.setField(service, "preparationProgress", progress);
            ReflectionTestUtils.setField(service, "runSnapshotService", snapshots);
            ReflectionTestUtils.setField(service, "runHistory", history);
            ReflectionTestUtils.setField(service, "runBudgets", new RunBudgetRegistry());
        }
    }
}
