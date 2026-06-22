package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.FieldState;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.Inspection;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.ProseState;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.Signal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** P0-B2b-O1：{@link AnalysisCompletionDetector#inspect} provenance（含 status_progress_conflict）。 */
public class AnalysisCompletionInspectTest {

    private static Inspection insp(String raw) {
        return AnalysisCompletionDetector.inspect(raw);
    }

    @Test
    public void fieldValidCompleted_fieldOnly() {
        Inspection i = insp("分析正文\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->");
        assertEquals(FieldState.VALID, i.fieldState());
        assertEquals(Signal.COMPLETED, i.fieldSignal());
        assertEquals(Signal.COMPLETED, i.resolvedSignal());
        assertEquals("new_field", AnalysisCompletionDetector.candidateSource(i));
        assertEquals("field_only", AnalysisCompletionDetector.fieldVsProse(i));
        assertEquals("none", i.unknownReason());
    }

    @Test
    public void statusProgressConflict() {
        Inspection i = insp("正文\n<!-- AUTO_COMPLETION_PROGRESS: 50% -->\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->");
        assertEquals(FieldState.FIELD_CONFLICT, i.fieldState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("conflict", AnalysisCompletionDetector.fieldVsProse(i));
        assertEquals("status_progress_conflict", i.unknownReason());
    }

    @Test
    public void proseOnly_noTrailer() {
        Inspection i = insp("任务状态: COMPLETED");
        assertEquals(FieldState.MISSING, i.fieldState());
        assertEquals(ProseState.VALID, i.proseState());
        assertEquals(Signal.COMPLETED, i.proseSignal());
        assertEquals("legacy_allowlist", AnalysisCompletionDetector.candidateSource(i));
        assertEquals("prose_only", AnalysisCompletionDetector.fieldVsProse(i));
        assertEquals(Signal.COMPLETED, i.resolvedSignal());
    }

    @Test
    public void bothNone_trailerMissing() {
        Inspection i = insp("一段没有完成标记的分析正文");
        assertEquals(FieldState.MISSING, i.fieldState());
        assertEquals(ProseState.NONE, i.proseState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("both_none", AnalysisCompletionDetector.fieldVsProse(i));
        assertEquals("trailer_missing", i.unknownReason());
    }

    @Test
    public void duplicateField_unknown() {
        Inspection i = insp("正文\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->");
        assertEquals(FieldState.DUPLICATE, i.fieldState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("duplicate", i.unknownReason());
    }

    @Test
    public void malformedField_unknown() {
        Inspection i = insp("正文\n<!-- AUTO_COMPLETION_STATUS: MAYBE -->");
        assertEquals(FieldState.INVALID, i.fieldState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("malformed", i.unknownReason());
    }

    @Test
    public void requiredMissing_trailerPresentButKeysAbsent() {
        Inspection i = insp("正文\n<!-- AUTO_EXTRA: x -->");
        assertEquals(FieldState.REQUIRED_MISSING, i.fieldState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("required_missing", i.unknownReason());
    }

    @Test
    public void unexpectedExtraField_unknown() {
        Inspection i = insp("正文\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->\n<!-- AUTO_EXTRA: x -->");
        assertEquals(FieldState.UNEXPECTED, i.fieldState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("unexpected", i.unknownReason());
    }

    @Test
    public void fieldContinueProseCompletedConflictVisible() {
        // 机器字段 CONTINUE，散文 任务状态: COMPLETED → 对照 conflict；resolved 随权威 field=CONTINUE
        Inspection i = insp("任务状态: COMPLETED\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->");
        assertEquals(FieldState.VALID, i.fieldState());
        assertEquals(Signal.CONTINUE, i.fieldSignal());
        assertEquals(Signal.COMPLETED, i.proseSignal());
        assertEquals("conflict", AnalysisCompletionDetector.fieldVsProse(i));
        assertEquals(Signal.CONTINUE, i.resolvedSignal());
    }

    @Test
    public void proseInternalConflict_isHardConflictWithDedicatedReason() {
        Inspection i = insp("任务状态: COMPLETED\n完成度评估: 50%");
        assertEquals(FieldState.MISSING, i.fieldState());
        assertEquals(ProseState.CONFLICT, i.proseState());
        assertEquals(Signal.UNKNOWN, i.resolvedSignal());
        assertEquals("conflict", AnalysisCompletionDetector.fieldVsProse(i));
        assertEquals("prose_conflict", i.unknownReason());
    }
}
