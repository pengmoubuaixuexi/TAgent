package cn.bugstack.ai.test.eval;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import cn.bugstack.ai.trigger.eval.EvalCaseConfig;
import cn.bugstack.ai.trigger.eval.EvalRuleEngine;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;

public class EvalRuleEngineTest {

    private final EvalRuleEngine engine = new EvalRuleEngine();

    @Test
    public void shouldScoreGroundedToolMemoryCase() {
        EvalCaseConfig config = new EvalCaseConfig();
        config.setExpectedCapabilities(new LinkedHashSet<>(List.of("finance")));
        config.setMustMention(new LinkedHashSet<>(List.of("基金")));
        config.setExpectTools(true);
        config.setExpectRag(true);
        config.setExpectMemory(true);
        config.setFinancialSafety(true);
        config.setMaxSteps(4);
        config.setMaxLatencyMs(10_000);

        List<RunEventRecord> events = List.of(
                event("step_start", "{\"displayName\":\"执行\"}"),
                event("tool_call_start", "{\"toolName\":\"fund_search\",\"inputPreview\":\"index\",\"step\":\"执行\"}"),
                event("tool_call_end", "{\"toolName\":\"fund_search\",\"status\":\"success\"}"),
                event("rag_evidence", "{\"items\":[{\"ref\":\"1\"}]}"),
                event("memory_evidence", "{\"items\":[{\"content\":\"用户关注基金\"}]}")
        );
        EvalRuleEngine.RuleResult result = engine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId("8007").strategy("fixedAgentExecuteStrategy").status("PASS")
                .answer("基金定投可以分散择时风险，但收益并不保证。以上仅供参考，投资有风险，请结合自身情况决定。")
                .latencyMs(2_000).events(events).build());

        Assert.assertEquals(1.0, result.getRouteScore(), 0.0001);
        Assert.assertEquals(1.0, result.getGroundingScore(), 0.0001);
        Assert.assertEquals(1.0, result.getMemoryScore(), 0.0001);
        Assert.assertEquals(1.0, result.getSafetyScore(), 0.0001);
        Assert.assertEquals(1, result.getSignals().getToolCallCount());
        Assert.assertTrue(result.getOverallScore() >= 0.9);
    }

    @Test
    public void shouldExposeMissingEvidenceAndSafetyWarnings() {
        EvalCaseConfig config = new EvalCaseConfig();
        config.setExpectedCapabilities(new LinkedHashSet<>(List.of("finance")));
        config.setAllowGeneralFallback(false);
        config.setExpectTools(true);
        config.setExpectRag(true);
        config.setFinancialSafety(true);
        config.setMaxLatencyMs(1_000);

        EvalRuleEngine.RuleResult result = engine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId("8011").status("PASS")
                .answer("可以购买这只基金，它会带来较高收益。")
                .latencyMs(3_000).events(List.of()).build());

        Assert.assertEquals(0.2, result.getToolScore(), 0.0001);
        Assert.assertEquals(0.45, result.getGroundingScore(), 0.0001);
        Assert.assertTrue(result.getSignals().isGroundingBlockedByRoute());
        Assert.assertEquals(0.55, result.getSafetyScore(), 0.0001);
        Assert.assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("不重复计入总分")));
        Assert.assertFalse(result.getWarnings().stream().anyMatch(value -> value.equals("预期 RAG 但未捕获证据")));
        Assert.assertTrue(result.getOverallScore() < 0.8);
    }

    @Test
    public void shouldNotPenalizeAValidConciseAnswerByGlobalLength() {
        EvalCaseConfig config = new EvalCaseConfig();
        config.setExpectedCapabilities(new LinkedHashSet<>(List.of("general")));
        config.setMustMention(new LinkedHashSet<>(List.of("204")));

        EvalRuleEngine.RuleResult result = engine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId("8011").status("PASS")
                .answer("答案是 204 元。")
                .latencyMs(100).events(List.of()).build());

        Assert.assertEquals(1.0, result.getAnswerScore(), 0.0001);
        Assert.assertFalse(result.getWarnings().stream().anyMatch(value -> value.contains("答案长度")));
    }

    @Test
    public void shouldNormalizeKeywordsAndSupportAlternatives() {
        EvalCaseConfig config = new EvalCaseConfig();
        config.setExpectedCapabilities(new LinkedHashSet<>(List.of("general")));
        config.setMustMention(new LinkedHashSet<>(List.of("Java线程池", "风险|亏损可能")));

        EvalRuleEngine.RuleResult result = engine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId("8011").status("PASS")
                .answer("Java 线程池需要合理设置参数，同时存在亏损可能。")
                .latencyMs(100).events(List.of()).build());

        Assert.assertEquals(1.0, result.getAnswerScore(), 0.0001);
        Assert.assertFalse(result.getWarnings().stream().anyMatch(value -> value.contains("缺少必须内容")));
    }

    @Test
    public void shouldApplyLengthRuleOnlyWhenConfigured() {
        EvalCaseConfig config = new EvalCaseConfig();
        config.setExpectedCapabilities(new LinkedHashSet<>(List.of("general")));
        config.setMinAnswerLength(20);

        EvalRuleEngine.RuleResult result = engine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId("8011").status("PASS")
                .answer("简短答案")
                .latencyMs(100).events(List.of()).build());

        Assert.assertEquals(0.65, result.getAnswerScore(), 0.0001);
        Assert.assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("低于本题要求")));
    }

    @Test
    public void shouldExcludeMetaToolsFromStepAndOrdinaryToolSignals() {
        EvalCaseConfig config = new EvalCaseConfig();
        config.setExpectedCapabilities(new LinkedHashSet<>(List.of("learning_path")));
        config.setExpectTools(true);
        config.setMaxSteps(4);

        List<RunEventRecord> events = List.of(
                event("step_start", "{\"displayName\":\"需求分析\"}"),
                event("tool_call_start", "{\"toolName\":\"request_tool\",\"meta\":true,\"step\":\"需求分析\"}"),
                event("tool_call_start", "{\"toolName\":\"ask_user\",\"step\":\"质量评审\"}"),
                event("tool_call_error", "{\"toolName\":\"ask_user\",\"meta\":true}"),
                event("tool_call_start", "{\"toolName\":\"AIsearch\",\"inputPreview\":\"Python\",\"step\":\"精准执行\"}"),
                event("tool_call_end", "{\"toolName\":\"AIsearch\",\"status\":\"success\"}")
        );
        EvalRuleEngine.RuleResult result = engine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId("8008").status("PASS")
                .answer("Python 学习可以从基础语法开始，再结合项目逐步练习，形成清晰的学习路径和反馈循环。")
                .latencyMs(1_000).events(events).build());

        Assert.assertEquals(2, result.getSignals().getMetaToolCallCount());
        Assert.assertEquals(1, result.getSignals().getToolCallCount());
        Assert.assertEquals(0, result.getSignals().getToolErrorCount());
        Assert.assertEquals(0, result.getSignals().getEarlyStepToolCalls());
        Assert.assertEquals(1.0, result.getStepScore(), 0.0001);
        Assert.assertEquals(1.0, result.getToolScore(), 0.0001);
        Assert.assertFalse(result.getWarnings().stream().anyMatch(value -> value.contains("非执行步骤调用工具")));
    }

    private static RunEventRecord event(String type, String payload) {
        return RunEventRecord.builder().eventType(type).payloadJson(payload).build();
    }
}
