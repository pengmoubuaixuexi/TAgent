package cn.bugstack.ai.test.domain;
import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.FlowAgentExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step4ExecuteStepsNode;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import cn.bugstack.ai.domain.agent.service.execute.common.LongTermMemoryTurnSnapshot;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.Assert.*;
public class FlowVersionResumeTest {
    @Test public void changedVersionCannotExecuteReviewedPlanAndReleasesLease() throws Exception {
        var strategy=new FlowAgentExecuteStrategy();var access=mock(IWorkspaceAccessRepository.class);
        when(access.ownsAgent("owner","agent")).thenReturn(true);when(access.activeAgentVersion("owner","agent")).thenReturn(2);
        var executor=mock(ThreadPoolExecutor.class);doAnswer(c->{((Runnable)c.getArgument(0)).run();return null;}).when(executor).execute(any());
        var node=mock(Step4ExecuteStepsNode.class);var review=mock(FlowPlanReviewService.class);var guard=new WorkspaceExecutionGuard();
        ReflectionTestUtils.setField(strategy,"workspaceAccess",access);ReflectionTestUtils.setField(strategy,"threadPoolExecutor",executor);
        ReflectionTestUtils.setField(strategy,"workspaceGuard",guard);ReflectionTestUtils.setField(strategy,"step4ExecuteStepsNode",node);
        ReflectionTestUtils.setField(strategy,"flowPlanReviewService",review);ReflectionTestUtils.setField(strategy,"longTermMemoryTurnSnapshot",mock(LongTermMemoryTurnSnapshot.class));
        strategy.resumeReviewedPlan(FlowPlanReviewState.builder().runId("r").sessionId("s").agentId("agent").agentVersion(1).userId("owner").attemptCount(1).build(),
                FlowPlanReviewValidationResult.builder().valid(true).stepsMap(Map.of("1","step")).stepDependencies(Map.of()).build(),new ResponseBodyEmitter());
        verify(node,never()).apply(any(),any());verify(review).markExecutionFailed(eq("r"),contains("版本"),eq(1));
        try(var update=guard.beginUpdate(Set.of("agent"))) { assertNotNull(update); }
    }
}
