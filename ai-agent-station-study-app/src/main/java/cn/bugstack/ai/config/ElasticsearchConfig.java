package cn.bugstack.ai.config;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Elasticsearch low-level REST client 配置。
 * <p>
 * 复用 docker-compose-elk-aliyun.yml 里已存在的 ES 7.17.28：
 * - 日志侧：Logstash 写入 ai-agent-station-log-* 索引
 * - 检索侧：本 client 对 ai-agent-rag 索引做 BM25 查询（与 PgVector 的语义向量互补）
 * 同一个 ES 实例双职，ELK 栈价值最大化。
 */
@Configuration
@ConditionalOnProperty(prefix = "elasticsearch", name = "uris")
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(@Value("${elasticsearch.uris}") String uris,
                                              @Value("${elasticsearch.connect-timeout-ms:2000}") int connectTimeoutMs,
                                              @Value("${elasticsearch.socket-timeout-ms:5000}") int socketTimeoutMs) {
        HttpHost[] hosts = parseHosts(uris);
        RestClientBuilder builder = RestClient.builder(hosts);
        builder.setRequestConfigCallback(rc -> rc
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs));
        RestClient delegate = builder.build();

        ProxyFactory factory = new ProxyFactory(delegate);
        factory.setProxyTargetClass(true); // RestClient is a concrete class, must use CGLIB
        factory.addAdvice((MethodInterceptor) invocation -> {
            for (int attempt = 0; ; attempt++) {
                try {
                    return invocation.proceed();
                } catch (IOException e) {
                    if (attempt >= 2) throw e;
                }
            }
        });
        return (RestClient) factory.getProxy();
    }

    private HttpHost[] parseHosts(String uris) {
        String[] split = uris.split(",");
        HttpHost[] hosts = new HttpHost[split.length];
        for (int i = 0; i < split.length; i++) {
            hosts[i] = HttpHost.create(split[i].trim());
        }
        return hosts;
    }
}
