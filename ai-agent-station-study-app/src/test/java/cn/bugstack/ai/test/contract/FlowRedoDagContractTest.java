package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.RootNode;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunStepSnapshot;
import cn.bugstack.ai.infrastructure.adapter.repository.RedisRunSnapshotService;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure contract tests for dependency-aware Flow step redo. */
public class FlowRedoDagContractTest {

    @SuppressWarnings("unchecked")
    private static Set<Integer> rootRedoSet(int target, Map<Integer, Set<Integer>> dependencies) {
        return (Set<Integer>) ReflectionTestUtils.invokeMethod(
                new RootNode(), "collectRedoTargets", target, dependencies);
    }

    private static Map<Integer, Set<Integer>> diamondDependencies() {
        Map<Integer, Set<Integer>> dependencies = new LinkedHashMap<>();
        dependencies.put(1, Set.of());
        dependencies.put(2, Set.of(1));
        dependencies.put(3, Set.of());
        dependencies.put(4, Set.of(2, 3));
        return dependencies;
    }

    @Test
    public void targetAndOnlyItsTransitiveDescendantsAreRerun() {
        Set<Integer> redoSet = rootRedoSet(1, diamondDependencies());

        assertEquals(Set.of(1, 2, 4), redoSet);
        assertFalse("independent branch must not rerun", redoSet.contains(3));
    }

    @Test
    public void diamondRedoOfOneBranchRerunsMergeButKeepsSibling() {
        assertEquals(Set.of(2, 4), rootRedoSet(2, diamondDependencies()));
    }

    @Test
    public void oldAndNewToolNeedsAreUnionedAndDeduplicated() {
        RootNode node = new RootNode();

        String merged = ReflectionTestUtils.invokeMethod(
                node, "mergeToolNeeds", (Object) new String[]{"search capability\nshared", "shared\nwrite file"});

        assertEquals("search capability\nshared\nwrite file", merged);
    }

    @Test
    public void fullFlowPlanSurvivesSnapshotJsonRoundTrip() {
        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("第1步：分析", "DEPENDS_ON: NONE");
        steps.put("第2步：输出", "DEPENDS_ON: 1");
        RunSnapshot source = RunSnapshot.builder()
                .runId("run-1")
                .flowPlanSteps(steps)
                .flowPlanDependencies(Map.of(1, Set.of(), 2, Set.of(1)))
                .build();

        RunSnapshot restored = JSON.parseObject(JSON.toJSONString(source), RunSnapshot.class);

        assertEquals(2, restored.getFlowPlanSteps().size());
        assertEquals(Set.of(1), restored.getFlowPlanDependencies().get(2));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void fullPlanFindsDescendantsThatNeverStarted() {
        Map<String, String> planSteps = new LinkedHashMap<>();
        planSteps.put("第1步：准备", "DEPENDS_ON: NONE");
        planSteps.put("第2步：修改", "DEPENDS_ON: 1");
        planSteps.put("第3步：交付", "DEPENDS_ON: 2");
        Map<Integer, Set<Integer>> dependencies = new LinkedHashMap<>();
        dependencies.put(1, Set.of());
        dependencies.put(2, Set.of(1));
        dependencies.put(3, Set.of(2));
        RunSnapshot source = RunSnapshot.builder()
                .runId("partial-run")
                .flowPlanSteps(planSteps)
                .flowPlanDependencies(dependencies)
                // Step 3 was cancelled before executeStep, so no per-step snapshot exists.
                .steps(List.of(step(4, 1, "flow_step4_execute_step_1", "done-1")))
                .build();
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find("partial-run")).thenReturn(Optional.of(source));
        RootNode node = new RootNode();
        ReflectionTestUtils.setField(node, "runSnapshotService", snapshots);
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .sourceRunId("partial-run")
                .message("修正第二步")
                .build();

        Map<String, String> redoSteps = (Map<String, String>) ReflectionTestUtils.invokeMethod(
                node, "buildRedoStepsMap", request, 2, planSteps.get("第2步：修改"));

        assertEquals(2, redoSteps.size());
        assertTrue(redoSteps.keySet().stream().anyMatch(key -> key.startsWith("第2步")));
        assertTrue(redoSteps.keySet().stream().anyMatch(key -> key.startsWith("第3步")));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void inheritedCardsKeepIndependentBranchEvenWhenItSortsAfterTarget() {
        RedisRunSnapshotService service = new RedisRunSnapshotService();
        List<RunStepSnapshot> steps = new ArrayList<>();
        steps.add(step(1, null, "thinking:plan", "plan"));
        steps.add(step(4, 1, "flow_step4_execute_step_1", "result-1"));
        steps.add(step(5, 2, "flow_step4_execute_step_2", "result-2"));
        steps.add(step(6, 3, "flow_step4_execute_step_3", "independent-result"));
        steps.add(step(7, 4, "flow_step4_execute_step_4", "merged-result"));

        List<RunStepSnapshot> inherited = (List<RunStepSnapshot>) ReflectionTestUtils.invokeMethod(
                service, "selectInheritedSourceSteps", steps, 4, Set.of(1, 2, 4));

        assertEquals(List.of("thinking:plan", "flow_step4_execute_step_3"),
                inherited.stream().map(RunStepSnapshot::getStepId).toList());
    }

    @Test
    public void redoingStepOneSeedsIndependentBranchResult() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        RunSnapshot source = RunSnapshot.builder()
                .runId("source-run")
                .flowPlanDependencies(diamondDependencies())
                .steps(List.of(
                        step(4, 1, "flow_step4_execute_step_1", "old-1"),
                        step(5, 2, "flow_step4_execute_step_2", "old-2"),
                        step(6, 3, "flow_step4_execute_step_3", "side-3"),
                        step(7, 4, "flow_step4_execute_step_4", "old-4")))
                .build();
        when(snapshots.find("source-run")).thenReturn(Optional.of(source));
        RootNode node = new RootNode();
        ReflectionTestUtils.setField(node, "runSnapshotService", snapshots);
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        ExecuteCommandEntity request = ExecuteCommandEntity.builder().sourceRunId("source-run").build();

        ReflectionTestUtils.invokeMethod(node, "seedRedoInheritedStepResults", context, request, 1);

        assertEquals("side-3", context.getValue("step3Result"));
        assertNull(context.getValue("step1Result"));
        assertNull(context.getValue("step2Result"));
        assertNull(context.getValue("step4Result"));
    }

    private static RunStepSnapshot step(int ordinal, Integer stepNo, String stepId, String content) {
        return RunStepSnapshot.builder()
                .ordinal(ordinal)
                .stepNo(stepNo)
                .stepId(stepId)
                .title(stepId)
                .type("flow")
                .status("COMPLETED")
                .content(content)
                .build();
    }
}
