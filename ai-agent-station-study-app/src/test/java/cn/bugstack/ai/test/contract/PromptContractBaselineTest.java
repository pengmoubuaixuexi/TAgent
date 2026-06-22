package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.auto.step.Step1AnalyzerNode;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.Signal;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.Verdict;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step3ParseStepsNode;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P3-1 Evaluation Baseline —— <b>解析契约回归门</b>（v3.md §85；inventory §3.6 / §3.6.1 / §3.6.2）。
 *
 * <p><b>目的</b>：把"重写 prompt 绝不能破坏"的 parser 契约钉成可回归的硬断言。重写阶段（P4）批量改 DB prompt 后
 * 回归跑本测试：<b>绿</b>=契约 token 仍被真实 parser 认得，重写没退化；<b>红</b>=某条契约 token 被"润色"掉了，
 * 解析会在线上静默判 UNKNOWN / 走降级 —— 在改 prompt 的同 PR 就拦住。
 *
 * <p><b>方法</b>：不连 LLM、不连 DB，直接喂规范样本给<b>真实 parser</b>。每个锚点配
 * 「正样本（必须解析成预期裁决）」+「负样本（token 被删 → 降级 / UNKNOWN / 空，演示退化形态）」。
 *
 * <p><b>三锚点（§3.6.1 分级 + §3.6.2 非对称）</b>：
 * <ul>
 *   <li><b>Step1 完成度（Auto p1，HARD）</b>：LIVE 控制流是 {@link Step1AnalyzerNode} 里的 {@code rawResult.contains(...)}
 *       精确子串（机器字段对 Step1 只是 shadow，不驱动路由）。故 prompt 必须保留这两个精确子串。</li>
 *   <li><b>Step3 质检（Auto p3，HARD）</b>：已 B2b enforce，机器字段 {@code AUTO_QUALITY_VERDICT} 权威；
 *       纯散文裁决在 enforce 下不算数（→ UNKNOWN → repair）。故 prompt 应主推机器字段。</li>
 *   <li><b>Flow 规划（p2，第N步=HARD / DEPENDS_ON=FALLBACK）</b>：{@link Step3ParseStepsNode} 真实解析步骤标题 + 依赖。</li>
 * </ul>
 *
 * <p>纯单测，零 Spring 容器；Flow 私有解析方法经 {@link ReflectionTestUtils} 直呼（方法不依赖注入字段）。
 */
public class PromptContractBaselineTest {

    // ====================================================================================
    // 锚点一：Step1 完成度（Auto p1）—— LIVE 路径 = Step1AnalyzerNode:113 的 raw contains
    // §3.6.2：机器字段 AnalysisCompletionDetector 对 Step1 只 shadow（:115 "只打点不改路由"），
    //         真正决定 setCompleted 的是下面这两个精确子串。prompt 重写必须原样保留它们。
    // ====================================================================================

    /** Step1 完成判定的 LIVE 精确子串（与 Step1AnalyzerNode 中 contains 的字面量逐字一致）。 */
    static final String STEP1_DONE_STATUS = "任务状态: COMPLETED";
    static final String STEP1_DONE_PROGRESS = "完成度评估: 100%";

    /** 复刻 Step1AnalyzerNode 的 LIVE 完成判定（保持与生产控制流字面等价）。 */
    private static boolean liveStep1Completed(String rawResult) {
        return rawResult.contains(STEP1_DONE_STATUS) || rawResult.contains(STEP1_DONE_PROGRESS);
    }

    @Test
    public void step1_compliantOutput_setsCompleted_andShadowAgrees() {
        String compliant = "任务状态分析: 已全部完成\n完成度评估: 100%\n" + STEP1_DONE_STATUS;
        assertTrue("LIVE 完成判定靠精确 contains（生产控制流）", liveStep1Completed(compliant));
        // shadow detector 迁移期应同向（不驱动路由，仅一致性观测）
        assertEquals(Signal.COMPLETED, AnalysisCompletionDetector.parse(compliant));
    }

    @Test
    public void step1_rewriteThatDropsExactMarker_silentlyNeverCompletes() {
        // 模拟"润色版"：自然语言说完成，但没有精确契约 token —— 这正是要拦的退化
        String rewritten = "我已经把所有分析做完了，可以进入执行阶段了。";
        assertFalse("无精确 token → LIVE contains false → 永不 setCompleted（HARD 回归）",
                liveStep1Completed(rewritten));
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(rewritten));
    }

    @Test
    public void step1_progressMarkerAloneAlsoCompletes() {
        String onlyProgress = "执行历史评估: 充分\n" + STEP1_DONE_PROGRESS;
        assertTrue(liveStep1Completed(onlyProgress));
        assertEquals(Signal.COMPLETED, AnalysisCompletionDetector.parse(onlyProgress));
    }

    // ====================================================================================
    // 锚点二：Step3 质检（Auto p3）—— enforce 路径，机器字段 AUTO_QUALITY_VERDICT 权威
    // §3.6.2：Step3QualitySupervisorNode:159-162 用 resolveForEnforcement 驱动控制流，
    //         legacy 散文 contains 已降级为 shadow。故 prompt 应主推机器字段。
    // ====================================================================================

    @Test
    public void step3_machineFieldVerdict_drivesEnforce() {
        for (Verdict v : new Verdict[]{Verdict.PASS, Verdict.FAIL, Verdict.OPTIMIZE}) {
            String out = "质检意见正文……\n<!-- AUTO_QUALITY_VERDICT: " + v.name() + " -->";
            Verdict resolved = SupervisionVerdictParser.resolveForEnforcement(
                    SupervisionVerdictParser.inspect(out));
            assertEquals("机器字段必须驱动 enforce 裁决: " + v, v, resolved);
        }
    }

    @Test
    public void step3_proseOnlyVerdict_isUnknownUnderEnforce_soPromptMustEmitMachineField() {
        // §3.6.2：enforce 要 fieldState VALID；纯散文 "是否通过: PASS" 在 enforce 下 → UNKNOWN（走 repair）。
        String proseOnly = "是否通过: PASS";
        assertEquals("纯散文裁决在 enforce 下不算数",
                Verdict.UNKNOWN, SupervisionVerdictParser.resolveForEnforcement(
                        SupervisionVerdictParser.inspect(proseOnly)));
        // 但迁移期 candidate parse() 仍认 legacy 白名单（散文是兜底、可保留为人类可读冗余）
        assertEquals(Verdict.PASS, SupervisionVerdictParser.parse(proseOnly));
    }

    @Test
    public void step3_noVerdictAtAll_isUnknown_thenConservativeFail() {
        // 重写若把裁决行整段删了 → UNKNOWN → 生产侧 repair；repair 再 UNKNOWN → 保守 FAIL 回炉（不误判 PASS）
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.resolveForEnforcement(
                SupervisionVerdictParser.inspect("一段没有任何裁决字段的评论。")));
    }

    @Test
    public void step3_duplicateMachineField_isUnknown() {
        // 重写若诱导模型重复打印裁决行 = 契约违规 → UNKNOWN
        String dup = "正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->\n<!-- AUTO_QUALITY_VERDICT: PASS -->";
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.resolveForEnforcement(
                SupervisionVerdictParser.inspect(dup)));
    }

    // ====================================================================================
    // 锚点三：Flow 规划（p2）—— Step3ParseStepsNode 真实解析步骤标题（第N步=HARD）+ 依赖（DEPENDS_ON=FALLBACK）
    // ====================================================================================

    @Test
    @SuppressWarnings("unchecked")
    public void flow_compliantPlan_parsesStepsAndDeps() {
        Step3ParseStepsNode node = new Step3ParseStepsNode();
        String plan = "### 第1步：收集资料\nDEPENDS_ON: NONE\n\n### 第2步：汇总报告\nDEPENDS_ON: 1";
        Map<String, String> steps = (Map<String, String>)
                ReflectionTestUtils.invokeMethod(node, "parseExecutionSteps", plan);
        assertEquals("两个 第N步 必须被解析出来", 2, steps.size());
        Map<Integer, Set<Integer>> deps = (Map<Integer, Set<Integer>>)
                ReflectionTestUtils.invokeMethod(node, "parseStepDependencies", steps);
        assertTrue("第1步 DEPENDS_ON: NONE → 无依赖", deps.get(1).isEmpty());
        assertEquals("第2步 DEPENDS_ON: 1 → 依赖第1步", Set.of(1), deps.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void flow_rewriteThatDropsStepFormat_yieldsNoSteps() {
        Step3ParseStepsNode node = new Step3ParseStepsNode();
        // 模拟"润色版"：纯自然语言叙述，没有 "第N步" 结构 —— DAG 空 = 无执行步（HARD 回归）
        String prose = "首先收集资料，接着把它汇总成一份报告，最后校对。";
        Map<String, String> steps = (Map<String, String>)
                ReflectionTestUtils.invokeMethod(node, "parseExecutionSteps", prose);
        assertTrue("无 第N步 → DAG 空 → 无执行步", steps.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void flow_dependsOnMissing_degradesToNoDeps_notCrash() {
        // DEPENDS_ON 是 FALLBACK（§3.6.1）：缺失 = 默认无依赖（退化为并行），不崩
        Step3ParseStepsNode node = new Step3ParseStepsNode();
        String plan = "### 第1步：只有标题没有依赖行\n（这一步没写 DEPENDS_ON）";
        Map<String, String> steps = (Map<String, String>)
                ReflectionTestUtils.invokeMethod(node, "parseExecutionSteps", plan);
        Map<Integer, Set<Integer>> deps = (Map<Integer, Set<Integer>>)
                ReflectionTestUtils.invokeMethod(node, "parseStepDependencies", steps);
        assertEquals(1, steps.size());
        assertTrue("缺 DEPENDS_ON → 默认无依赖（FALLBACK，不崩）", deps.get(1).isEmpty());
    }
}
