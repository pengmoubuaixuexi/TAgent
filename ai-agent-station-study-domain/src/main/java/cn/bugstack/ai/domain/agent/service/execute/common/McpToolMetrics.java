package cn.bugstack.ai.domain.agent.service.execute.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MCP 工具调用指标采集（P1.5.1）。
 * <p>
 * 与 {@link LlmMetrics} 镜像：每次 {@link org.springframework.ai.tool.ToolCallback#call} 经
 * {@code MeteredToolCallback} 装饰器后落地一组 metric。
 * <ul>
 *   <li>{@code mcp.tool.call} Timer：耗时分布 + count；tag tool/outcome</li>
 *   <li>{@code mcp.tool.errors} Counter：失败累计；tag tool/exception</li>
 * </ul>
 * 工具维度都是低基数（同一 agent 注册的工具数有限），适合作为 tag。
 */
@Component
public class McpToolMetrics {

    private static final String METRIC_CALL = "mcp.tool.call";
    private static final String METRIC_ERROR = "mcp.tool.errors";

    private final MeterRegistry registry;

    public McpToolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCall(String toolName, long latencyMs, boolean success) {
        registry.timer(METRIC_CALL, "tool", safe(toolName), "outcome", success ? "success" : "failure")
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordError(String toolName, Throwable t) {
        registry.counter(METRIC_ERROR, "tool", safe(toolName), "exception",
                t == null ? "unknown" : t.getClass().getSimpleName())
                .increment();
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s;
    }
}
