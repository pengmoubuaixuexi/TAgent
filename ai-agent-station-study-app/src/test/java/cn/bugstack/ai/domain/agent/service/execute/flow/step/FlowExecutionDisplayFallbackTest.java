package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FlowExecutionDisplayFallbackTest {

    @Test
    public void resultBlockOnlyResponseMustBecomeVisibleInsteadOfLeavingAnEmptyCard() {
        String response = "```\n"
                + "=== 执行结果 ===\n"
                + "状态: 成功\n"
                + "结果描述: 已查询到 20 个车次\n"
                + "输出数据:\n- G38 07:47-12:22\n"
                + "```";

        String fallback = AbstractExecuteSupport.buildResultBlockDisplayFallback(response);

        assertTrue(fallback.contains("本步骤未单独生成展示正文"));
        assertTrue(fallback.contains("已查询到 20 个车次"));
        assertTrue(fallback.contains("G38 07:47-12:22"));
        assertTrue("internal marker must not be rendered", !fallback.contains("=== 执行结果 ==="));
    }

    @Test
    public void normalUserVisiblePrefixMustNotDuplicateTheStructuredArtifact() {
        String response = "这是给用户看的完整车次列表。\n\n"
                + "=== 执行结果 ===\n状态: 成功\n输出数据: {...}";

        assertEquals("", AbstractExecuteSupport.buildResultBlockDisplayFallback(response));
    }

    @Test
    public void frontendMustOnlyFillAnExecutionCardWhenItsTokenBufferIsEmpty() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull("static/index.html must be on the test classpath", stream);
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("function fillEmptyExecutionStepCard"));
            assertTrue(html.contains("String(entry.buf || '').trim()"));
            assertTrue(html.contains("payload.type === 'execution'"));
            assertTrue(html.contains("executionResultDisplayFallback(payload.content)"));
        }
    }
}
