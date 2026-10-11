package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.flow.FlowAgentExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step4ExecuteStepsNode;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import cn.bugstack.ai.types.exception.BizException;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RunCleanupFailureTest {
    private IWorkspaceAccessRepository access(){
        var access=mock(IWorkspaceAccessRepository.class);when(access.ownsAgent("alice","agent")).thenReturn(true);
        when(access.activeAgentVersion("alice","agent")).thenReturn(1);return access;
    }
    private ExecuteCommandEntity request(){return ExecuteCommandEntity.builder().userId("alice").aiAgentId("agent").sessionId("cleanup-session").runId("cleanup-run").message("x").build();}

    @Test public void preDispatchSnapshotFailureReleasesSessionReservation(){
        var dispatcher=new AgentDispatchDispatchService();var snapshots=mock(RunSnapshotService.class);
        ReflectionTestUtils.setField(dispatcher,"workspaceAccess",access());ReflectionTestUtils.setField(dispatcher,"runSnapshotService",snapshots);
        when(snapshots.find("cleanup-run")).thenThrow(new IllegalStateException("fixture Redis unavailable"));
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(request(),new ResponseBodyEmitter()));
        assertNull(dispatcher.activeRunId("cleanup-session"));assertNull(MDC.get("runId"));assertNull(MDC.get("userId"));
    }

    @Test public void cleanupSnapshotFailureDoesNotMaskOriginalErrorOrSkipRunRelease() throws Exception {
        var dispatcher=new AgentDispatchDispatchService();var snapshots=mock(RunSnapshotService.class);var publisher=mock(RunEventPublisher.class);
        var pool=mock(ThreadPoolExecutor.class);var worker=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        doAnswer(call->{worker.set(call.getArgument(0));return null;}).when(pool).execute(any());
        ReflectionTestUtils.setField(dispatcher,"threadPoolExecutor",pool);
        var guard=new WorkspaceExecutionGuard();ReflectionTestUtils.setField(dispatcher,"workspaceExecutionGuard",guard);
        ReflectionTestUtils.setField(dispatcher,"workspaceAccess",access());ReflectionTestUtils.setField(dispatcher,"runSnapshotService",snapshots);
        ReflectionTestUtils.setField(dispatcher,"runEventPublisher",publisher);ReflectionTestUtils.setField(dispatcher,"repository",mock(IAgentRepository.class));
        when(snapshots.find("cleanup-run")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("fixture failure during cleanup")).when(snapshots)
                .markStatus(eq("cleanup-run"),eq("FAILED"),any());
        // Preparation now executes after the SSE handler returns. Its original failure must reach
        // this run's event channel, even if persisting the terminal snapshot also fails.
        dispatcher.dispatch(request(),new ResponseBodyEmitter());
        assertEquals("cleanup-run",dispatcher.activeRunId("cleanup-session"));
        assertNotNull(worker.get());
        worker.get().run();
        var order=inOrder(publisher);
        order.verify(publisher).registerRun("cleanup-run","cleanup-session");
        order.verify(publisher).publish(eq("cleanup-run"),eq("cleanup-session"),eq("message"),
                argThat(payload -> payload instanceof Map<?,?> error
                        && "error".equals(error.get("type"))
                        && String.valueOf(error.get("content")).contains("agent 不存在")
                        && !String.valueOf(error.get("content")).contains("fixture failure during cleanup")));
        order.verify(publisher).finishRun("cleanup-run");
        assertNull(dispatcher.activeRunId("cleanup-session"));
        assertNull(MDC.get("runId"));assertNull(MDC.get("userId"));
        try(var lease=guard.beginUpdate(Set.of("agent"))){assertNotNull(lease);}
    }

    @Test public void resumeReviewWriteAndToolCleanupFailuresStillReleaseOtherResources() throws Exception {
        var strategy=new FlowAgentExecuteStrategy();var pool=mock(ThreadPoolExecutor.class);var guard=new WorkspaceExecutionGuard();
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(pool).execute(any());
        var review=mock(FlowPlanReviewService.class);var node=mock(Step4ExecuteStepsNode.class);var memory=mock(LongTermMemoryTurnSnapshot.class);
        var catalog=mock(McpToolCatalogService.class);var ledger=mock(ToolCallLedger.class);var emitter=mock(ResponseBodyEmitter.class);
        ReflectionTestUtils.setField(strategy,"threadPoolExecutor",pool);ReflectionTestUtils.setField(strategy,"workspaceGuard",guard);
        ReflectionTestUtils.setField(strategy,"workspaceAccess",access());ReflectionTestUtils.setField(strategy,"repository",mock(IAgentRepository.class));
        ReflectionTestUtils.setField(strategy,"flowPlanReviewService",review);ReflectionTestUtils.setField(strategy,"step4ExecuteStepsNode",node);
        ReflectionTestUtils.setField(strategy,"longTermMemoryTurnSnapshot",memory);ReflectionTestUtils.setField(strategy,"mcpToolCatalogService",catalog);
        ReflectionTestUtils.setField(strategy,"toolCallLedger",ledger);
        doThrow(new IllegalStateException("fixture Redis unavailable")).when(review).markExecutionCompleted("resume-cleanup-run",1);
        doThrow(new IllegalStateException("fixture tool cleanup unavailable")).when(catalog).clearNeeds("resume-cleanup-session");
        strategy.resumeReviewedPlan(FlowPlanReviewState.builder().runId("resume-cleanup-run").sessionId("resume-cleanup-session").agentId("agent")
                        .userId("alice").agentVersion(1).attemptCount(1).build(),
                FlowPlanReviewValidationResult.builder().valid(true).stepsMap(Map.of("第1步","work")).stepDependencies(Map.of()).build(),emitter);
        verify(review).markExecutionCompleted("resume-cleanup-run",1);verify(memory).clearSession("resume-cleanup-session");
        verify(catalog).cleanupRun("resume-cleanup-run");verify(ledger).clear("resume-cleanup-run");verify(emitter).complete();
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(strategy,"activeContexts")).isEmpty());
        assertNull(MDC.get("userId"));assertNull(MDC.get("runId"));assertNull(MDC.get("agent.run_id"));
        try(var lease=guard.beginUpdate(Set.of("agent"))){assertNotNull(lease);}
    }
}
