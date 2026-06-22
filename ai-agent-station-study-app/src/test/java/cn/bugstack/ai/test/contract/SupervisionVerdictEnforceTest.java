package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.FieldState;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.Inspection;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.ProseState;
import cn.bugstack.ai.domain.agent.service.execute.common.SupervisionVerdictParser.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P0-B2b-Step3：{@link SupervisionVerdictParser#resolveForEnforcement} 是 enforce 路由的唯一裁决函数。
 * 规则（§51.5/§53.1）：仅 field VALID + (prose NONE 或 prose VALID 同值) → 接受 field；其余一律 UNKNOWN→repair。
 */
public class SupervisionVerdictEnforceTest {

    private static Inspection insp(Verdict fieldV, FieldState fs, Verdict proseV, ProseState ps) {
        // resolveForEnforcement 只读 fieldState/fieldVerdict/proseState/proseVerdict；后两参占位
        return new Inspection(fieldV, fs, proseV, ps, Verdict.UNKNOWN, "none");
    }

    private static Verdict resolve(Inspection i) {
        return SupervisionVerdictParser.resolveForEnforcement(i);
    }

    @Test
    public void fieldValidProseNone_acceptsField() {
        assertEquals(Verdict.PASS, resolve(insp(Verdict.PASS, FieldState.VALID, Verdict.UNKNOWN, ProseState.NONE)));
        assertEquals(Verdict.FAIL, resolve(insp(Verdict.FAIL, FieldState.VALID, Verdict.UNKNOWN, ProseState.NONE)));
        assertEquals(Verdict.OPTIMIZE, resolve(insp(Verdict.OPTIMIZE, FieldState.VALID, Verdict.UNKNOWN, ProseState.NONE)));
    }

    @Test
    public void fieldValidProseValidSameValue_acceptsField() {
        assertEquals(Verdict.FAIL, resolve(insp(Verdict.FAIL, FieldState.VALID, Verdict.FAIL, ProseState.VALID)));
    }

    @Test
    public void fieldValidProseValidDifferent_unknown() {
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.PASS, FieldState.VALID, Verdict.FAIL, ProseState.VALID)));
    }

    @Test
    public void fieldValidProseConflict_unknown() {
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.PASS, FieldState.VALID, Verdict.UNKNOWN, ProseState.CONFLICT)));
    }

    @Test
    public void proseOnly_unknown() {
        // 当前真实样本无 prose_only；规则 3：field 非 VALID 即使 prose 唯一 valid 也先 repair
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.UNKNOWN, FieldState.MISSING, Verdict.OPTIMIZE, ProseState.VALID)));
    }

    @Test
    public void bothNone_unknown() {
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.UNKNOWN, FieldState.MISSING, Verdict.UNKNOWN, ProseState.NONE)));
    }

    @Test
    public void fieldInvalidDuplicateUnexpectedRequiredMissing_unknown() {
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.UNKNOWN, FieldState.INVALID, Verdict.UNKNOWN, ProseState.NONE)));
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.UNKNOWN, FieldState.DUPLICATE, Verdict.UNKNOWN, ProseState.NONE)));
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.UNKNOWN, FieldState.UNEXPECTED, Verdict.UNKNOWN, ProseState.NONE)));
        assertEquals(Verdict.UNKNOWN, resolve(insp(Verdict.UNKNOWN, FieldState.REQUIRED_MISSING, Verdict.UNKNOWN, ProseState.NONE)));
    }

    @Test
    public void nullInspection_unknown() {
        assertEquals(Verdict.UNKNOWN, SupervisionVerdictParser.resolveForEnforcement(null));
    }

    @Test
    public void enforcementUnknownReason_distinguishesConflictsAndMissingField() {
        assertEquals("field_prose_conflict", SupervisionVerdictParser.enforcementUnknownReason(
                insp(Verdict.PASS, FieldState.VALID, Verdict.FAIL, ProseState.VALID)));
        assertEquals("field_prose_conflict", SupervisionVerdictParser.enforcementUnknownReason(
                insp(Verdict.PASS, FieldState.VALID, Verdict.UNKNOWN, ProseState.CONFLICT)));
        assertEquals("prose_conflict", SupervisionVerdictParser.enforcementUnknownReason(
                insp(Verdict.UNKNOWN, FieldState.MISSING, Verdict.UNKNOWN, ProseState.CONFLICT)));
        assertEquals("trailer_missing", SupervisionVerdictParser.enforcementUnknownReason(
                insp(Verdict.UNKNOWN, FieldState.MISSING, Verdict.OPTIMIZE, ProseState.VALID)));
        assertEquals("none", SupervisionVerdictParser.enforcementUnknownReason(
                insp(Verdict.PASS, FieldState.VALID, Verdict.UNKNOWN, ProseState.NONE)));
    }
}
