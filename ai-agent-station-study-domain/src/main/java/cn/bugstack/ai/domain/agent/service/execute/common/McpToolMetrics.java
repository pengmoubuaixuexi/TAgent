package cn.bugstack.ai.domain.agent.service.execute.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP 工具调用指标采集。
 * <p>
 * 在 {@link MeteredToolCallback} / {@link RobustToolCallingManager} / {@link McpClientRegistry}
 * 之上做的统一治理面 metric 入口，给 ObserveController 拼 `/mcp-tools-status` 用。
 * <ul>
 *   <li>{@code mcp.tool.call} Timer：耗时分布 + count；tag tool/outcome</li>
 *   <li>{@code mcp.tool.errors} Counter：失败累计；tag tool/exception</li>
 *   <li>{@code mcp.tool.normalize.applied} Counter：入参 normalize 命中；tag tool/kind</li>
 *   <li>{@code mcp.tool.result.truncated} Counter：返回值被截断；tag tool</li>
 *   <li>{@code mcp.tool.result.raw_chars} Summary：返回值原始字符数分布；tag tool</li>
 *   <li>{@code mcp.tool.name.normalized} Counter：工具名大小写校正；tag from/to</li>
 *   <li>{@code mcp.tool.name.unknown} Counter：未知工具名命中；tag tool</li>
 *   <li>{@code mcp.tool.first_attempt.failure} Counter：首次真实调用失败；tag tool/reason</li>
 *   <li>{@code mcp.tool.recovered} Counter：首次失败后被重试/重连恢复；tag tool/recovery</li>
 *   <li>{@code mcp.tool.retry} Counter：工具内瞬态错误重试；tag tool/attempt</li>
 *   <li>{@code mcp.tool.timeout.probe} Counter：超时后探活结果；tag tool/alive</li>
 *   <li>{@code mcp.client.reconnect} Counter：客户端重连；tag mcpId/trigger</li>
 *   <li>{@code mcp.client.reconnect.cooldown_hit} Counter：重连冷却期命中；tag mcpId</li>
 * </ul>
 * <p>
 * 另外维护一个 per-tool ring buffer（保留最近 3 条错误样本），供 Observe 页快速定位失败原因，
 * 不依赖 ELK——本地裸跑也能拿到。
 */
@Component
public class McpToolMetrics {

    private static final String METRIC_CALL = "mcp.tool.call";
    private static final String METRIC_ERROR = "mcp.tool.errors";
    private static final String METRIC_NORMALIZE_APPLIED = "mcp.tool.normalize.applied";
    private static final String METRIC_RESULT_TRUNCATED = "mcp.tool.result.truncated";
    private static final String METRIC_RESULT_RAW_CHARS = "mcp.tool.result.raw_chars";
    private static final String METRIC_NAME_NORMALIZED = "mcp.tool.name.normalized";
    private static final String METRIC_NAME_UNKNOWN = "mcp.tool.name.unknown";
    private static final String METRIC_FIRST_ATTEMPT_FAILURE = "mcp.tool.first_attempt.failure";
    private static final String METRIC_RECOVERED = "mcp.tool.recovered";
    private static final String METRIC_RETRY = "mcp.tool.retry";
    private static final String METRIC_TIMEOUT_PROBE = "mcp.tool.timeout.probe";
    private static final String METRIC_CLIENT_RECONNECT = "mcp.client.reconnect";
    private static final String METRIC_RECONNECT_COOLDOWN_HIT = "mcp.client.reconnect.cooldown_hit";
    private static final String METRIC_CIRCUIT_OPEN = "mcp.client.circuit.open";
    private static final String METRIC_CONSECUTIVE_FAILURES = "mcp.client.consecutive_failures";
    private static final String METRIC_APPROVAL_DENIED = "mcp.tool.approval.denied";
    private static final String METRIC_POLICY_RESOLUTION = "agent.tool.policy.resolution";

    private static final int LAST_ERROR_BUFFER_SIZE = 3;
    private static final int LAST_ERROR_MESSAGE_MAX_CHARS = 200;

    private final MeterRegistry registry;

    /** tool → 最近 N 条错误样本（线程安全 deque，FIFO 截断） */
    private final Map<String, Deque<ErrorSample>> lastErrorsByTool = new ConcurrentHashMap<>();

    public McpToolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ====== 已有 metric ======

    public void recordCall(String toolName, long latencyMs, boolean success) {
        registry.timer(METRIC_CALL, "tool", safe(toolName), "outcome", success ? "success" : "failure")
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordError(String toolName, Throwable t) {
        String tool = safe(toolName);
        registry.counter(METRIC_ERROR, "tool", tool, "exception",
                t == null ? "unknown" : t.getClass().getSimpleName())
                .increment();
        recordErrorSample(tool, t);
    }

    // ====== 新增 metric ======

    /** 入参 normalize 命中：kind ∈ {github_per_page, calculate_precision, aisearch_strip}。 */
    public void recordNormalizeApplied(String toolName, String kind) {
        registry.counter(METRIC_NORMALIZE_APPLIED, "tool", safe(toolName), "kind", safe(kind)).increment();
    }

    /** 返回值原始字符数 + 是否被截断；rawChars 进 summary，截断与否进 counter。 */
    public void recordResultSize(String toolName, int rawChars, boolean truncated) {
        String tool = safe(toolName);
        if (rawChars >= 0) {
            registry.summary(METRIC_RESULT_RAW_CHARS, "tool", tool).record(rawChars);
        }
        if (truncated) {
            registry.counter(METRIC_RESULT_TRUNCATED, "tool", tool).increment();
        }
    }

    /** 工具名大小写校正命中。 */
    public void recordNameNormalized(String from, String to) {
        registry.counter(METRIC_NAME_NORMALIZED, "from", safe(from), "to", safe(to)).increment();
    }

    /** 未知工具名命中（LLM 幻觉工具）。 */
    public void recordUnknownToolName(String toolName) {
        registry.counter(METRIC_NAME_UNKNOWN, "tool", safe(toolName)).increment();
    }

    /**
     * 首次真实 MCP 调用失败。即使后续 retry / reconnect 恢复成功，也会保留这次失败样本，
     * 用来衡量"第一次就成功"的工具稳定性。
     */
    public void recordFirstAttemptFailure(String toolName, String reason) {
        registry.counter(METRIC_FIRST_ATTEMPT_FAILURE, "tool", safe(toolName), "reason", safe(reason)).increment();
    }

    /**
     * 首次真实 MCP 调用失败后，后续通过 retry / reconnect 等治理动作恢复成功。
     */
    public void recordRecovered(String toolName, String recovery) {
        registry.counter(METRIC_RECOVERED, "tool", safe(toolName), "recovery", safe(recovery)).increment();
    }

    /**
     * G1-C：人工审批被拒/超时/通道缺失计数。reason ∈ {REJECTED, TIMEOUT, APPROVAL_UNAVAILABLE}。
     * 通过 Grafana 可看哪些工具/会话审批未通过比例最高，用于灰度策略调优。
     */
    public void recordApprovalDenied(String toolName, String reason) {
        registry.counter(METRIC_APPROVAL_DENIED, "tool", safe(toolName), "reason", safe(reason)).increment();
    }

    /** P1-A1：请求级工具 policy 的解析状态；state 仅允许 explicit/missing/invalid 三个低基数值。 */
    public void recordToolPolicyResolution(String state) {
        registry.counter(METRIC_POLICY_RESOLUTION, "state", safe(state)).increment();
    }

    /** 工具调用瞬态错误重试。 */
    public void recordRetry(String toolName, int attempt) {
        registry.counter(METRIC_RETRY, "tool", safe(toolName), "attempt", String.valueOf(attempt)).increment();
    }

    /** 工具调用超时后的探活结果（alive=true 说明服务端慢，alive=false 说明连接死了）。 */
    public void recordTimeoutProbe(String toolName, boolean alive) {
        registry.counter(METRIC_TIMEOUT_PROBE, "tool", safe(toolName), "alive", String.valueOf(alive)).increment();
    }

    /** MCP 客户端重连成功；trigger ∈ {cold, dead, force, timeout}。 */
    public void recordClientReconnect(String mcpId, String trigger) {
        registry.counter(METRIC_CLIENT_RECONNECT, "mcpId", safe(mcpId), "trigger", safe(trigger),
                "outcome", "success").increment();
    }

    /** MCP 客户端重连失败（重建抛异常）。同时把异常样本落 ring buffer 让 Observe 页能定位。 */
    public void recordClientReconnectFailure(String mcpId, String trigger, Throwable t) {
        registry.counter(METRIC_CLIENT_RECONNECT, "mcpId", safe(mcpId), "trigger", safe(trigger),
                "outcome", "failure").increment();
        // 用 mcpId 做 ring buffer 的 key 前缀，跟 tool 维度区分；Observe 页 latestErrorPerTool 也能扫到
        recordErrorSample("[client:" + safe(mcpId) + "]", t);
    }

    /** 重连冷却期命中（说明刚重连过又被触发，避免抖动）。 */
    public void recordReconnectCooldownHit(String mcpId) {
        registry.counter(METRIC_RECONNECT_COOLDOWN_HIT, "mcpId", safe(mcpId)).increment();
    }

    /** 熔断打开（连续 N 次重建失败触发）。 */
    public void recordCircuitOpen(String mcpId) {
        registry.counter(METRIC_CIRCUIT_OPEN, "mcpId", safe(mcpId)).increment();
    }

    /**
     * 注册 consecutiveFailures gauge：让 McpClientRegistry 把内部 AtomicInteger
     * 直接接到 Prometheus，Grafana 能画"实时连续失败次数"曲线。
     * 调用方传 AtomicInteger，gauge 会跟着自动跟踪。
     */
    public void registerConsecutiveFailuresGauge(String mcpId, java.util.concurrent.atomic.AtomicInteger ref) {
        registry.gauge(METRIC_CONSECUTIVE_FAILURES, java.util.List.of(io.micrometer.core.instrument.Tag.of("mcpId", safe(mcpId))), ref, java.util.concurrent.atomic.AtomicInteger::get);
    }

    // ====== lastError ring buffer ======

    private void recordErrorSample(String tool, Throwable t) {
        if (tool == null) return;
        ErrorSample sample = new ErrorSample(
                System.currentTimeMillis(),
                t == null ? "unknown" : t.getClass().getSimpleName(),
                abbreviate(t == null ? null : t.getMessage(), LAST_ERROR_MESSAGE_MAX_CHARS));
        Deque<ErrorSample> deque = lastErrorsByTool.computeIfAbsent(tool, k -> new LinkedList<>());
        synchronized (deque) {
            deque.addLast(sample);
            while (deque.size() > LAST_ERROR_BUFFER_SIZE) {
                deque.removeFirst();
            }
        }
    }

    /** 取某工具最近 N 条错误样本（按时间升序，可能为空 list）。 */
    public List<ErrorSample> recentErrors(String toolName) {
        if (toolName == null) return Collections.emptyList();
        Deque<ErrorSample> deque = lastErrorsByTool.get(toolName);
        if (deque == null) return Collections.emptyList();
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /** 取所有工具最新 1 条错误样本（按时间降序，给 Observe 总览页用）。 */
    public Map<String, ErrorSample> latestErrorPerTool() {
        Map<String, ErrorSample> raw = new java.util.LinkedHashMap<>();
        lastErrorsByTool.forEach((tool, deque) -> {
            synchronized (deque) {
                if (!deque.isEmpty()) raw.put(tool, deque.peekLast());
            }
        });
        // 按 ts 降序输出到新 LinkedHashMap，保留遍历顺序
        Map<String, ErrorSample> out = new java.util.LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted(Map.Entry.<String, ErrorSample>comparingByValue(Comparator.comparingLong(ErrorSample::ts).reversed()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ====== utility ======

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /** 单条错误样本（ts = epoch ms，exception = 异常类 SimpleName，message = 截断后的描述）。 */
    public record ErrorSample(long ts, String exception, String message) {}
}
