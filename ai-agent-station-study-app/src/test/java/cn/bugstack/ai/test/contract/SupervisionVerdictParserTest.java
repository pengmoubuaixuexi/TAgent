package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P0-B2a：{@link SupervisionVerdictParser} candidate 解析（新字段优先 + legacy allowlist + 同值折叠/异值冲突）。
 * 覆盖 v3.md §37.6 要求拆开的真实样本形状。
 */
public class SupervisionVerdictParserTest {

    // ---- 新机器字段优先 ----

    @Test
    public void newFieldPass() {
        assertEquals(Verdict.PASS, SupervisionVerdictParser.parse("任意正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->"));
    }

    @Test
    public void newFieldFail() {
        assertEquals(Verdict.FAIL, SupervisionVerdictParser.parse("正文\n<!-- AUTO_QUALITY_VERDICT: FAIL -->"));
    }

    @Test
    public void newFieldBeatsLegacy() {
        // 旧中文 marker 是 FAIL，但新权威字段是 PASS → candidate=PASS
        String raw = "是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: PASS -->";
        assertEquals(Verdict.PASS, SupervisionVerdictParser.parse(raw));
    }

    @Test
    public void invalidNewFieldDoesNotFallBackToLegacy() {
        String raw = "是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: MAYBE -->";
        assertEquals("权威新字段存在但非法时必须 UNKNOWN", Verdict.UNKNOWN, SupervisionVerdictParser.parse(raw));
    }

    @Test
    public void invalidNewFieldNoLegacyIsUnknown() {
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse("一段没有裁决的正文\n<!-- AUTO_QUALITY_VERDICT: MAYBE -->"));
    }

    @Test
    public void duplicateNewFieldIsUnknownEvenWhenValuesMatch() {
        String raw = "正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->\n<!-- AUTO_QUALITY_VERDICT: PASS -->";
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse(raw));
    }

    @Test
    public void wrongAutoFieldDoesNotFallBackToLegacy() {
        String raw = "是否通过: PASS\n<!-- AUTO_WRONG_FIELD: PASS -->";
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse(raw));
    }

    @Test
    public void expectedFieldPlusUnexpectedAutoFieldIsUnknown() {
        String raw = "正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->\n<!-- AUTO_EXTRA: anything -->";
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse(raw));
    }

    // ---- legacy allowlist ----

    @Test
    public void legacyAnchoredFail() {
        assertEquals(Verdict.FAIL, SupervisionVerdictParser.parse("质量评分: 30\n是否通过: FAIL"));
    }

    @Test
    public void legacyReviewResultMarkdown() {
        assertEquals(Verdict.FAIL, SupervisionVerdictParser.parse("## 审查结果：FAIL\n详情..."));
    }

    @Test
    public void legacyJudgementBold() {
        assertEquals(Verdict.OPTIMIZE, SupervisionVerdictParser.parse("**判定：OPTIMIZE** ✅"));
    }

    @Test
    public void legacyBareStandaloneLine() {
        assertEquals(Verdict.FAIL, SupervisionVerdictParser.parse("评审结论：\nFAIL"));
    }

    @Test
    public void concludeColonFailNotInAllowlistIsUnknown() {
        // "结论：FAIL" 不在首版 allowlist
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse("质量评估: 严重缺陷\n结论：FAIL"));
    }

    @Test
    public void freeTextNotUpgraded() {
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse("如果故判定为 FAIL 则需重做，但此处只是说明"));
    }

    @Test
    public void anchoredLineWithContradictoryTrailingTextIsUnknown() {
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse("判定: FAIL 或 PASS"));
    }

    @Test
    public void verdictInsideFencedExampleIsIgnored() {
        assertEquals(Verdict.UNKNOWN,
                SupervisionVerdictParser.parse("```text\n是否通过: FAIL\n```\n这里只是在展示格式"));
    }

    // ---- 同值折叠 / 异值冲突 ----

    @Test
    public void sameValueRepeatsFold() {
        String raw = "## 审查结果：OPTIMIZE（可优化）\n**判定：OPTIMIZE** ✅";
        assertEquals("同值重复折叠为一个", Verdict.OPTIMIZE, SupervisionVerdictParser.parse(raw));
    }

    @Test
    public void differentValueConflictIsUnknown() {
        // 两行行首锚定裁决，异值 → UNKNOWN（与 B1 旧 contains "FAIL 优先" 行为不同，正是 shadow 要测的分歧）
        String raw = "是否通过: OPTIMIZE\n是否通过: FAIL";
        assertEquals("异值冲突 → UNKNOWN", Verdict.UNKNOWN, SupervisionVerdictParser.parse(raw));
    }

    // ---- 边界 ----

    @Test
    public void emptyAndNullUnknown() {
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse(""));
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse(null));
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.parse("质量评估: 正在分析，发现"));
    }
}
