package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Regression for personal connections saved with /v1 in their base URL and an empty path. */
public class EvalJudgeRequestUrlTest {
    @Test public void personalConnectionDefaultsDoNotDuplicateV1() {
        assertEquals("https://provider.test/v1/chat/completions", url("https://provider.test/v1", ""));
        assertEquals("https://provider.test/v1/chat/completions", url(" https://provider.test/v1/ ", null));
        assertEquals("https://provider.test/v1/chat/completions", url("https://provider.test/v1", "/v1/chat/completions"));
    }

    @Test public void legacyRootAndConfiguredGatewayRoutesRemainSupported() {
        assertEquals("https://provider.test/v1/chat/completions", url("https://provider.test/", ""));
        assertEquals("https://provider.test/gateway/chat/completions", url("https://provider.test/gateway", "chat/completions"));
    }

    private static String url(String baseUrl, String path) {
        return EvalJudgeService.judgeCompletionUrl(AiClientApiVO.builder()
                .baseUrl(baseUrl).completionsPath(path).build());
    }
}
