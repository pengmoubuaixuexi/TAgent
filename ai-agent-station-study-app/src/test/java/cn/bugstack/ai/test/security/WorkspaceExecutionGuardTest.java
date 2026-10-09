package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class WorkspaceExecutionGuardTest {
    @After public void clearMdc() { MDC.clear(); }

    @Test public void updateAcquisitionIsAtomicAndRunLeasesAreCrossThreadAndIdempotent() throws Exception {
        WorkspaceExecutionGuard guard = new WorkspaceExecutionGuard();
        var first = guard.startRun("b");
        var second = guard.startRun("b");
        assertThrows(WorkspaceExecutionGuard.BusyException.class, () -> guard.beginUpdate(Set.of("a", "b")));
        guard.startRun("a").close(); // Failed batch did not reserve a.
        first.close(); first.close();
        assertThrows(WorkspaceExecutionGuard.BusyException.class, () -> guard.beginUpdate(Set.of("b")));
        var worker = Executors.newSingleThreadExecutor();
        try { worker.submit(second::close).get(5, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
        try (var update = guard.beginUpdate(Set.of("a", "b"))) {
            assertThrows(WorkspaceExecutionGuard.BusyException.class, () -> guard.startRun("a"));
            assertThrows(WorkspaceExecutionGuard.BusyException.class, () -> guard.startRun("b"));
        }
        assertTrue(((Map<?,?>) ReflectionTestUtils.getField(guard, "activeRuns")).isEmpty());
        assertTrue(((Set<?>) ReflectionTestUtils.getField(guard, "editing")).isEmpty());
    }

    @Test public void dispatchHoldsRunUntilAsynchronousFailureFinishes() throws Exception {
        WorkspaceExecutionGuard guard = new WorkspaceExecutionGuard();
        ThreadPoolExecutor pool = mock(ThreadPoolExecutor.class);
        IExecuteStrategy strategy = mock(IExecuteStrategy.class);
        doThrow(new IllegalStateException("fixture failure")).when(strategy).execute(any(), any());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        doAnswer(call -> { queued.set(call.getArgument(0)); return null; }).when(pool).execute(any());
        var dispatcher = dispatcher(guard, pool, strategy);
        dispatcher.dispatch(command(), new ResponseBodyEmitter());
        assertThrows(WorkspaceExecutionGuard.BusyException.class, () -> guard.beginUpdate(Set.of("agent-a")));
        var worker = Executors.newSingleThreadExecutor();
        try { worker.submit(queued.get()).get(5, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
        assertNull(dispatcher.activeRunId("session-a"));
        guard.beginUpdate(Set.of("agent-a")).close();
    }

    @Test public void rejectedExecutorReleasesConfigurationLease() throws Exception {
        WorkspaceExecutionGuard guard = new WorkspaceExecutionGuard();
        ThreadPoolExecutor pool = mock(ThreadPoolExecutor.class);
        doThrow(new RejectedExecutionException("fixture full")).when(pool).execute(any());
        var dispatcher = dispatcher(guard, pool, mock(IExecuteStrategy.class));
        dispatcher.dispatch(command(), new ResponseBodyEmitter());
        assertNull(dispatcher.activeRunId("session-a"));
        guard.beginUpdate(Set.of("agent-a")).close();
    }

    @Test public void synchronousAssemblyFailureReleasesConfigurationLease() {
        WorkspaceExecutionGuard guard = new WorkspaceExecutionGuard();
        var dispatcher = dispatcher(guard, mock(ThreadPoolExecutor.class), mock(IExecuteStrategy.class));
        ArmoryService armory = mock(ArmoryService.class);
        doThrow(new IllegalStateException("fixture assembly")).when(armory).ensureArmed("agent-a");
        ReflectionTestUtils.setField(dispatcher, "armoryService", armory);
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(command(), new ResponseBodyEmitter()));
        assertNull(dispatcher.activeRunId("session-a"));
        guard.beginUpdate(Set.of("agent-a")).close();
    }

    private AgentDispatchDispatchService dispatcher(WorkspaceExecutionGuard guard, ThreadPoolExecutor pool,
                                                   IExecuteStrategy strategy) {
        var service = new AgentDispatchDispatchService();
        var access = mock(IWorkspaceAccessRepository.class);
        when(access.ownsAgent("alice", "agent-a")).thenReturn(true);
        var repo = mock(IAgentRepository.class);
        when(repo.queryAiAgentByAgentId("agent-a")).thenReturn(AiAgentVO.builder()
                .agentId("agent-a").agentName("A").strategy("fixed").build());
        ReflectionTestUtils.setField(service, "workspaceAccess", access);
        ReflectionTestUtils.setField(service, "workspaceExecutionGuard", guard);
        ReflectionTestUtils.setField(service, "repository", repo);
        ReflectionTestUtils.setField(service, "threadPoolExecutor", pool);
        ReflectionTestUtils.setField(service, "executeStrategyMap", Map.of("fixed", strategy));
        ReflectionTestUtils.setField(service, "runEventPublisher", mock(RunEventPublisher.class));
        return service;
    }

    private ExecuteCommandEntity command() {
        return ExecuteCommandEntity.builder().userId("alice").aiAgentId("agent-a")
                .sessionId("session-a").runId("run-a").message("test").build();
    }
}
