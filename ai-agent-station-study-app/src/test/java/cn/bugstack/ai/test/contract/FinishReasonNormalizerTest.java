package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.FinishReasonNormalizer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** P0-B2b-O1：{@link FinishReasonNormalizer} 归一为固定低基数枚举；null/未知绝不伪造 length。 */
public class FinishReasonNormalizerTest {

    @Test
    public void stopVariants() {
        assertEquals("stop", FinishReasonNormalizer.normalize("stop"));
        assertEquals("stop", FinishReasonNormalizer.normalize("STOP"));
        assertEquals("stop", FinishReasonNormalizer.normalize("end_turn"));
        assertEquals("stop", FinishReasonNormalizer.normalize("  Stop  "));
    }

    @Test
    public void lengthVariants() {
        assertEquals("length", FinishReasonNormalizer.normalize("length"));
        assertEquals("length", FinishReasonNormalizer.normalize("LENGTH"));
        assertEquals("length", FinishReasonNormalizer.normalize("max_tokens"));
    }

    @Test
    public void toolCallsVariants() {
        assertEquals("tool_calls", FinishReasonNormalizer.normalize("tool_calls"));
        assertEquals("tool_calls", FinishReasonNormalizer.normalize("TOOL_CALLS"));
        assertEquals("tool_calls", FinishReasonNormalizer.normalize("function_call"));
    }

    @Test
    public void cancelledVariants() {
        assertEquals("cancelled", FinishReasonNormalizer.normalize("cancelled"));
        assertEquals("cancelled", FinishReasonNormalizer.normalize("canceled"));
    }

    @Test
    public void nullEmptyUnknownNeverFakesLength() {
        assertEquals("unknown", FinishReasonNormalizer.normalize(null));
        assertEquals("unknown", FinishReasonNormalizer.normalize(""));
        assertEquals("unknown", FinishReasonNormalizer.normalize("   "));
        assertEquals("unknown", FinishReasonNormalizer.normalize("content_filter"));
        assertEquals("unknown", FinishReasonNormalizer.normalize("某种未知原因"));
    }
}
