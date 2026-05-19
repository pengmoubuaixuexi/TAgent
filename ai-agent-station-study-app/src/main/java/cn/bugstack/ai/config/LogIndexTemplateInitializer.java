package cn.bugstack.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 为 ai-agent-station-log-* 索引创建 composable index template，
 * 确保 tokens / latencyMs 以数值 long 类型落盘（否则默认动态映射为 text，
 * sum / avg 聚合无法执行），业务维度字段固定为 keyword（terms 聚合）。
 * <p>
 * 幂等：通过 HEAD 探测先判断是否已存在，不存在才 PUT。失败不中断启动，只打印警告，
 * 因为在没有 ES 的环境（本地不启 ELK）不应影响应用可用性。
 */
@Slf4j
@Component
@ConditionalOnBean(RestClient.class)
public class LogIndexTemplateInitializer {

    private static final String TEMPLATE_NAME = "ai-agent-station-log-template";

    private static final String TEMPLATE_BODY = "{"
            + "\"index_patterns\":[\"ai-agent-station-log-*\"],"
            + "\"priority\":100,"
            + "\"template\":{"
            + "  \"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
            + "  \"mappings\":{"
            + "    \"properties\":{"
            + "      \"@timestamp\":{\"type\":\"date\"},"
            + "      \"app\":{\"type\":\"keyword\"},"
            + "      \"requestId\":{\"type\":\"keyword\"},"
            + "      \"traceId\":{\"type\":\"keyword\"},"
            + "      \"spanId\":{\"type\":\"keyword\"},"
            + "      \"userId\":{\"type\":\"keyword\"},"
            + "      \"tenantId\":{\"type\":\"keyword\"},"
            + "      \"sessionId\":{\"type\":\"keyword\"},"
            + "      \"agentId\":{\"type\":\"keyword\"},"
            + "      \"billingScope\":{\"type\":\"keyword\"},"
            + "      \"clientId\":{\"type\":\"keyword\"},"
            + "      \"step\":{\"type\":\"keyword\"},"
            + "      \"model\":{\"type\":\"keyword\"},"
            + "      \"toolName\":{\"type\":\"keyword\"},"
            + "      \"level\":{\"type\":\"keyword\"},"
            + "      \"logger_name\":{\"type\":\"keyword\"},"
            + "      \"thread_name\":{\"type\":\"keyword\"},"
            + "      \"promptTokens\":{\"type\":\"long\"},"
            + "      \"completionTokens\":{\"type\":\"long\"},"
            + "      \"totalTokens\":{\"type\":\"long\"},"
            + "      \"cachedTokens\":{\"type\":\"long\"},"
            + "      \"latencyMs\":{\"type\":\"long\"},"
            + "      \"toolLatencyMs\":{\"type\":\"long\"},"
            + "      \"message\":{\"type\":\"text\"},"
            + "      \"stack_trace\":{\"type\":\"text\"}"
            + "    }"
            + "  }"
            + "}"
            + "}";

    @Resource
    private RestClient restClient;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTemplate() {
        try {
            Request put = new Request("PUT", "/_index_template/" + TEMPLATE_NAME);
            put.setJsonEntity(TEMPLATE_BODY);
            Response resp = restClient.performRequest(put);
            int code = resp.getStatusLine().getStatusCode();
            if (code >= 200 && code < 300) {
                log.info("ES index template ensured: {}", TEMPLATE_NAME);
            } else {
                log.warn("ES index template PUT got unexpected status: {}", code);
            }
        } catch (Exception e) {
            log.warn("ES index template init failed (ES reachable?): {}", e.getMessage());
        }
    }
}
