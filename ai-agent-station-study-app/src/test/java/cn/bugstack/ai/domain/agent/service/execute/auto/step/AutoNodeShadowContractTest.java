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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P0-B2a shadow 行为测试（B2a 新增；不翻转 B1 AS-IS 断言）：
 * <ul>
 *   <li>真实 prompt 末尾含 shadow 尾注（Node seam 捕获 suffix）；</li>
 *   <li>businessResult（进 DynamicContext）已剥离 trailer，旧控制流仍正确触发；</li>
 *   <li>shadow 指标被记录（反射注入 {@link AutoAgentMetrics}）。</li>
 * </ul>
 */
public class AutoNodeShadowContractTest {

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

    private static void injectMetrics(Object seamNode, Class<?> nodeClass, AutoAgentMetrics metrics) throws Exception {
        Field f = nodeClass.getDeclaredField("autoAgentMetrics");
        f.setAccessible(true);
        f.set(seamNode, metrics);
    }

    private static double total(SimpleMeterRegistry reg, String name) {
        return reg.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    // ---- 尾注进入真实 prompt ----

    @Test
    public void step1PromptContainsShadowTrailer() throws Exception {
        var node = new AutoNodeTestSeams.Step1();
        node.result("任务状态: CONTINUE");
        node.run(request(), context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s"));
        String prompt = node.capturedPrompt();
        assertTrue("Step1 prompt 应含完成度机器字段", prompt.contains("AUTO_COMPLETION_PROGRESS"));
        assertTrue("Step1 prompt 应含完成状态机器字段", prompt.contains("AUTO_COMPLETION_STATUS"));
        assertTrue("提示必须要求保留 HTML 注释定界符", prompt.contains("必须保留 <!-- 和 -->"));
        assertFalse("不得自相矛盾地要求不输出尖括号", prompt.contains("不要输出尖括号"));
    }

    @Test
    public void step3PromptContainsShadowTrailer() throws Exception {
        var node = new AutoNodeTestSeams.Step3();
        node.result("是否通过: FAIL");
        node.run(request(), step3Context());
        assertTrue("Step3 prompt 应含裁决机器字段", node.capturedPrompt().contains("AUTO_QUALITY_VERDICT"));
        assertTrue(node.capturedPrompt().contains("必须保留 <!-- 和 -->"));
        assertFalse(node.capturedPrompt().contains("不要输出尖括号"));
    }

    // ---- businessResult 剥离 trailer，旧控制流仍正确 ----

    @Test
    public void step1BusinessResultStripsTrailerAndOldLogicFires() throws Exception {
        var ctx = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        var node = new AutoNodeTestSeams.Step1();
        node.result("任务状态: COMPLETED\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->");
        node.run(request(), ctx);
        assertTrue("旧完成分支仍在 businessResult 上触发", ctx.isCompleted());
        String stored = (String) ctx.getValue("analysisResult");
        assertFalse("存入 DynamicContext 的 analysisResult 不含 trailer", stored.contains("<!--"));
        assertFalse(stored.contains("AUTO_COMPLETION_STATUS"));
        assertEquals("Working Memory 镜像必须使用 businessResult", stored, node.mirroredValue());
        assertEquals("thinking 完整内容必须使用 businessResult", stored, node.thinkingContent());
        assertTrue("所有 Step1 SSE 内容都不得含 trailer",
                node.emitted().stream().noneMatch(e -> e.getContent() != null && e.getContent().contains("AUTO_COMPLETION_")));
    }

    @Test
    public void step3BusinessResultStripsTrailerAndOldLogicFires() throws Exception {
        var ctx = step3Context();
        var node = new AutoNodeTestSeams.Step3();
        node.result("质量评分: 30\n是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: FAIL -->");
        node.run(request(), ctx);
        assertFalse("旧 FAIL 分支仍触发：不完成", ctx.isCompleted());
        assertTrue(ctx.getCurrentTask().contains("重新执行"));
        String stored = (String) ctx.getValue("supervisionResult");
        assertFalse("存入 DynamicContext 的 supervisionResult 不含 trailer", stored.contains("<!--"));
        assertFalse(stored.contains("AUTO_QUALITY_VERDICT"));
        assertEquals("Working Memory 镜像必须使用 businessResult", stored, node.mirroredValue());
        assertFalse("执行历史不得含 shadow trailer", ctx.getExecutionHistory().toString().contains("AUTO_QUALITY_VERDICT"));
        assertTrue("所有 Step3 SSE 内容都不得含 trailer",
                node.emitted().stream().noneMatch(e -> e.getContent() != null && e.getContent().contains("AUTO_QUALITY_VERDICT")));
    }

    @Test
    public void step1LegacyControlReadsRawEvenWhenMalformedTrailerIsStripped() throws Exception {
        var ctx = context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s");
        var node = new AutoNodeTestSeams.Step1();
        node.result("正文\n<!-- AUTO_EXTRA: 任务状态: COMPLETED -->");
        node.run(request(), ctx);
        assertTrue("改造前 raw contains 会完成，shadow 改造必须保持", ctx.isCompleted());
        assertFalse(((String) ctx.getValue("analysisResult")).contains("AUTO_EXTRA"));
    }

    @Test
    public void step3LegacyControlReadsRawEvenWhenMalformedTrailerIsStripped() throws Exception {
        var ctx = step3Context();
        var node = new AutoNodeTestSeams.Step3();
        node.result("正文\n<!-- AUTO_EXTRA: 是否通过: FAIL -->");
        node.run(request(), ctx);
        assertFalse("改造前 raw contains 会进入 FAIL，shadow 改造必须保持", ctx.isCompleted());
        assertTrue(ctx.getCurrentTask().contains("重新执行"));
        assertFalse(((String) ctx.getValue("supervisionResult")).contains("AUTO_EXTRA"));
    }

    // ---- shadow 指标记录 ----

    @Test
    public void step3ShadowMetricsRecorded() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var node = new AutoNodeTestSeams.Step3();
        injectMetrics(node, Step3QualitySupervisorNode.class, new AutoAgentMetrics(reg));
        node.result("是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: FAIL -->");
        node.run(request(), step3Context());
        assertTrue("supervision.parse 应记录", total(reg, "agent.auto.supervision.parse") >= 1.0);
        assertTrue("contract.shadow 应记录", total(reg, "agent.auto.contract.shadow") >= 1.0);
        assertEquals(1.0, reg.get("agent.auto.supervision.parse")
                .tags("phase", "primary", "verdict", "fail").counter().count(), 0.0);
        assertEquals(1.0, reg.get("agent.auto.contract.shadow")
                .tags("stage", "step3", "legacy", "fail", "candidate", "fail").counter().count(), 0.0);
    }

    @Test
    public void step1ShadowMetricsRecorded() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var node = new AutoNodeTestSeams.Step1();
        injectMetrics(node, Step1AnalyzerNode.class, new AutoAgentMetrics(reg));
        node.result("任务状态: CONTINUE\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->\n<!-- AUTO_COMPLETION_PROGRESS: 40% -->");
        node.run(request(), context(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(), "%s %d %d %s %s"));
        assertTrue("analysis.completion 应记录", total(reg, "agent.auto.analysis.completion") >= 1.0);
        assertTrue("contract.shadow 应记录", total(reg, "agent.auto.contract.shadow") >= 1.0);
        assertEquals(1.0, reg.get("agent.auto.analysis.completion")
                .tag("signal", "continue").counter().count(), 0.0);
        assertEquals(1.0, reg.get("agent.auto.contract.shadow")
                .tags("stage", "step1", "legacy", "continue", "candidate", "continue").counter().count(), 0.0);
    }
}
