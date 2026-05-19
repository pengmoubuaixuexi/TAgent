package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 模型定价表（USD per 1K tokens）。
 * <p>
 * 用于 {@link LlmMetrics} 估算每次调用的成本，写入 Prometheus `llm.cost.usd` Counter。
 * <p>
 * 价格来源：各厂商公开 pricing page（截至 2026-04）。模型未命中时返回 0.0（不估算，避免误导）。
 * 后续如要外置：迁移到 @ConfigurationProperties 读 application.yml 的 `llm.pricing.*`。
 */
public final class LlmPricing {

    private LlmPricing() {}

    /** 单位：USD per 1K tokens */
    private static final Map<String, double[]> PRICES = new HashMap<>();

    static {
        // [inputUsdPer1k, outputUsdPer1k]
        PRICES.put("gpt-4o",                  new double[]{0.0025, 0.0100});
        PRICES.put("gpt-4o-mini",             new double[]{0.00015, 0.00060});
        PRICES.put("gpt-4-turbo",             new double[]{0.0100, 0.0300});
        PRICES.put("gpt-3.5-turbo",           new double[]{0.0005, 0.0015});
        PRICES.put("claude-opus-4-7",         new double[]{0.0150, 0.0750});
        PRICES.put("claude-sonnet-4-6",       new double[]{0.0030, 0.0150});
        PRICES.put("claude-haiku-4-5",        new double[]{0.0008, 0.0040});
        // 国产模型（CNY 价 -> USD 取近似汇率 7.2 折算，截至 2026-05）
        // mimo-v2.5-pro：思考增强版，主对话用；mimo-v2.5：路由/分类小模型
        PRICES.put("mimo-v2.5-pro",           new double[]{0.0010, 0.0020});
        PRICES.put("mimo-v2.5",               new double[]{0.0003, 0.0006});
        // DeepSeek：v0.5.0 切换后的主 LLM
        PRICES.put("deepseek-v4-pro",         new double[]{0.0008, 0.0016});
        PRICES.put("deepseek-v4-flash",       new double[]{0.0002, 0.0004});
        PRICES.put("deepseek-chat",           new double[]{0.00027, 0.0011});
        PRICES.put("deepseek-reasoner",       new double[]{0.00055, 0.0022});
        // SiliconFlow embedding
        PRICES.put("BAAI/bge-large-zh-v1.5",  new double[]{0.00001, 0.0});
        PRICES.put("Qwen/Qwen3-Embedding-8B", new double[]{0.00002, 0.0});
        // OpenAI embedding
        PRICES.put("text-embedding-3-small",  new double[]{0.00002, 0.0});
        PRICES.put("text-embedding-3-large",  new double[]{0.00013, 0.0});
        PRICES.put("text-embedding-ada-002",  new double[]{0.00010, 0.0});
    }

    /**
     * 估算单次调用成本（USD）。
     * 模型名走前缀匹配以便对 "gpt-4o-2024-08-06" 这类带日期后缀的也命中。
     * 命中不到任何前缀则返回 0.0 — 上游 Counter 不会 increment(0)，相当于该 model 不计成本。
     */
    public static double estimateCostUsd(String model, long promptTokens, long completionTokens) {
        if (model == null) return 0.0;
        double[] price = lookup(model);
        if (price == null) return 0.0;
        return promptTokens / 1000.0 * price[0] + completionTokens / 1000.0 * price[1];
    }

    private static double[] lookup(String model) {
        if (PRICES.containsKey(model)) return PRICES.get(model);
        return PRICES.entrySet().stream()
                .sorted(Map.Entry.<String, double[]>comparingByKey(Comparator.comparingInt(String::length).reversed()))
                .filter(e -> model.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
