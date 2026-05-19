package cn.bugstack.ai.domain.agent.service.execute.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用的业务指标采集点（亮点 4 Part D）。
 * <p>
 * 只做一件事：把每次 LLM 调用的耗时、token 用量、结果以 Micrometer 的形式吐出。
 * 暴露端点 /actuator/prometheus 会被 Prometheus scrape，Agent 通过 MCP 反查这些指标做自省诊断。
 * <p>
 * 不写到 AbstractExecuteSupport 里是因为两个 auto/flow 变体都要调用，单独抽出避免复制。
 */
@Component
public class LlmMetrics {

    private static final String METRIC_CALL = "llm.call";
    private static final String METRIC_TOKENS = "llm.tokens";
    private static final String METRIC_COST = "llm.cost.usd";
    /** P0.4 Prompt Caching：被命中的 prompt token 数（OpenAI 自动缓存） */
    private static final String METRIC_CACHED_TOKENS = "llm.tokens.cached";

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次 LLM 调用的完整指标：耗时 + token。
     * 低基数标签（step/model/outcome）才做 tag，会话/用户等高基数走日志不走指标。
     *
     * @param stepName         step1_analyzer / step2_precision_executor 等
     * @param model            gpt-4o / claude-sonnet-4-5 等，未知填 "unknown"
     * @param latencyMs        本次调用耗时（含重试、CB 放行等待）
     * @param promptTokens     输入 token 精确值
     * @param completionTokens 输出 token 精确值
     * @param success          response != null 且有内容视为 success，null / 降级视为 failure
     */
    public void record(String stepName, String model, long latencyMs,
                       long promptTokens, long completionTokens, boolean success) {
        String outcome = success ? "success" : "failure";
        String safeModel = (model == null || model.isEmpty()) ? "unknown" : model;

        registry.timer(METRIC_CALL, "step", stepName, "model", safeModel, "outcome", outcome)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        // token 累计：按 kind 维度拆，方便按"输入/输出"分别出图 + 估成本
        if (promptTokens > 0) {
            tokenCounter(stepName, safeModel, "prompt").increment(promptTokens);
        }
        if (completionTokens > 0) {
            tokenCounter(stepName, safeModel, "completion").increment(completionTokens);
        }

        // 成本估算（P0.0.2 Cost Dashboard）：按 LlmPricing 表做 token→USD 折算，
        // 模型未命中价格表返回 0，不增 counter；后续 Grafana 直接 sum by step/model 出钱。
        double costUsd = LlmPricing.estimateCostUsd(safeModel, promptTokens, completionTokens);
        if (costUsd > 0) {
            costCounter(stepName, safeModel).increment(costUsd);
        }
    }

    private Counter tokenCounter(String step, String model, String kind) {
        return registry.counter(METRIC_TOKENS, "step", step, "model", model, "kind", kind);
    }

    private Counter costCounter(String step, String model) {
        return registry.counter(METRIC_COST, "step", step, "model", model);
    }

    /**
     * P0.4 Prompt Caching：单独记录被缓存命中的 prompt token 数。
     * 既能算缓存命中率（cachedTokens / promptTokens），又能算实际省钱（cachedTokens × discount）。
     */
    public void recordCachedTokens(String stepName, String model, long cachedTokens) {
        if (cachedTokens <= 0) return;
        String safeModel = (model == null || model.isEmpty()) ? "unknown" : model;
        registry.counter(METRIC_CACHED_TOKENS, "step", stepName, "model", safeModel)
                .increment(cachedTokens);
    }
}
