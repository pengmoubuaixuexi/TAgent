package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.Map;

/** Trusted diagnostics for errors carried inside an otherwise successful HTTP SSE response. */
public final class ModelStreamException extends IllegalStateException {
    private final boolean retryable;
    private final boolean emptyAnswer;
    /** The provider's error object, for request-layer classification only; never part of the message. */
    private final transient Map<?, ?> providerError;

    private ModelStreamException(String message, boolean retryable, boolean emptyAnswer, Map<?, ?> providerError) {
        super(message);
        this.retryable = retryable;
        this.emptyAnswer = emptyAnswer;
        this.providerError = providerError;
    }

    public static ModelStreamException emptyAnswer() {
        return new ModelStreamException("模型结束了响应，但没有生成回答正文（可能只返回了思考内容）。请稍后重试或更换模型。", true, true, null);
    }

    public static ModelStreamException providerError(Object error) {
        // Never expose an arbitrary provider message: it may echo the prompt or credentials.
        Map<?, ?> fields = error instanceof Map<?, ?> map ? map : Map.of();
        String code = String.valueOf(fields.get("code"));
        String type = String.valueOf(fields.get("type"));
        if ("429".equals(code) || "rate_limit_error".equals(type))
            return new ModelStreamException("模型服务返回限流错误（429），本次生成未完成。请稍后重试或检查提供商额度。", true, false, fields);
        if ("401".equals(code) || "403".equals(code) || "authentication_error".equals(type))
            return new ModelStreamException("模型服务认证或授权失败，请检查模型连接设置。", false, false, fields);
        if ("400".equals(code) || "422".equals(code) || "invalid_request_error".equals(type))
            return new ModelStreamException("模型服务拒绝了请求参数，请检查模型与工具参数兼容性。", false, false, fields);
        boolean transientFailure = code.matches("5[0-9]{2}") || "server_error".equals(type);
        return new ModelStreamException("模型服务在流式响应中返回错误，本次生成未完成。请稍后重试或检查模型连接。", transientFailure, false, fields);
    }

    /** Null unless the provider sent an error object inside the stream. */
    public Map<?, ?> providerError() { return providerError; }

    public boolean canRetryAt(int attempt) {
        // A reasoning-only completion gets at most one extra attempt, never an endless loop.
        return retryable && (!emptyAnswer || attempt == 1);
    }
}
