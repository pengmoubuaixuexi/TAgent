package cn.bugstack.ai.domain.agent.service.support;

public final class OpenAiCompatibleApiSupport {

    private static final String V1_SEGMENT = "/v1";

    private OpenAiCompatibleApiSupport() {
    }

    public static String valueOrDefault(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : fallback;
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static String chatCompletionsPath(String baseUrl, String configuredPath) {
        return resolvePath(baseUrl, configuredPath, "chat/completions");
    }

    public static String embeddingsPath(String baseUrl, String configuredPath) {
        return resolvePath(baseUrl, configuredPath, "embeddings");
    }

    private static String resolvePath(String baseUrl, String configuredPath, String suffix) {
        if (hasText(configuredPath)) {
            return normalizePath(baseUrl, configuredPath.trim());
        }
        return endsWithV1(baseUrl) ? "/" + suffix : "/v1/" + suffix;
    }

    private static boolean endsWithV1(String baseUrl) {
        if (!hasText(baseUrl)) {
            return false;
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith(V1_SEGMENT);
    }

    private static String normalizePath(String baseUrl, String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        if (endsWithV1(baseUrl) && result.startsWith("v1/")) {
            result = result.substring("v1/".length());
        }
        return "/" + result;
    }
}
