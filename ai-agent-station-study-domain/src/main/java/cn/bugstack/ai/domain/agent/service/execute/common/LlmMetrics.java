package cn.bugstack.ai.domain.agent.service.execute.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用的业务指标采集点（亮点 4 Part D）。
 * <p>
 * 每次 LLM 调用的耗时、token 用量、结果 + billingScope 以 Micrometer 形式吐出。
 * 暴露端点 /actuator/prometheus 会被 Prometheus scrape。
 * <p>
 * <b>低基数标签策略</b>：
 * <ul>
 *   <li>step（step1/2/3/4 + unified_router 等）— ~10 个</li>
 *   <li>model — ~5 个（gpt-4o / mimo / qwen / claude / haiku）</li>
 *   <li>outcome — 2 个（success / failure）</li>
 *   <li>billingScope — 2 个（USER_CHARGEABLE / SYSTEM_OVERHEAD），从 LlmObservationRecorder 传进来</li>
 * </ul>
 * 维度总计 ≤ 200 series/metric，Prometheus 单实例无压力。
 * 会话/用户/请求 id 等高基数字段走日志不走指标。
 */
@Component
public class LlmMetrics {

    private static final String METRIC_CALL = "llm.call";
    private static final String METRIC_TOKENS = "llm.tokens";
    private static final String METRIC_COST = "llm.cost.usd";
    /** P0.4 Prompt Caching：被命中的 prompt token 数（OpenAI 自动缓存） */
    private static final String METRIC_CACHED_TOKENS = "llm.tokens.cached";

    /** billingScope 缺失时的兜底值，避免 Prometheus 出现空 label。 */
    private static final String DEFAULT_BILLING_SCOPE = "unknown";

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次 LLM 调用：耗时 + token + 成本 + billingScope。
     *
     * @param stepName         step1_analyzer / step2_precision_executor 等
     * @param model            gpt-4o / claude-sonnet-4-5 等，未知填 "unknown"
     * @param billingScope     USER_CHARGEABLE / SYSTEM_OVERHEAD（由 LlmObservationRecorder 推断或显式传入）
     * @param latencyMs        本次调用耗时（含重试、CB 放行等待）
     * @param promptTokens     输入 token 精确值
     * @param completionTokens 输出 token 精确值
     * @param success          response != null 且有内容视为 success，null / 降级视为 failure
     */
    public void record(String stepName, String model, String billingScope, long latencyMs,
                       long promptTokens, long completionTokens, boolean success) {
        String outcome = success ? "success" : "failure";
        String safeModel = safe(model);
        String safeScope = safe(billingScope);

        registry.timer(METRIC_CALL,
                "step", stepName, "model", safeModel, "outcome", outcome, "billingScope", safeScope)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        if (promptTokens > 0) {
            tokenCounter(stepName, safeModel, safeScope, "prompt").increment(promptTokens);
        }
        if (completionTokens > 0) {
            tokenCounter(stepName, safeModel, safeScope, "completion").increment(completionTokens);
        }

        double costUsd = LlmPricing.estimateCostUsd(safeModel, promptTokens, completionTokens);
        if (costUsd > 0) {
            costCounter(stepName, safeModel, safeScope).increment(costUsd);
        }
    }

    /** 历史签名兼容：billingScope 缺省 "unknown"。给老的测试代码和未来万一漏传留兜底。 */
    public void record(String stepName, String model, long latencyMs,
                       long promptTokens, long completionTokens, boolean success) {
        record(stepName, model, DEFAULT_BILLING_SCOPE, latencyMs, promptTokens, completionTokens, success);
    }

    private Counter tokenCounter(String step, String model, String billingScope, String kind) {
        return registry.counter(METRIC_TOKENS,
                "step", step, "model", model, "billingScope", billingScope, "kind", kind);
    }

    private Counter costCounter(String step, String model, String billingScope) {
        return registry.counter(METRIC_COST,
                "step", step, "model", model, "billingScope", billingScope);
    }

    /**
     * P0.4 Prompt Caching：单独记录被缓存命中的 prompt token 数。
     * 既能算缓存命中率（cachedTokens / promptTokens），又能算实际省钱（cachedTokens × discount）。
     */
    public void recordCachedTokens(String stepName, String model, String billingScope, long cachedTokens) {
        if (cachedTokens <= 0) return;
        registry.counter(METRIC_CACHED_TOKENS,
                "step", stepName, "model", safe(model), "billingScope", safe(billingScope))
                .increment(cachedTokens);
    }

    /** 历史签名兼容。 */
    public void recordCachedTokens(String stepName, String model, long cachedTokens) {
        recordCachedTokens(stepName, model, DEFAULT_BILLING_SCOPE, cachedTokens);
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s;
    }
}
