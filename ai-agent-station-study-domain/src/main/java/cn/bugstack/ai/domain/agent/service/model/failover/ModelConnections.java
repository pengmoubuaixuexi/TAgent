package cn.bugstack.ai.domain.agent.service.model.failover;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.service.model.ModelCredentialRequestAuthorizer;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.health.ModelFallbackRunReporter;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Production wiring of model connections: every agent connection and platform helper client gets
 * the request-layer failover (catalog candidates, health, run notice) and the single retry policy.
 */
@Slf4j
@Component
public class ModelConnections {
    private final ModelConnectionAssembler assembler;
    private final IAgentRepository repository;
    private final String noThinkBodyParams;

    public ModelConnections(ModelCredentialRequestAuthorizer credentials, ModelEndpointRegistry endpoints,
                            ModelCatalogPlanner catalog, ModelHealthRegistry health, ModelFallbackRunReporter runNotice,
                            @Lazy IAgentRepository repository,
                            @Value("${agent.no-think.body-params:}") String noThinkBodyParams,
                            @Value("${agent.model-failover.retry.attempts:3}") int attempts,
                            @Value("${agent.model-failover.retry.initial-backoff-ms:1000}") long initialBackoff,
                            @Value("${agent.model-failover.retry.max-backoff-ms:8000}") long maxBackoff) {
        this.repository = repository;
        this.noThinkBodyParams = noThinkBodyParams;
        this.assembler = new ModelConnectionAssembler(credentials, endpoints, catalog, ModelFailoverObserver.of(health, runNotice),
                new ModelRetryPolicy(Math.max(1, attempts), Duration.ofMillis(initialBackoff), Duration.ofMillis(maxBackoff)));
        endpoints.provisioner(this::provision);
    }

    /** An agent connection; its sender also serves other connections' substitutes. */
    public OpenAiApi assemble(AiClientApiVO api) {
        return assembler.assemble(new ModelConnectionAssembler.Settings(api.getApiId(), api.getBaseUrl(),
                api.getCompletionsPath(), api.getEmbeddingsPath(), noThinkBodyParams));
    }

    /** A platform helper client (router pool): checks and accounts the helper purpose on every request. */
    public OpenAiApi assembleHelper(AiClientApiVO api, String purpose) {
        return assembler.assemble(new ModelConnectionAssembler.Settings(api.getApiId(), api.getBaseUrl(),
                api.getCompletionsPath(), api.getEmbeddingsPath(), null, purpose, false));
    }

    /** A candidate connection that no agent has assembled yet in this process. */
    private ModelEndpointRegistry.Endpoint provision(String apiId) {
        List<AiClientApiVO> apis = repository.queryAiClientApiVOListByApiIds(List.of(apiId));
        if (apis.isEmpty() || apis.get(0) == null || apis.get(0).getBaseUrl() == null) return null;
        AiClientApiVO api = apis.get(0);
        log.info("[ModelFailover] assembling candidate connection apiId={}", apiId);
        return assembler.endpoint(new ModelConnectionAssembler.Settings(api.getApiId(), api.getBaseUrl(),
                api.getCompletionsPath(), api.getEmbeddingsPath(), noThinkBodyParams));
    }
}
