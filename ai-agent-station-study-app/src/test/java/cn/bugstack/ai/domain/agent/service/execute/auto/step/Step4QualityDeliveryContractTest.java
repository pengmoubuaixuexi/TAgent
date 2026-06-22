package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.QualityVerificationStatus;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** P0-B2b-Step3：Step4 质量降级指令与 terminal 单点指标。 */
public class Step4QualityDeliveryContractTest {

    @Test
    public void directivesCoverOnlyAbnormalDeliveryStates() {
        assertEquals("", Step4LogExecutionSummaryNode.qualityDeliveryDirective(null));
        assertEquals("", Step4LogExecutionSummaryNode.qualityDeliveryDirective(QualityVerificationStatus.NOT_ASSESSED));
        assertEquals("", Step4LogExecutionSummaryNode.qualityDeliveryDirective(QualityVerificationStatus.VERIFIED_PASS));

        String notVerified = Step4LogExecutionSummaryNode.qualityDeliveryDirective(QualityVerificationStatus.QUALITY_NOT_VERIFIED);
        assertTrue(notVerified.contains("不得声称本次结果已完成质量验证"));
        assertTrue(notVerified.contains("不要提及任何内部流程"));

        String failed = Step4LogExecutionSummaryNode.qualityDeliveryDirective(QualityVerificationStatus.VERIFIED_FAIL);
        assertTrue(failed.contains("不得把本次结果描述为已通过完整质量检查"));

        String optimize = Step4LogExecutionSummaryNode.qualityDeliveryDirective(QualityVerificationStatus.VERIFIED_OPTIMIZE);
        assertTrue(optimize.contains("仍有优化空间"));
    }

    @Test
    public void deterministicNoticesAreVisibleSafeAndIdempotent() {
        String body = "这是当前最佳结果。";
        assertEquals(body, Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(null, body));
        assertEquals(body, Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.NOT_ASSESSED, body));
        assertEquals(body, Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.VERIFIED_PASS, body));

        String qnv = Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.QUALITY_NOT_VERIFIED, body);
        assertTrue(qnv.startsWith("⚠️ 说明：本次结果未经完整质量确认，可能存在局限。"));
        assertTrue(qnv.endsWith(body));
        assertEquals(qnv, Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.QUALITY_NOT_VERIFIED, qnv));

        String fail = Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.VERIFIED_FAIL, body);
        assertTrue(fail.startsWith("⚠️ 说明：本次结果尚未通过完整质量检查"));
        assertEquals(fail, Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.VERIFIED_FAIL, fail));

        String optimize = Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.VERIFIED_OPTIMIZE, body);
        assertTrue(optimize.startsWith(body));
        assertTrue(optimize.endsWith("提示：本次结果仍有进一步优化空间。"));
        assertEquals(optimize, Step4LogExecutionSummaryNode.applyQualityDeliveryNotice(QualityVerificationStatus.VERIFIED_OPTIMIZE, optimize));

        for (String userFacing : new String[]{qnv, fail, optimize}) {
            assertTrue(!userFacing.contains("QUALITY_NOT_VERIFIED"));
            assertTrue(!userFacing.contains("VERIFIED_"));
            assertTrue(!userFacing.contains("step3"));
            assertTrue(!userFacing.contains("prompt"));
        }
    }

    @Test
    public void step4RecordsExactlyOneTerminalMetricForTheRequest() throws Exception {
        var registry = new SimpleMeterRegistry();
        var node = new AutoNodeTestSeams.Step4();
        node.metrics(new AutoAgentMetrics(registry));
        var ctx = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        ctx.setStep(2);
        ctx.setMaxStep(1);
        ctx.setExecutionHistory(new StringBuilder());
        ctx.setQualityVerificationStatus(QualityVerificationStatus.QUALITY_NOT_VERIFIED);

        node.run(ExecuteCommandEntity.builder().sessionId("quality-terminal").build(), ctx);

        assertEquals(1, node.generateCalls());
        assertEquals(1.0, registry.get("agent.auto.quality.terminal")
                .tag("status", "quality_not_verified").counter().count(), 0.0);
        assertEquals(1, registry.find("agent.auto.quality.terminal").counters().size());
    }
}
