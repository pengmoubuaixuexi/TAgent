package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 轻量观测面板后端：基于 ai-agent-station-log-* 索引做 ES 聚合，
 * 把散落在 Kibana 里的"token 分模型消耗 / 会话调用次数"查询封装为固定接口，
 * 前端页面直接 fetch 即可渲染，避免用户每次手动建 Index Pattern + 拖 Lens。
 * <p>
 * RestClient 是可选 Bean（@Autowired required=false）：没起 ELK 时应用仍能启动，
 * 只是调本接口会返回空数据。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/observe")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
public class ObserveController {

    private static final String LOG_INDEX_PATTERN = "ai-agent-station-log-*";

    @Autowired(required = false)
    private RestClient restClient;

    /** MCP 治理观测：客户端注册表（健康状态 / 冷却 / 工具列表） */
    @Autowired(required = false)
    private McpClientRegistry mcpClientRegistry;

    /** MCP 治理观测：工具治理指标（normalize / truncate / retry / lastError） */
    @Autowired(required = false)
    private McpToolMetrics mcpToolMetrics;

    /** Micrometer：从内存读 mcp.tool.call timer + counter，比 ES 查询快 100x */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @GetMapping("/token-by-model")
    public Response<Map<String, Object>> tokenByModel(@RequestParam(value = "hours", defaultValue = "24") int hours) {
        if (restClient == null) return emptyResponse("ES unavailable");
        String body = "{"
                + "\"size\":0,"
                + "\"query\":{\"bool\":{\"filter\":["
                + "  {\"exists\":{\"field\":\"totalTokens\"}},"
                + "  {\"range\":{\"@timestamp\":{\"gte\":\"now-" + hours + "h\"}}}"
                + "]}},"
                + "\"aggs\":{\"models\":{"
                + "  \"terms\":{\"field\":\"model\",\"size\":20,\"missing\":\"(unknown)\"},"
                + "  \"aggs\":{"
                + "    \"total\":{\"sum\":{\"field\":\"totalTokens\"}},"
                + "    \"prompt\":{\"sum\":{\"field\":\"promptTokens\"}},"
                + "    \"completion\":{\"sum\":{\"field\":\"completionTokens\"}},"
                + "    \"calls\":{\"value_count\":{\"field\":\"totalTokens\"}}"
                + "  }"
                + "}}"
                + "}";
        JSONObject root = search(body);
        if (root == null) return emptyResponse("search failed");

        List<Map<String, Object>> rows = new ArrayList<>();
        long grandTotal = 0L, grandCalls = 0L;
        JSONArray buckets = root.getJSONObject("aggregations")
                .getJSONObject("models")
                .getJSONArray("buckets");
        for (int i = 0; i < buckets.size(); i++) {
            JSONObject b = buckets.getJSONObject(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", b.getString("key"));
            row.put("calls", b.getJSONObject("calls").getLongValue("value"));
            row.put("promptTokens", b.getJSONObject("prompt").getLongValue("value"));
            row.put("completionTokens", b.getJSONObject("completion").getLongValue("value"));
            row.put("totalTokens", b.getJSONObject("total").getLongValue("value"));
            grandTotal += b.getJSONObject("total").getLongValue("value");
            grandCalls += b.getJSONObject("calls").getLongValue("value");
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("windowHours", hours);
        data.put("grandTotalTokens", grandTotal);
        data.put("grandTotalCalls", grandCalls);
        data.put("items", rows);
        return success(data);
    }

    @GetMapping("/calls-by-session-today")
    public Response<Map<String, Object>> callsBySessionToday(@RequestParam(value = "size", defaultValue = "30") int size,
                                                             @RequestParam(value = "hours", defaultValue = "24") int hours) {
        if (restClient == null) return emptyResponse("ES unavailable");
        String body = "{"
                + "\"size\":0,"
                + "\"query\":{\"bool\":{\"filter\":["
                + "  {\"exists\":{\"field\":\"sessionId\"}},"
                + "  {\"exists\":{\"field\":\"step\"}},"
                + "  {\"exists\":{\"field\":\"totalTokens\"}},"
                + "  {\"range\":{\"@timestamp\":{\"gte\":\"now-" + hours + "h\"}}}"
                + "]}},"
                + "\"aggs\":{\"sessions\":{"
                + "  \"terms\":{\"field\":\"sessionId\",\"size\":" + size + "},"
                + "  \"aggs\":{"
                + "    \"calls\":{\"value_count\":{\"field\":\"totalTokens\"}},"
                + "    \"totalTokens\":{\"sum\":{\"field\":\"totalTokens\"}},"
                + "    \"latencySum\":{\"sum\":{\"field\":\"latencyMs\"}},"
                + "    \"lastSeen\":{\"max\":{\"field\":\"@timestamp\"}}"
                + "  }"
                + "}}"
                + "}";
        JSONObject root = search(body);
        if (root == null) return emptyResponse("search failed");

        List<Map<String, Object>> rows = new ArrayList<>();
        JSONArray buckets = root.getJSONObject("aggregations")
                .getJSONObject("sessions")
                .getJSONArray("buckets");
        for (int i = 0; i < buckets.size(); i++) {
            JSONObject b = buckets.getJSONObject(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", b.getString("key"));
            row.put("calls", b.getJSONObject("calls").getLongValue("value"));
            row.put("totalTokens", b.getJSONObject("totalTokens").getLongValue("value"));
            row.put("latencyMs", b.getJSONObject("latencySum").getLongValue("value"));
            row.put("lastSeen", b.getJSONObject("lastSeen").getString("value_as_string"));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("windowHours", hours);
        data.put("items", rows);
        return success(data);
    }

    @GetMapping("/summary")
    public Response<Map<String, Object>> summary(@RequestParam(value = "hours", defaultValue = "24") int hours) {
        if (restClient == null) return emptyResponse("ES unavailable");
        String body = "{"
                + "\"size\":0,"
                + "\"query\":{\"bool\":{\"filter\":["
                + "  {\"exists\":{\"field\":\"totalTokens\"}},"
                + "  {\"range\":{\"@timestamp\":{\"gte\":\"now-" + hours + "h\"}}}"
                + "]}},"
                + "\"aggs\":{"
                + "  \"totalTokens\":{\"sum\":{\"field\":\"totalTokens\"}},"
                + "  \"promptTokens\":{\"sum\":{\"field\":\"promptTokens\"}},"
                + "  \"completionTokens\":{\"sum\":{\"field\":\"completionTokens\"}},"
                + "  \"calls\":{\"value_count\":{\"field\":\"totalTokens\"}},"
                + "  \"avgLatency\":{\"avg\":{\"field\":\"latencyMs\"}},"
                + "  \"sessions\":{\"cardinality\":{\"field\":\"sessionId\"}},"
                + "  \"models\":{\"cardinality\":{\"field\":\"model\"}}"
                + "}"
                + "}";
        JSONObject root = search(body);
        if (root == null) return emptyResponse("search failed");
        JSONObject aggs = root.getJSONObject("aggregations");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("windowHours", hours);
        data.put("calls", aggs.getJSONObject("calls").getLongValue("value"));
        data.put("totalTokens", aggs.getJSONObject("totalTokens").getLongValue("value"));
        data.put("promptTokens", aggs.getJSONObject("promptTokens").getLongValue("value"));
        data.put("completionTokens", aggs.getJSONObject("completionTokens").getLongValue("value"));
        data.put("avgLatencyMs", aggs.getJSONObject("avgLatency").getDoubleValue("value"));
        data.put("uniqueSessions", aggs.getJSONObject("sessions").getLongValue("value"));
        data.put("uniqueModels", aggs.getJSONObject("models").getLongValue("value"));
        return success(data);
    }

    /**
     * MCP 客户端健康快照：直接读 McpClientRegistry.snapshotAll() 内存态，
     * 0 网络 IO；不依赖 ELK，单机裸跑也能用。
     */
    @GetMapping("/mcp-client-health")
    public Response<Map<String, Object>> mcpClientHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", System.currentTimeMillis());
        if (mcpClientRegistry == null) {
            data.put("summary", Collections.emptyMap());
            data.put("clients", Collections.emptyList());
            return success(data);
        }

        List<McpClientRegistry.ClientHealthSnapshot> snapshots = mcpClientRegistry.snapshotAll();
        List<Map<String, Object>> clients = new ArrayList<>(snapshots.size());
        int alive = 0;
        int circuitOpen = 0;
        int totalTools = 0;
        for (McpClientRegistry.ClientHealthSnapshot s : snapshots) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mcpId", s.mcpId());
            row.put("status", s.status());
            row.put("consecutiveFailures", s.consecutiveFailures());
            row.put("circuitOpenUntil", s.circuitOpenUntil());
            row.put("circuitCooldownRemainingSec", s.circuitCooldownRemainingSec());
            row.put("lastReconnectAt", s.lastReconnectAt());
            row.put("reconnectCooldownRemainingSec", s.reconnectCooldownRemainingSec());
            row.put("lastSuccessAt", s.lastSuccessAt());
            row.put("lastProbeAt", s.lastProbeAt());
            row.put("lastProbeOkAt", s.lastProbeOkAt());
            row.put("registeredTools", s.registeredTools());
            // 把重连/熔断的最近错误样本带过去：键约定为 "[client:<mcpId>]"，跟 McpClientRegistry.recordClientReconnectFailure 对齐
            row.put("lastClientError", lastClientErrorView(s.mcpId()));
            clients.add(row);
            if ("alive".equals(s.status())) alive++;
            if ("circuit_open".equals(s.status())) circuitOpen++;
            totalTools += s.registeredTools() == null ? 0 : s.registeredTools().size();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalClients", snapshots.size());
        summary.put("aliveClients", alive);
        summary.put("circuitOpenClients", circuitOpen);
        summary.put("totalRegisteredTools", totalTools);

        data.put("summary", summary);
        data.put("clients", clients);
        return success(data);
    }

    /**
     * MCP 工具治理面板：聚合 mcp.tool.* 系列 metric + lastError ring buffer，
     * 给 observe-mcp.html 渲染表格用。
     * <p>
     * Micrometer counter 是 JVM 启动至今累计（非滑动窗口），前端要"per minute"自己做 delta 即可。
     * windowHours 字段保留位但暂未生效（要做窗口需走 ES，性价比低）。
     */
    @GetMapping("/mcp-tools-status")
    public Response<Map<String, Object>> mcpToolsStatus(@RequestParam(value = "hours", defaultValue = "24") int hours) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", System.currentTimeMillis());
        data.put("windowHours", hours);

        if (meterRegistry == null) {
            data.put("summary", Collections.emptyMap());
            data.put("tools", Collections.emptyList());
            return success(data);
        }

        // tool → 聚合视图（按 tool 名归并 success/failure 两个 timer）
        Map<String, ToolMetricRow> rows = new TreeMap<>();
        for (Timer t : meterRegistry.find("mcp.tool.call").timers()) {
            String tool = safeTag(t.getId().getTag("tool"));
            String outcome = safeTag(t.getId().getTag("outcome"));
            ToolMetricRow row = rows.computeIfAbsent(tool, ToolMetricRow::new);
            long count = t.count();
            if ("success".equals(outcome)) row.success = count;
            else if ("failure".equals(outcome)) row.failure = count;
            // percentile 只取 success/failure 中较大的（实际两个 timer 各自独立分布；这里取合并视图近似值）
            HistogramSnapshot snap = t.takeSnapshot();
            for (ValueAtPercentile vp : snap.percentileValues()) {
                double p = vp.percentile();
                double valueMs = vp.value(TimeUnit.MILLISECONDS);
                // SlidingTimeWindow 在窗口内没数据时返回 NaN，跳过；否则 Math.max(0, NaN)=NaN 把已有值污染掉
                if (Double.isNaN(valueMs) || Double.isInfinite(valueMs)) continue;
                if (Math.abs(p - 0.5) < 1e-6) row.p50Ms = Math.max(row.p50Ms, valueMs);
                else if (Math.abs(p - 0.95) < 1e-6) row.p95Ms = Math.max(row.p95Ms, valueMs);
                else if (Math.abs(p - 0.99) < 1e-6) row.p99Ms = Math.max(row.p99Ms, valueMs);
            }
        }

        accumulate(rows, "mcp.tool.normalize.applied", "tool", (r, v) -> r.normalizeApplied += v);
        accumulate(rows, "mcp.tool.result.truncated", "tool", (r, v) -> r.resultTruncated += v);
        accumulate(rows, "mcp.tool.retry", "tool", (r, v) -> r.retries += v);
        accumulate(rows, "mcp.tool.name.unknown", "tool", (r, v) -> r.unknownNameHits += v);
        accumulate(rows, "mcp.tool.first_attempt.failure", "tool", (r, v) -> r.firstAttemptFailures += v);
        accumulate(rows, "mcp.tool.recovered", "tool", (r, v) -> r.recovered += v);

        // mcp.tool.name.normalized 没有 tool 维度（按 from→to），先汇总到一个 "global" 行不展示
        long nameNormalizedTotal = 0;
        for (Counter c : meterRegistry.find("mcp.tool.name.normalized").counters()) {
            nameNormalizedTotal += (long) c.count();
        }

        // 工具 → mcpId 反查（让前端能按 mcpId group）
        List<Map<String, Object>> tools = new ArrayList<>(rows.size());
        long totalCalls = 0;
        long totalErrors = 0;
        long totalFinalErrors = 0;
        long totalRecovered = 0;
        ToolMetricRow slowest = null;
        ToolMetricRow mostError = null;
        for (ToolMetricRow r : rows.values()) {
            long calls = r.success + r.failure;
            long finalErrors = r.failure;
            long errors = r.firstAttemptFailures;
            double errorRate = calls > 0 ? (errors * 1.0 / calls) : 0.0;
            totalCalls += calls;
            totalErrors += errors;
            totalFinalErrors += finalErrors;
            totalRecovered += r.recovered;
            if (slowest == null || r.p99Ms > slowest.p99Ms) slowest = r;
            if (mostError == null || errors > (mostError.firstAttemptFailures)) mostError = r;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", r.tool);
            row.put("mcpId", mcpClientRegistry == null ? null : mcpClientRegistry.getMcpIdForTool(r.tool));
            row.put("calls", calls);
            row.put("errors", errors);
            row.put("firstAttemptFailures", r.firstAttemptFailures);
            row.put("finalErrors", finalErrors);
            row.put("recovered", r.recovered);
            row.put("errorRate", round(errorRate, 4));
            row.put("latencyP50Ms", r.p50Ms > 0 ? round(r.p50Ms, 1) : null);
            row.put("latencyP95Ms", r.p95Ms > 0 ? round(r.p95Ms, 1) : null);
            row.put("latencyP99Ms", r.p99Ms > 0 ? round(r.p99Ms, 1) : null);
            row.put("normalizeApplied", r.normalizeApplied);
            row.put("resultTruncated", r.resultTruncated);
            row.put("retries", r.retries);
            row.put("unknownNameHits", r.unknownNameHits);
            row.put("lastError", lastErrorView(r.tool));
            tools.add(row);
        }
        // 按 calls 降序排，前端首屏看高频工具
        tools.sort((a, b) -> Long.compare((long) b.get("calls"), (long) a.get("calls")));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCalls", totalCalls);
        summary.put("totalErrors", totalErrors);
        summary.put("totalFirstAttemptFailures", totalErrors);
        summary.put("totalFinalErrors", totalFinalErrors);
        summary.put("totalRecovered", totalRecovered);
        summary.put("avgErrorRate", totalCalls > 0 ? round(totalErrors * 1.0 / totalCalls, 4) : 0.0);
        summary.put("slowestTool", slowest == null ? null : slowest.tool);
        summary.put("mostErrorTool", mostError == null ? null : mostError.tool);
        summary.put("nameNormalizedTotal", nameNormalizedTotal);
        data.put("summary", summary);
        data.put("tools", tools);
        return success(data);
    }

    /** 累加 Counter 到对应 tool 行；缺 row 自动建。 */
    private void accumulate(Map<String, ToolMetricRow> rows, String metricName, String tagName,
                            java.util.function.ObjLongConsumer<ToolMetricRow> setter) {
        for (Counter c : meterRegistry.find(metricName).counters()) {
            String tool = safeTag(c.getId().getTag(tagName));
            ToolMetricRow row = rows.computeIfAbsent(tool, ToolMetricRow::new);
            setter.accept(row, (long) c.count());
        }
    }

    private Map<String, Object> lastErrorView(String tool) {
        if (mcpToolMetrics == null) return null;
        List<McpToolMetrics.ErrorSample> samples = mcpToolMetrics.recentErrors(tool);
        if (samples.isEmpty()) return null;
        McpToolMetrics.ErrorSample last = samples.get(samples.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", last.ts());
        out.put("exception", last.exception());
        out.put("message", last.message());
        return out;
    }

    /** 重连失败的错误样本：键 "[client:mcpId]"，跟 McpToolMetrics.recordClientReconnectFailure 对齐。 */
    private Map<String, Object> lastClientErrorView(String mcpId) {
        return lastErrorView("[client:" + mcpId + "]");
    }

    private static String safeTag(String tag) {
        return tag == null || tag.isEmpty() ? "unknown" : tag;
    }

    private static double round(double v, int scale) {
        double pow = Math.pow(10, scale);
        return Math.round(v * pow) / pow;
    }

    /** 每个工具一行聚合数据。 */
    private static final class ToolMetricRow {
        final String tool;
        long success;
        long failure;
        double p50Ms;
        double p95Ms;
        double p99Ms;
        long normalizeApplied;
        long resultTruncated;
        long retries;
        long unknownNameHits;
        long firstAttemptFailures;
        long recovered;
        ToolMetricRow(String tool) { this.tool = tool; }
    }

    private JSONObject search(String body) {
        try {
            Request req = new Request("POST", "/" + LOG_INDEX_PATTERN + "/_search");
            req.setJsonEntity(body);
            String resp = EntityUtils.toString(restClient.performRequest(req).getEntity(), "UTF-8");
            return JSON.parseObject(resp);
        } catch (org.elasticsearch.client.ResponseException e) {
            // ES 返回 4xx/5xx：把 body 打出来，通常是 mapping 冲突或聚合字段类型不对
            String detail;
            try { detail = EntityUtils.toString(e.getResponse().getEntity(), "UTF-8"); }
            catch (Exception ignore) { detail = e.getMessage(); }
            log.warn("Observe ES search rejected by ES: {}", detail);
            return null;
        } catch (Exception e) {
            log.warn("Observe ES search failed: {}", e.getMessage());
            return null;
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private Response<Map<String, Object>> emptyResponse(String info) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", Collections.emptyList());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(info)
                .data(data)
                .build();
    }
}
