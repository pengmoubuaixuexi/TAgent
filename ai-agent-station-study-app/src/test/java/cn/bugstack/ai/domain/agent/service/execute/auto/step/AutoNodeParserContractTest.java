package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.QualityVerificationStatus;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.types.exception.BizException;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Characterization tests that drive the real Step1/Step3 doApply state transitions. */
public class AutoNodeParserContractTest {

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

    @Test
    public void step1ExactMarkersCompleteAndFullWidthColonDoesNot() throws Exception {
        var exact = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        var node = new AutoNodeTestSeams.Step1();
        node.result("任务状态: COMPLETED");
        node.run(request(), exact);
        assertTrue(exact.isCompleted());

        var fullWidth = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        node.result("任务状态：COMPLETED");
        node.run(request(), fullWidth);
        assertFalse(fullWidth.isCompleted());
    }

    @Test
    public void step1NullThrowsBeforeCompletionPredicate() throws Exception {
        var ctx = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        var node = new AutoNodeTestSeams.Step1();
        node.result(null);
        try {
            node.run(request(), ctx);
            fail("expected BizException");
        } catch (BizException expected) {
            assertFalse(ctx.isCompleted());
        }
    }

    @Test
    public void step3LegacyOnlyVerdictsRequireRepairAndFailClosedWhenRepairIsInvalid() throws Exception {
        var failCtx = step3Context();
        var node = new AutoNodeTestSeams.Step3();
        node.result("是否通过: FAIL");
        node.run(request(), failCtx);
        assertFalse(failCtx.isCompleted());
        assertTrue(failCtx.getCurrentTask().contains("重新执行"));
        assertTrue(failCtx.getQualityVerificationStatus() == QualityVerificationStatus.QUALITY_NOT_VERIFIED);

        var optimizeCtx = step3Context();
        node.result("是否通过: OPTIMIZE");
        node.run(request(), optimizeCtx);
        assertFalse(optimizeCtx.isCompleted());
        assertTrue(optimizeCtx.getCurrentTask().contains("重新执行"));
        assertTrue(optimizeCtx.getQualityVerificationStatus() == QualityVerificationStatus.QUALITY_NOT_VERIFIED);
    }

    @Test
    public void step3FormerFailOpenFormatsNowFailClosed() throws Exception {
        var bare = step3Context();
        var node = new AutoNodeTestSeams.Step3();
        node.result("结论：FAIL");
        node.run(request(), bare);
        assertFalse("TO-BE: bare FAIL without canonical field must never complete", bare.isCompleted());
        assertTrue(bare.getQualityVerificationStatus() == QualityVerificationStatus.QUALITY_NOT_VERIFIED);

        var fullWidth = step3Context();
        node.result("是否通过：FAIL");
        node.run(request(), fullWidth);
        assertFalse("TO-BE: full-width legacy FAIL without canonical field must never complete", fullWidth.isCompleted());
        assertTrue(fullWidth.getQualityVerificationStatus() == QualityVerificationStatus.QUALITY_NOT_VERIFIED);
    }

    @Test
    public void step3ConflictPrefersFailAndNullThrows() throws Exception {
        var conflict = step3Context();
        var node = new AutoNodeTestSeams.Step3();
        node.result("是否通过: OPTIMIZE\n是否通过: FAIL");
        node.run(request(), conflict);
        assertFalse(conflict.isCompleted());
        assertTrue(conflict.getCurrentTask().contains("重新执行"));
        assertTrue(conflict.getQualityVerificationStatus() == QualityVerificationStatus.QUALITY_NOT_VERIFIED);

        var nullCtx = step3Context();
        node.result(null);
        try {
            node.run(request(), nullCtx);
            fail("expected BizException");
        } catch (BizException expected) {
            assertFalse(nullCtx.isCompleted());
        }
    }

    private static DefaultAutoAgentExecuteStrategyFactory.DynamicContext step3Context() {
        var ctx = context(AiClientTypeEnumVO.QUALITY_SUPERVISOR_CLIENT.getCode(), "%s %s");
        ctx.setValue("executionResult", "已有执行结果");
        return ctx;
    }
}
