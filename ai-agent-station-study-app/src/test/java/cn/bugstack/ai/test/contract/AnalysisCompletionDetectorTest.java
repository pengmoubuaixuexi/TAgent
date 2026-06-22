package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector;
import cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.Signal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P0-B2a：{@link AnalysisCompletionDetector} candidate 解析（新字段优先 + legacy 精确 + 冲突→UNKNOWN）。
 */
public class AnalysisCompletionDetectorTest {

    // ---- 新机器字段 ----

    @Test
    public void newFieldsCompleted() {
        String raw = "分析正文\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->";
        assertEquals(Signal.COMPLETED, AnalysisCompletionDetector.parse(raw));
    }

    @Test
    public void newFieldsContinue() {
        String raw = "正文\n<!-- AUTO_COMPLETION_PROGRESS: 50% -->\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->";
        assertEquals(Signal.CONTINUE, AnalysisCompletionDetector.parse(raw));
    }

    @Test
    public void newFieldsConflictUnknown() {
        // status=COMPLETED 但 progress=50 → 冲突
        String raw = "正文\n<!-- AUTO_COMPLETION_PROGRESS: 50% -->\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->";
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(raw));
    }

    @Test
    public void newOnlyStatusCompleted() {
        assertEquals(Signal.COMPLETED, AnalysisCompletionDetector.parse("正文\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->"));
    }

    @Test
    public void invalidNewDoesNotFallBackToLegacy() {
        String raw = "任务状态: COMPLETED\n<!-- AUTO_COMPLETION_STATUS: MAYBE -->";
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(raw));
    }

    @Test
    public void oneInvalidNewFieldMakesWholeContractUnknown() {
        String raw = "正文\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->\n<!-- AUTO_COMPLETION_STATUS: MAYBE -->";
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(raw));
    }

    @Test
    public void duplicateNewFieldIsUnknown() {
        String raw = "正文\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->\n" +
                "<!-- AUTO_COMPLETION_STATUS: COMPLETED -->\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->";
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(raw));
    }

    @Test
    public void expectedFieldsPlusUnexpectedAutoFieldIsUnknown() {
        String raw = "正文\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->\n" +
                "<!-- AUTO_COMPLETION_STATUS: COMPLETED -->\n<!-- AUTO_EXTRA: anything -->";
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(raw));
    }

    // ---- legacy 精确 ----

    @Test
    public void legacyStatusCompleted() {
        assertEquals(Signal.COMPLETED, AnalysisCompletionDetector.parse("任务状态分析: 完成\n任务状态: COMPLETED"));
    }

    @Test
    public void legacyHundredPercent() {
        assertEquals(Signal.COMPLETED, AnalysisCompletionDetector.parse("完成度评估: 100%"));
    }

    @Test
    public void legacyContinue() {
        assertEquals(Signal.CONTINUE, AnalysisCompletionDetector.parse("任务状态: CONTINUE\n完成度评估: 80%"));
    }

    /** 1000% 不得误判为完成（旧 contains 也不命中 "完成度评估: 100%"）。 */
    @Test
    public void thousandPercentNotCompleted() {
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse("完成度评估: 1000%"));
    }

    @Test
    public void hundredPercentWithTrailingProseIsNotCompleted() {
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse("完成度评估: 100%以外仍有工作"));
    }

    @Test
    public void fullWidthPercentAndParentheticalAreSupported() {
        assertEquals(Signal.CONTINUE, AnalysisCompletionDetector.parse("完成度评估：50％（分析完成，待执行）"));
    }

    @Test
    public void completionMarkerInsideFencedExampleIsIgnored() {
        assertEquals(Signal.UNKNOWN,
                AnalysisCompletionDetector.parse("```text\n任务状态: COMPLETED\n```\n这里只是在展示格式"));
    }

    @Test
    public void legacyConflictUnknown() {
        // 两行行首锚定，异值 → UNKNOWN
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse("任务状态: COMPLETED\n任务状态: CONTINUE"));
    }

    // ---- 边界 ----

    @Test
    public void emptyAndNullUnknown() {
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(""));
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse(null));
        assertEquals(Signal.UNKNOWN, AnalysisCompletionDetector.parse("一段没有完成标记的分析正文"));
    }
}
