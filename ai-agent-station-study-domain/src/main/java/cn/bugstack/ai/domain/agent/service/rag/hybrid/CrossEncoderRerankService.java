package cn.bugstack.ai.domain.agent.service.rag.hybrid;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cross-Encoder Rerank API 实现（P1.4 7.2）。
 * <p>
 * 支持 Cohere / SiliconFlow 等兼容 Cohere rerank 格式的 API。
 * API 文档：https://docs.cohere.com/reference/rerank
 * <p>
 * 开关：{@code agent.rerank.provider=cohere|siliconflow} 时生效，否则走 LlmRerankService。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "agent.rerank", name = "provider", havingValue = "siliconflow", matchIfMissing = false)
public class CrossEncoderRerankService implements IRerankService {

    @Value("${agent.rerank.api-url:https://api.cohere.com/v2/rerank}")
    private String apiUrl;

    @Value("${agent.rerank.api-key:}")
    private String apiKey;

    @Value("${agent.rerank.model:rerank-english-v3.0}")
    private String model;

    @Value("${agent.rerank.timeout-seconds:10}")
    private int timeoutSeconds;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Rerank API key not configured, fallback to original order");
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
        if (candidates.size() <= topN) return candidates;

        long start = System.currentTimeMillis();

        List<String> texts = new ArrayList<>(candidates.size());
        for (Document d : candidates) {
            String t = d.getText();
            texts.add(t != null ? t : "");
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", texts);
        body.put("top_n", topN);

        List<Document> reranked;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Rerank HTTP {}: {}, fallback to original order",
                        response.statusCode(), response.body());
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }

            JSONObject respJson = JSON.parseObject(response.body());
            JSONArray results = respJson.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                log.warn("Rerank returned empty results");
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }

            reranked = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                int idx = r.getIntValue("index");
                if (idx >= 0 && idx < candidates.size()) {
                    reranked.add(candidates.get(idx));
                }
            }
        } catch (Exception e) {
            log.warn("Rerank failed: {}, fallback to original order", e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        long latency = System.currentTimeMillis() - start;
        MDC.put("retrieval", "rerank-cross-encoder");
        MDC.put("candidates", String.valueOf(candidates.size()));
        MDC.put("topN", String.valueOf(reranked.size()));
        MDC.put("latencyMs", String.valueOf(latency));
        try {
            log.info("Cross-encoder rerank done: candidates={} topN={} latencyMs={} model={}",
                    candidates.size(), reranked.size(), latency, model);
        } finally {
            MDC.remove("retrieval");
            MDC.remove("candidates");
            MDC.remove("topN");
            MDC.remove("latencyMs");
        }
        return reranked;
    }
}
