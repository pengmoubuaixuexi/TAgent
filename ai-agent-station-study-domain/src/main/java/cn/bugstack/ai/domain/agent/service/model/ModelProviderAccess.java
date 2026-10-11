package cn.bugstack.ai.domain.agent.service.model;

/** Resolves credentials only at the outgoing request boundary; never expose the return value in DTOs/logs. */
public interface ModelProviderAccess {
    String apiKeyForRequest(String apiId, String authenticatedUserId);
    default String apiKeyForRequest(String apiId, String authenticatedUserId, java.net.URI requestUri) {
        return apiKeyForRequest(apiId, authenticatedUserId);
    }
    void requirePlatformAuxiliary(String authenticatedUserId, String purpose);
}
