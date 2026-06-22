package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.FieldState;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.Inspection;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.ProseState;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P0-B2b-O1：{@link SupervisionVerdictParser#inspect} provenance/交叉证据。
 * resolved 恒等于 parse()；本测试聚焦 field/prose 独立结果、candidate-source、fieldVsProse、unknownReason。
 */
public class SupervisionVerdictInspectTest {

    private static Inspection insp(String raw) {
        return SupervisionVerdictParser.inspect(raw);
    }

    @Test
    public void fieldValidProseMissing_fieldOnly() {
        Inspection i = insp("质量评估: 通过\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        assertEquals(FieldState.VALID, i.fieldState());
        assertEquals(Verdict.PASS, i.fieldVerdict());
        assertEquals(ProseState.NONE, i.proseState());
        assertEquals(Verdict.PASS, i.resolvedVerdict());
        assertEquals("new_field", SupervisionVerdictParser.candidateSource(i));
        assertEquals("field_only", SupervisionVerdictParser.fieldVsProse(i));
        assertEquals("none", i.unknownReason());
    }

    @Test
    public void fieldAndProseAgree() {
        Inspection i = insp("是否通过: PASS\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        assertEquals(FieldState.VALID, i.fieldState());
        assertEquals(ProseState.VALID, i.proseState());
        assertEquals(Verdict.PASS, i.proseVerdict());
        assertEquals("agree", SupervisionVerdictParser.fieldVsProse(i));
        assertEquals(Verdict.PASS, i.resolvedVerdict());
    }

    @Test
    public void fieldVsProseConflict_resolvedFollowsField() {
        // 关键：散文说 FAIL，但权威机器字段 PASS → resolved=PASS（field 权威），但对照标 conflict（enforce 时该 UNKNOWN→repair）
        Inspection i = insp("是否通过: FAIL\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        assertEquals(Verdict.PASS, i.fieldVerdict());
        assertEquals(Verdict.FAIL, i.proseVerdict());
        assertEquals("conflict", SupervisionVerdictParser.fieldVsProse(i));
        assertEquals("resolved 仍随权威 field（B2b-O1 不改控制流）", Verdict.PASS, i.resolvedVerdict());
    }

    @Test
    public void proseOnly_noTrailer() {
        Inspection i = insp("## 审查结果：OPTIMIZE（可优化）");
        assertEquals(FieldState.MISSING, i.fieldState());
        assertEquals(ProseState.VALID, i.proseState());
        assertEquals(Verdict.OPTIMIZE, i.proseVerdict());
        assertEquals("legacy_allowlist", SupervisionVerdictParser.candidateSource(i));
        assertEquals("prose_only", SupervisionVerdictParser.fieldVsProse(i));
        assertEquals(Verdict.OPTIMIZE, i.resolvedVerdict());
    }

    @Test
    public void bothNone_trailerMissing() {
        Inspection i = insp("一段普通分析正文，没有任何裁决词");
        assertEquals(FieldState.MISSING, i.fieldState());
        assertEquals(ProseState.NONE, i.proseState());
        assertEquals(Verdict.UNKNOWN, i.resolvedVerdict());
        assertEquals("none", SupervisionVerdictParser.candidateSource(i));
        assertEquals("both_none", SupervisionVerdictParser.fieldVsProse(i));
        assertEquals("trailer_missing", i.unknownReason());
    }

    @Test
    public void duplicateField_unknown() {
        Inspection i = insp("正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        assertEquals(FieldState.DUPLICATE, i.fieldState());
        assertEquals(Verdict.UNKNOWN, i.resolvedVerdict());
        assertEquals("duplicate", i.unknownReason());
    }

    @Test
    public void malformedField_unknown() {
        Inspection i = insp("正文\n<!-- AUTO_QUALITY_VERDICT: MAYBE -->");
        assertEquals(FieldState.INVALID, i.fieldState());
        assertEquals(Verdict.UNKNOWN, i.resolvedVerdict());
        assertEquals("malformed", i.unknownReason());
    }

    @Test
    public void unexpectedExtraField_unknown() {
        Inspection i = insp("正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->\n<!-- AUTO_EXTRA: x -->");
        assertEquals(FieldState.UNEXPECTED, i.fieldState());
        assertEquals(Verdict.UNKNOWN, i.resolvedVerdict());
        assertEquals("unexpected", i.unknownReason());
    }

    @Test
    public void requiredMissing_trailerPresentButKeyAbsent() {
        Inspection i = insp("正文\n<!-- AUTO_EXTRA: x -->");
        assertEquals(FieldState.REQUIRED_MISSING, i.fieldState());
        assertEquals(Verdict.UNKNOWN, i.resolvedVerdict());
        assertEquals("required_missing", i.unknownReason());
    }

    @Test
    public void proseConflict_isHardConflictWithDedicatedReason() {
        // 无机器契约 + 散文异值冲突：不能与真正 both-none 混为一谈。
        Inspection i = insp("审查结果：FAIL\n判定：PASS");
        assertEquals(FieldState.MISSING, i.fieldState());
        assertEquals(ProseState.CONFLICT, i.proseState());
        assertEquals(Verdict.UNKNOWN, i.resolvedVerdict());
        assertEquals("conflict", SupervisionVerdictParser.fieldVsProse(i));
        assertEquals("prose_conflict", i.unknownReason());
    }

    @Test
    public void validFieldWithInternallyConflictingProse_remainsVisibleAsConflict() {
        Inspection i = insp("审查结果：FAIL\n判定：PASS\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        assertEquals(FieldState.VALID, i.fieldState());
        assertEquals(ProseState.CONFLICT, i.proseState());
        assertEquals("conflict", SupervisionVerdictParser.fieldVsProse(i));
        // O1 仍保持 parse() 的 field-first resolved 语义；enforce 波次再把 conflict 路由到 repair。
        assertEquals(Verdict.PASS, i.resolvedVerdict());
    }
}
