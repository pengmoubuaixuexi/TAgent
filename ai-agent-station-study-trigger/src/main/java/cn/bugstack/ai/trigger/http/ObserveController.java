package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
