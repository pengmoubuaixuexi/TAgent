package cn.bugstack.ai.domain.agent.service.model;

/** Metadata from a server-assembled client. User and run identity always come from the trusted run budget. */
public record ModelUsageContext(String apiId,String purpose,String providerHost) {
    public static ModelUsageContext api(String apiId,String purpose,String baseUrl) {
        return new ModelUsageContext(apiId,purpose,java.net.URI.create(baseUrl).getHost());
    }
}
