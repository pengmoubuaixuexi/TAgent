package cn.bugstack.ai.config;

import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 编程式配置：补充 yml 无法表达的"随机抖动"。
 *
 * 为什么这一段一定要代码方式而不是 yml：yml 里只有 enable-exponential-backoff 开关和 multiplier，
 * 并没有 randomized-wait-factor 字段。纯指数退避在突发并发下会产生"惊群"——下游服务刚缓过来，
 * 所有重试请求同时打过去再次把它压挂。加上 ±50% 随机化能把重试流量均匀涂开。
 *
 * IntervalFunction.ofExponentialRandomBackoff(initial, multiplier, factor) 的语义：
 *   第 N 次等待 = initial × multiplier^(N-1) × random(1-factor, 1+factor)
 *   例：initial=2s, multiplier=2, factor=0.5
 *       第 1 次重试等待 ∈ [1s, 3s]
 *       第 2 次重试等待 ∈ [2s, 6s]
 */
@Configuration
public class ResilienceConfig {

    /**
     * 绑定到 application.yml 的 resilience4j.retry.instances.llmCall 实例。
     * 在 yml 已有配置基础上覆写 IntervalFunction，其他字段(max-attempts, retry-exceptions 等)仍走 yml。
     */
    @Bean
    public RetryConfigCustomizer llmCallRetryCustomizer() {
        return RetryConfigCustomizer.of("llmCall", builder ->
                builder.intervalFunction(
                        IntervalFunction.ofExponentialRandomBackoff(
                                Duration.ofSeconds(2),   // 初始等待 2s
                                2.0,                     // 指数倍数 2（1→2→4→8…）
                                0.5                      // 随机因子 ±50%，防惊群
                        )
                )
        );
    }

}
