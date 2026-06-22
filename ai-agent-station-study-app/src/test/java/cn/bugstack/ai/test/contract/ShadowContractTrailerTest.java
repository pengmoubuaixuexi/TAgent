package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.ShadowContractTrailer;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P0-B2a：{@link ShadowContractTrailer} 纯函数正/负例。
 * 重点验证 v3.md §43 约束 1（strip 与 extract 有效性边界分离）。
 */
public class ShadowContractTrailerTest {

    // ---- strip 正例 ----

    @Test
    public void stripsTailVerdictTrailer() {
        String raw = "质量评估: 通过\n是否通过: PASS\n<!-- AUTO_QUALITY_VERDICT: PASS -->";
        assertEquals("质量评估: 通过\n是否通过: PASS", ShadowContractTrailer.strip(raw));
    }

    @Test
    public void stripsTwoTailCompletionTrailers() {
        String raw = "分析正文\n<!-- AUTO_COMPLETION_PROGRESS: 50% -->\n<!-- AUTO_COMPLETION_STATUS: CONTINUE -->";
        assertEquals("分析正文", ShadowContractTrailer.strip(raw));
    }

    /** §43 约束 1：保留命名空间的尾部 trailer 即使值非法（占位符未替换）也要剥离。 */
    @Test
    public void stripsTailTrailerEvenWhenValueInvalid() {
        String raw = "正文\n<!-- AUTO_QUALITY_VERDICT: VERDICT -->";
        assertEquals("正文", ShadowContractTrailer.strip(raw));
    }

    @Test
    public void stripsWithTrailingBlankLines() {
        String raw = "正文\n<!-- AUTO_QUALITY_VERDICT: FAIL -->\n\n";
        assertEquals("正文", ShadowContractTrailer.strip(raw));
    }

    // ---- strip 负例（绝不误删）----

    @Test
    public void doesNotStripNonTailTrailer() {
        String raw = "<!-- AUTO_QUALITY_VERDICT: PASS -->\n后面还有正文内容";
        assertEquals("非尾部 trailer 不剥离", raw, ShadowContractTrailer.strip(raw));
    }

    @Test
    public void doesNotStripInProseMention() {
        String raw = "正文里提到 AUTO_QUALITY_VERDICT 这个字段名但不是注释行";
        assertEquals(raw, ShadowContractTrailer.strip(raw));
    }

    @Test
    public void doesNotStripInsideUnclosedCodeFence() {
        String raw = "示例：\n```\n<!-- AUTO_QUALITY_VERDICT: PASS -->";
        assertEquals("未闭合代码围栏内的尾部相似行不剥离", raw, ShadowContractTrailer.strip(raw));
    }

    @Test
    public void doesNotStripInsideUnclosedTildeFence() {
        String raw = "示例：\n~~~text\n<!-- AUTO_QUALITY_VERDICT: PASS -->";
        assertEquals("未闭合波浪线围栏内的尾部相似行不剥离", raw, ShadowContractTrailer.strip(raw));
    }

    @Test
    public void nullAndBlankSafe() {
        assertNull(ShadowContractTrailer.strip(null));
        assertEquals("", ShadowContractTrailer.strip(""));
    }

    // ---- extract ----

    @Test
    public void extractsTailVerdictValue() {
        Map<String, String> v = ShadowContractTrailer.extract("正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->");
        assertEquals("PASS", v.get(ShadowContractTrailer.KEY_QUALITY_VERDICT));
    }

    /** extract 不做合法性校验：非法值原样返回（合法性由 parser 负责）。 */
    @Test
    public void extractReturnsRawInvalidValue() {
        Map<String, String> v = ShadowContractTrailer.extract("正文\n<!-- AUTO_QUALITY_VERDICT: MAYBE -->");
        assertEquals("MAYBE", v.get(ShadowContractTrailer.KEY_QUALITY_VERDICT));
    }

    @Test
    public void extractsBothCompletionFields() {
        Map<String, String> v = ShadowContractTrailer.extract(
                "正文\n<!-- AUTO_COMPLETION_PROGRESS: 100% -->\n<!-- AUTO_COMPLETION_STATUS: COMPLETED -->");
        assertEquals("100%", v.get(ShadowContractTrailer.KEY_COMPLETION_PROGRESS));
        assertEquals("COMPLETED", v.get(ShadowContractTrailer.KEY_COMPLETION_STATUS));
    }

    @Test
    public void extractValuesPreservesDuplicatesForContractValidation() {
        String raw = "正文\n<!-- AUTO_QUALITY_VERDICT: PASS -->\n<!-- AUTO_QUALITY_VERDICT: FAIL -->";
        assertEquals(2, ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_QUALITY_VERDICT).size());
        assertTrue(ShadowContractTrailer.hasTrailer(raw));
    }

    @Test
    public void removesFencedExamplesBeforeLegacyParsing() {
        String raw = "```text\n是否通过: FAIL\n```\n正文";
        assertFalse(ShadowContractTrailer.withoutFencedCode(raw).contains("是否通过: FAIL"));
    }

    @Test
    public void extractEmptyWhenNoTailTrailer() {
        assertTrue(ShadowContractTrailer.extract("纯正文无 trailer").isEmpty());
        assertTrue(ShadowContractTrailer.extract("<!-- AUTO_QUALITY_VERDICT: PASS -->\n后续正文").isEmpty());
    }

    @Test
    public void strippedBusinessNeverContainsTrailerMarker() {
        String raw = "审查结论...\n是否通过: OPTIMIZE\n<!-- AUTO_QUALITY_VERDICT: OPTIMIZE -->";
        assertFalse(ShadowContractTrailer.strip(raw).contains("<!--"));
        assertFalse(ShadowContractTrailer.strip(raw).contains("AUTO_QUALITY_VERDICT"));
    }
}
