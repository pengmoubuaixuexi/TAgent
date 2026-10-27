package cn.bugstack.ai.domain.agent.service.model.failover;

/**
 * One failed attempt. {@link #reason()} is safe to show to anyone; {@link #message()} is the
 * redacted provider text, shown only to administrators (platform models) or the model owner.
 */
public record ModelFailure(ModelTarget target, Kind kind, int status, String message) {
    public enum Kind {
        /** Network, timeout or provider overload: retry the same model, never switch. */
        TRANSIENT,
        /** Ordinary rate limit: retry the same model after Retry-After. */
        RATE_LIMITED,
        /** Model removed, credentials revoked or balance exhausted: switch and mark unhealthy. */
        MODEL_UNAVAILABLE,
        /** The request itself is invalid, e.g. context too long: return the error unchanged. */
        REQUEST_REJECTED;

        public boolean retryable() { return this == TRANSIENT || this == RATE_LIMITED; }
    }

    /** Fixed wording by kind and status, never provider text. */
    public String reason() {
        return switch (kind) {
            case MODEL_UNAVAILABLE -> switch (status) {
                case 0 -> message == null || message.isBlank() ? "连接不可用" : message;
                case 401 -> "鉴权失败，密钥无效或已撤销";
                case 402 -> "账户余额不足";
                case 403 -> "无权使用该模型";
                case 404 -> "接口地址或模型不存在";
                case 429 -> "额度已用尽";
                default -> "模型不存在或已下线";
            };
            case RATE_LIMITED -> "请求过于频繁，已被限流";
            case TRANSIENT -> status > 0 ? "服务暂时不可用" : "网络错误或超时";
            case REQUEST_REJECTED -> "请求被拒绝（参数或上下文不符合要求）";
        };
    }

    /** Short reason with the status code, e.g. for the all-models-unavailable error. */
    public String describe() {
        return status > 0 ? reason() + "，HTTP " + status : reason();
    }
}
