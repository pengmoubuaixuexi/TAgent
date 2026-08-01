package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step4ExecuteStepsNode;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Contract tests for fail-closed Flow DAG execution semantics. */
public class FlowDagFailurePropagationContractTest {

    @SuppressWarnings("unchecked")
    @Test
    public void validDagIsTopologicallySorted() {
        Map<Integer, Set<Integer>> dependencies = new LinkedHashMap<>();
        dependencies.put(1, Set.of());
        dependencies.put(2, Set.of(1));
        dependencies.put(3, Set.of());
        dependencies.put(4, Set.of(2, 3));

        List<Integer> order = (List<Integer>) ReflectionTestUtils.invokeMethod(
                Step4ExecuteStepsNode.class,
                "validateAndTopologicallySort",
                List.of(1, 2, 3, 4), dependencies);

        assertTrue(order.indexOf(1) < order.indexOf(2));
        assertTrue(order.indexOf(2) < order.indexOf(4));
        assertTrue(order.indexOf(3) < order.indexOf(4));
    }

    @Test
    public void cyclicDagIsRejectedInsteadOfFallingBackToSerialExecution() {
        assertRejected(
                List.of(1, 2),
                Map.of(1, Set.of(2), 2, Set.of(1)),
                "contain a cycle");
    }

    @Test
    public void missingDependencyIsRejectedBeforeExecution() {
        assertRejected(
                List.of(1, 2),
                Map.of(1, Set.of(), 2, Set.of(9)),
                "depends on missing step 9");
    }

    @Test
    public void failedDependencyIsPersistedFailedAndItsChildIsPersistedBlocked() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        Step4ExecuteStepsNode node = new Step4ExecuteStepsNode();
        ReflectionTestUtils.setField(node, "runSnapshotService", snapshots);
        ReflectionTestUtils.setField(node, "dagExecutor", (java.util.concurrent.Executor) Runnable::run);

        DefaultFlowAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        context.setStep(4);
        context.setValue("runId", "run-failure-propagation");
        context.setValue("sessionId", "session-failure-propagation");

        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("第1步：查询", "查询订单");
        steps.put("第2步：处理", "根据订单执行退款");

        try {
            ReflectionTestUtils.invokeMethod(
                    node,
                    "executeStepsAsDag",
                    null,
                    steps,
                    List.of(1, 2),
                    Map.of(1, Set.of(), 2, Set.of(1)),
                    context,
                    ExecuteCommandEntity.builder().sessionId("session-failure-propagation").build());
            fail("DAG with a failed step must fail the run");
        } catch (RuntimeException expected) {
            assertTrue(rootMessage(expected).contains("execution failed"));
        }

        verify(snapshots).recordStep(
                eq("run-failure-propagation"),
                eq("flow_step4_execute_step_1"),
                any(),
                eq("flow_step4_execution"),
                eq(1),
                contains("执行失败"),
                eq(RunSnapshotService.STATUS_FAILED));
        verify(snapshots).recordStep(
                eq("run-failure-propagation"),
                eq("flow_step4_execute_step_2"),
                any(),
                eq("flow_step4_execution"),
                eq(2),
                contains("已阻断"),
                eq(RunSnapshotService.STATUS_BLOCKED));
    }

    @Test
    public void answerNowSkipsUnstartedDagStepsWithoutTurningRunIntoFailure() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        Step4ExecuteStepsNode node = new Step4ExecuteStepsNode();
        ReflectionTestUtils.setField(node, "runSnapshotService", snapshots);
        ReflectionTestUtils.setField(node, "dagExecutor", (java.util.concurrent.Executor) Runnable::run);

        DefaultFlowAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        context.setValue("runId", "run-answer-now");
        context.setValue("sessionId", "session-answer-now");
        context.requestFinalize();

        ReflectionTestUtils.invokeMethod(
                node,
                "executeStepsAsDag",
                null,
                Map.of("第1步：查询", "查询", "第2步：处理", "处理"),
                List.of(1, 2),
                Map.of(1, Set.of(), 2, Set.of(1)),
                context,
                ExecuteCommandEntity.builder().sessionId("session-answer-now").build());

        verify(snapshots, never()).recordStep(any(), any(), any(), any(), any(), any(), any());
    }

    private static void assertRejected(List<Integer> steps,
                                       Map<Integer, Set<Integer>> dependencies,
                                       String expectedMessage) {
        try {
            ReflectionTestUtils.invokeMethod(
                    Step4ExecuteStepsNode.class,
                    "validateAndTopologicallySort",
                    steps,
                    dependencies);
            fail("invalid DAG must be rejected");
        } catch (RuntimeException expected) {
            assertTrue(rootMessage(expected).contains(expectedMessage));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }
}
