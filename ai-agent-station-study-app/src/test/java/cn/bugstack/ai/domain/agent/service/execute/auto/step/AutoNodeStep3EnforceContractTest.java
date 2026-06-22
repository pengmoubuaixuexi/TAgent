package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.QualityVerificationStatus;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P0-B2b-Step3：真实 {@code doApply} 的 enforce/repair/终态契约（无网络、无 DB）。 */
public class AutoNodeStep3EnforceContractTest {

    private static ExecuteCommandEntity request() {
        return ExecuteCommandEntity.builder().aiAgentId("8012").message("合成任务")
                .sessionId("contract-session").userId("user-1").tenantId("tenant-1").build();
    }

    private static DefaultAutoAgentExecuteStrategyFactory.DynamicContext context(int step, int maxStep) {
        Map<String, AiAgentClientFlowConfigVO> configs = new HashMap<>();
        String type = AiClientTypeEnumVO.QUALITY_SUPERVISOR_CLIENT.getCode();
        configs.put(type, AiAgentClientFlowConfigVO.builder().clientId("fixture-client")
                .clientType(type).stepPrompt("%s %s").sequence(1).build());
        var ctx = DefaultAutoAgentExecuteStrategyFactory.DynamicContext.builder()
                .step(step).maxStep(maxStep).executionHistory(new StringBuilder())
                .currentTask("合成任务").aiAgentClientFlowConfigVOMap(configs).build();
        ctx.setValue("executionResult", "已有执行结果");
        return ctx;
    }

    private static void injectMetrics(AutoNodeTestSeams.Step3 node, AutoAgentMetrics metrics) throws Exception {
        Field f = Step3QualitySupervisorNode.class.getDeclaredField("autoAgentMetrics");
        f.setAccessible(true);
        f.set(node, metrics);
    }

    @Test
    public void canonicalPrimaryVerdictsDrivePassFailAndOptimizeWithoutRepair() throws Exception {
        var pass = context(1, 4);
        var passNode = new AutoNodeTestSeams.Step3();
        passNode.result("质量评分: 95\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        passNode.run(request(), pass);
        assertTrue(pass.isCompleted());
        assertEquals(QualityVerificationStatus.VERIFIED_PASS, pass.getQualityVerificationStatus());
        assertEquals(0, passNode.repairCalls());

        var fail = context(1, 4);
        var failNode = new AutoNodeTestSeams.Step3();
        failNode.result("是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: FAIL -->");
        failNode.run(request(), fail);
        assertFalse(fail.isCompleted());
        assertTrue(fail.getCurrentTask().contains("重新执行"));
        assertEquals(QualityVerificationStatus.VERIFIED_FAIL, fail.getQualityVerificationStatus());
        assertEquals(0, failNode.repairCalls());

        var optimize = context(1, 4);
        var optimizeNode = new AutoNodeTestSeams.Step3();
        optimizeNode.result("<!-- AUTO_QUALITY_VERDICT: OPTIMIZE -->");
        optimizeNode.run(request(), optimize);
        assertFalse(optimize.isCompleted());
        assertTrue(optimize.getCurrentTask().contains("优化"));
        assertEquals(QualityVerificationStatus.VERIFIED_OPTIMIZE, optimize.getQualityVerificationStatus());
        assertEquals(0, optimizeNode.repairCalls());
    }

    @Test
    public void proseOnlyPrimaryUsesCanonicalRepairAndPreservesForceNoneWireContract() throws Exception {
        var ctx = context(1, 4);
        var node = new AutoNodeTestSeams.Step3();
        node.result("是否通过: FAIL");
        node.repairResult("<!-- AUTO_QUALITY_VERDICT: PASS -->");
        node.run(request(), ctx);

        assertTrue(ctx.isCompleted());
        assertEquals(QualityVerificationStatus.VERIFIED_PASS, ctx.getQualityVerificationStatus());
        assertEquals(1, node.repairCalls());
        var wire = node.repairSnapshot();
        assertEquals(Integer.valueOf(128), wire.maxTokens());
        assertEquals(Boolean.FALSE, wire.internalToolExecutionEnabled());
        assertEquals(Boolean.TRUE, wire.toolContext().get(RobustToolCallingManager.FORCE_NO_TOOLS_KEY));
        assertTrue(wire.toolNames().isEmpty());
        assertTrue(wire.messages().stream().anyMatch(m -> m.text().contains("是否通过: FAIL")));
        assertTrue(wire.messages().stream().anyMatch(m -> m.text().contains("AUTO_QUALITY_VERDICT")));
    }

    @Test
    public void repairCanReturnEachNonPassVerdict() throws Exception {
        var fail = context(1, 4);
        var failNode = new AutoNodeTestSeams.Step3();
        failNode.result("无机器字段");
        failNode.repairResult("<!-- AUTO_QUALITY_VERDICT: FAIL -->");
        failNode.run(request(), fail);
        assertEquals(QualityVerificationStatus.VERIFIED_FAIL, fail.getQualityVerificationStatus());
        assertTrue(fail.getCurrentTask().contains("重新执行"));

        var optimize = context(1, 4);
        var optimizeNode = new AutoNodeTestSeams.Step3();
        optimizeNode.result("无机器字段");
        optimizeNode.repairResult("<!-- AUTO_QUALITY_VERDICT: OPTIMIZE -->");
        optimizeNode.run(request(), optimize);
        assertEquals(QualityVerificationStatus.VERIFIED_OPTIMIZE, optimize.getQualityVerificationStatus());
        assertTrue(optimize.getCurrentTask().contains("优化"));
    }

    @Test
    public void invalidRepairFailsClosedAndPinsNotVerifiedAtMaxStep() throws Exception {
        var ctx = context(1, 1);
        var node = new AutoNodeTestSeams.Step3();
        node.result("是否通过：FAIL");
        node.repairResult("判定大概失败，但没有 canonical 字段");
        node.run(request(), ctx);

        assertFalse(ctx.isCompleted());
        assertTrue(ctx.getCurrentTask().contains("重新执行"));
        assertEquals(QualityVerificationStatus.QUALITY_NOT_VERIFIED, ctx.getQualityVerificationStatus());
        assertEquals(2, ctx.getStep());
    }

    @Test
    public void conflictRecordsEnforcementReasonAndRepairOutcome() throws Exception {
        var registry = new SimpleMeterRegistry();
        var ctx = context(1, 4);
        var node = new AutoNodeTestSeams.Step3();
        injectMetrics(node, new AutoAgentMetrics(registry));
        node.result("是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        node.repairResult("<!-- AUTO_QUALITY_VERDICT: OPTIMIZE -->");
        node.run(request(), ctx);

        assertEquals(1.0, registry.get("agent.auto.contract.unknown_reason")
                .tags("stage", "step3", "reason", "field_prose_conflict").counter().count(), 0.0);
        assertEquals(1.0, registry.get("agent.auto.supervision.parse")
                .tags("phase", "repair", "verdict", "optimize").counter().count(), 0.0);
        assertEquals(1.0, registry.get("agent.auto.supervision.contract.repair")
                .tag("outcome", "success").counter().count(), 0.0);
        // shadow candidate 继续保持迁移期 parser 的 field-first 语义，避免时间序列断裂。
        assertEquals(1.0, registry.get("agent.auto.contract.shadow")
                .tags("stage", "step3", "legacy", "fail", "candidate", "pass").counter().count(), 0.0);
    }
}
