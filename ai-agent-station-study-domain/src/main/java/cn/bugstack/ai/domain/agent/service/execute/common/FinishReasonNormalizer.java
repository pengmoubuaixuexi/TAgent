package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.Locale;

/**
 * P0-B2b-O1：把各 provider 的 finish reason 原值归一为<b>固定低基数枚举</b>，供 shadow 诊断指标使用。
 *
 * <p>用于区分 Step1/Step3 的 UNKNOWN 根因：{@code length}=输出撞 max-tokens 上限被截断（尾注丢失）；
 * {@code stop}=正常收尾（UNKNOWN 多半是职责越界/格式不遵循而非截断）。
 *
 * <p><b>纪律</b>：null / 空 / 无法识别一律 {@code unknown}，<b>绝不伪造 {@code length}</b>（避免把"没拿到 finish reason"
 * 误报成截断）。provider 任意字符串不得直接做 metric label，必须经本类归一。纯函数、零依赖。
 */
public final class FinishReasonNormalizer {

    private FinishReasonNormalizer() {
    }

    /** 固定枚举：stop|length|tool_calls|cancelled|unknown。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return "unknown";
        }
        switch (v) {
            case "stop":
            case "end_turn":
            case "endturn":
            case "complete":
            case "completed":
            case "finished":
                return "stop";
            case "length":
            case "max_tokens":
            case "maxtokens":
            case "max_output_tokens":
            case "model_length":
                return "length";
            case "tool_calls":
            case "toolcalls":
            case "tool_use":
            case "tooluse":
            case "function_call":
                return "tool_calls";
            case "cancelled":
            case "canceled":
            case "abort":
            case "aborted":
                return "cancelled";
            default:
                return "unknown";
        }
    }
}
