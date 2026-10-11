package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolCallMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * P1 指标语义：首次真实 MCP 调用失败即计入 first_attempt.failure，
 * 即使后续 retry 恢复成功，最终 call 仍可保持 success。
 */
public class MeteredToolCallbackFirstAttemptMetricsTest {

    @Test
    public void firstAttemptFailureIsCountedEvenWhenRetryRecovers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpToolMetrics metrics = new McpToolMetrics(registry);
        FlakyToolCallback flaky = new FlakyToolCallback();
        MeteredToolCallback callback = new MeteredToolCallback(
                flaky,
                metrics,
                false,
                false,
                10,
                20_000,
                false,
                true,
                2,
                1
        );

        String out = callback.call("{\"q\":\"x\"}");

        assertEquals("OK_AFTER_RETRY", out);
        assertEquals(2, flaky.calls.get());

        Counter firstFailure = registry.find("mcp.tool.first_attempt.failure")
                .tag("tool", "flaky_search")
                .tag("reason", "retryable")
                .counter();
        Counter recovered = registry.find("mcp.tool.recovered")
                .tag("tool", "flaky_search")
                .tag("recovery", "retry")
                .counter();
        Timer finalSuccess = registry.find("mcp.tool.call")
                .tag("tool", "flaky_search")
                .tag("outcome", "success")
                .timer();
        Timer finalFailure = registry.find("mcp.tool.call")
                .tag("tool", "flaky_search")
                .tag("outcome", "failure")
                .timer();

        assertEquals(1.0, firstFailure.count(), 0.001);
        assertEquals(1.0, recovered.count(), 0.001);
        assertEquals(1L, finalSuccess.count());
        assertEquals("最终已恢复成功，不应再记最终失败", 0L, finalFailure == null ? 0L : finalFailure.count());
    }

    static class FlakyToolCallback implements ToolCallback {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("flaky_search").description("test").inputSchema("{}").build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return new McpToolCallMetadata("search-mcp", true, false, null, false);
        }

        @Override
        public String call(String input) {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException(new IOException("503 temporary unavailable"));
            }
            return "OK_AFTER_RETRY";
        }
    }
}
