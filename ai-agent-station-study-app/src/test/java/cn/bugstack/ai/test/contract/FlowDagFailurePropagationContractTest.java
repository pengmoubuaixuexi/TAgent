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

    @Test
    public void usedUpBudgetSkipsUnstartedStepsAndLeavesTheAnswerToCompletedOnes() {
        for (String method : List.of("executeStepsAsDag", "executeStepsSequentially")) {
            String runId = "run-budget-" + method;
            var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.start(runId, "owner",
                    new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.Limits(
                            java.time.Duration.ofSeconds(30), 10, 10, 1000));
            try {
                budget.reach("本次任务的 Token 预算（1000）已用完");
                RunSnapshotService snapshots = mock(RunSnapshotService.class);
                Step4ExecuteStepsNode node = new Step4ExecuteStepsNode();
                ReflectionTestUtils.setField(node, "runSnapshotService", snapshots);
                ReflectionTestUtils.setField(node, "dagExecutor", (java.util.concurrent.Executor) Runnable::run);
                DefaultFlowAgentExecuteStrategyFactory.DynamicContext context =
                        new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
                context.setValue("runId", runId);
                context.setValue("sessionId", "session-budget");

                // No failure: skipped steps are reported, and the answer is built from what completed.
                ReflectionTestUtils.invokeMethod(node, method, null,
                        Map.of("第1步：查询", "查询", "第2步：处理", "处理"),
                        List.of(1, 2),
                        Map.of(1, Set.of(), 2, Set.of(1)),
                        context,
                        ExecuteCommandEntity.builder().runId(runId).sessionId("session-budget").build());

                assertTrue(method, "本次任务的 Token 预算（1000）已用完".equals(context.getValue("budgetStopReason")));
                for (int step : List.of(1, 2)) {
                    verify(snapshots).recordStep(eq(runId), eq("flow_step4_execute_step_" + step), any(),
                            eq("flow_step4_execution"), eq(step), contains("未执行"), eq(RunSnapshotService.STATUS_BLOCKED));
                }
            } finally {
                cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.finish(runId);
            }
        }
    }

    @Test
    public void answerRefusedByTheHardCeilingStillDeliversTheCompletedSteps() throws Exception {
        Step4ExecuteStepsNode node = new Step4ExecuteStepsNode();
        org.springframework.context.ApplicationContext beans = mock(org.springframework.context.ApplicationContext.class);
        org.mockito.Mockito.when(beans.getBean(org.mockito.ArgumentMatchers.anyString())).thenThrow(
                new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException("任务 Token 用量已超过预算的 120%"));
        ReflectionTestUtils.setField(node, "applicationContext", beans);
        org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter emitter =
                mock(org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class);
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext context = new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        context.setAiAgentClientFlowConfigVOMap(Map.of(
                cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode(),
                cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO.builder().clientId("executor").build()));
        context.setValue("emitter", emitter);
        context.setValue("budgetStopReason", "本次任务的 Token 预算（1000）已用完");
        context.setValue("step1Result", "Python 用 asyncio 和线程池做并发。");

        String outcome = ReflectionTestUtils.invokeMethod(node, "finalizeNow",
                ExecuteCommandEntity.builder().runId("run-ceiling").sessionId("s").build(), context);

        assertTrue(outcome, outcome.contains("已交付"));
        assertTrue(context.isCompleted());
        org.mockito.ArgumentCaptor<Object> sent = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(sent.capture());
        String all = String.valueOf(sent.getAllValues());
        assertTrue(all, all.contains("Python 用 asyncio") && all.contains("已超过上限"));
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
