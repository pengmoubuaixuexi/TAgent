package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P0-B2b-O1：真 doApply 下 inspect() provenance 指标被正确记录，且旧控制流仍由 rawResult 驱动（不变）。
 */
public class AutoNodeShadowProvenanceTest {

    private static ExecuteCommandEntity request() {
        return ExecuteCommandEntity.builder().aiAgentId("8012").message("合成任务")
                .sessionId("contract-session").userId("user-1").tenantId("tenant-1").build();
    }

    private static DefaultAutoAgentExecuteStrategyFactory.DynamicContext context(String clientType, String prompt) {
        Map<String, AiAgentClientFlowConfigVO> configs = new HashMap<>();
        configs.put(clientType, AiAgentClientFlowConfigVO.builder().clientId("fixture-client")
                .clientType(clientType).stepPrompt(prompt).sequence(1).build());
        return DefaultAutoAgentExecuteStrategyFactory.DynamicContext.builder()
                .step(1).maxStep(4).executionHistory(new StringBuilder())
                .currentTask("合成任务").aiAgentClientFlowConfigVOMap(configs).build();
    }

    private static DefaultAutoAgentExecuteStrategyFactory.DynamicContext step3Context() {
        var ctx = context(AiClientTypeEnumVO.QUALITY_SUPERVISOR_CLIENT.getCode(), "%s %s");
        ctx.setValue("executionResult", "已有执行结果");
        return ctx;
    }

    private static void injectMetrics(Object seam, Class<?> nodeClass, AutoAgentMetrics m) throws Exception {
        Field f = nodeClass.getDeclaredField("autoAgentMetrics");
        f.setAccessible(true);
        f.set(seam, m);
    }

    private static double count(SimpleMeterRegistry reg, String name, String... tags) {
        Counter c = reg.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    public void step3FieldVsProseConflictRecorded_controlFlowUnchanged() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var node = new AutoNodeTestSeams.Step3();
        injectMetrics(node, Step3QualitySupervisorNode.class, new AutoAgentMetrics(reg));
        // 散文 FAIL + 机器字段 PASS → resolved=PASS(field 权威)、对照 conflict；旧真分支读 raw 含"是否通过: FAIL"→回炉
        var ctx = step3Context();
        node.result("是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        node.run(request(), ctx);
        // 控制流不变：真分支按 raw FAIL → 不完成 + 回炉
        assertFalse(ctx.isCompleted());
        assertTrue(ctx.getCurrentTask().contains("重新执行"));
        // provenance 指标
        assertTrue("candidate=new_field",
                count(reg, "agent.auto.supervision.candidate", "source", "new_field") >= 1.0);
        assertTrue("fieldvsprose=conflict",
                count(reg, "agent.auto.contract.fieldvsprose", "stage", "step3", "result", "conflict") >= 1.0);
        assertTrue("contract.shadow legacy=fail candidate=pass",
                count(reg, "agent.auto.contract.shadow", "stage", "step3", "legacy", "fail", "candidate", "pass") >= 1.0);
    }

    @Test
    public void step1FieldVsProseConflictRecorded_controlFlowUnchanged() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var node = new AutoNodeTestSeams.Step1();
        injectMetrics(node, Step1AnalyzerNode.class, new AutoAgentMetrics(reg));
        var ctx = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        // 散文 COMPLETED + 机器字段 CONTINUE → resolved=CONTINUE、对照 conflict；旧真分支读 raw 含"任务状态: COMPLETED"→完成
        node.result("任务状态: COMPLETED\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->");
        node.run(request(), ctx);
        assertTrue("控制流不变：真分支按 raw COMPLETED → 完成", ctx.isCompleted());
        assertTrue("candidate=new_field",
                count(reg, "agent.auto.analysis.candidate", "source", "new_field") >= 1.0);
        assertTrue("fieldvsprose=conflict",
                count(reg, "agent.auto.contract.fieldvsprose", "stage", "step1", "result", "conflict") >= 1.0);
    }

    @Test
    public void step1UnknownReasonTrailerMissingRecorded() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var node = new AutoNodeTestSeams.Step1();
        injectMetrics(node, Step1AnalyzerNode.class, new AutoAgentMetrics(reg));
        var ctx = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        node.result("一段没有任何完成标记也没有机器尾注的分析正文");
        node.run(request(), ctx);
        assertFalse("无完成标记 → 不完成（继续）", ctx.isCompleted());
        assertTrue("unknown_reason=trailer_missing",
                count(reg, "agent.auto.contract.unknown_reason", "stage", "step1", "reason", "trailer_missing") >= 1.0);
    }
}
