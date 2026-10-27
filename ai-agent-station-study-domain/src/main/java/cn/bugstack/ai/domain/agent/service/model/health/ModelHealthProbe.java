package cn.bugstack.ai.domain.agent.service.model.health;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelEndpointRegistry;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailureClassifier;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Minimal completion ({@code max_tokens: 1}, about ten tokens) against unhealthy models only, with
 * backoff, plus the manual "check now". Platform models are probed as the system; a personal model
 * only as its owner, on the owner's own key.
 */
@Slf4j
@Component
public class ModelHealthProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModelHealthRegistry health;
    private final ModelEndpointRegistry endpoints;
    private final ModelCatalogPlanner catalog;
    private final RunBudgetRegistry budgets;
    @Value("${agent.model-failover.probe.enabled:true}") private boolean enabled = true;

    public ModelHealthProbe(ModelHealthRegistry health, ModelEndpointRegistry endpoints, ModelCatalogPlanner catalog,
                            RunBudgetRegistry budgets) {
        this.health = health;
        this.endpoints = endpoints;
        this.catalog = catalog;
        this.budgets = budgets;
    }

    /** Result of one check: the model answered ({@code failure == null}), or why it did not. */
    public record Result(ModelTarget target, boolean available, ModelFailure failure) {
        public String reason() { return available ? "可用" : failure.describe(); }
    }

    @Scheduled(fixedDelayString = "${agent.model-failover.probe.interval-ms:60000}",
            initialDelayString = "${agent.model-failover.probe.initial-delay-ms:60000}")
    public void probeUnhealthy() {
        if (!enabled) return;
        for (ModelTarget target : health.dueForProbe()) {
            if (!catalog.referenced(target)) {
                health.forget(target);
                log.info("[ModelHealth] {} is no longer configured; stopped tracking it", target);
                continue;
            }
            try {
                Result result = check(target);
                log.info("[ModelHealth] probe {} -> {}", target, result.reason());
            } catch (RuntimeException error) {
                log.warn("[ModelHealth] probe {} could not run: {}", target, error.toString());
            }
        }
    }

    /** Sends one minimal request and records the outcome. The caller has already checked ownership. */
    public Result check(ModelTarget target) {
        String scope = catalog.scopeOf(target.apiId());
        ModelEndpointRegistry.Endpoint endpoint = scope == null ? null : endpoints.find(target.apiId());
        if (endpoint == null) {
            return new Result(target, false, new ModelFailure(target, ModelFailure.Kind.MODEL_UNAVAILABLE, 0, "连接已停用或不存在"));
        }
        ModelFailure failure;
        try (ModelInvocationScope ignored = ModelCatalogEntry.PLATFORM.equals(scope)
                ? ModelInvocationScope.openSystem(budgets, ModelInvocationScope.SystemPurpose.SYSTEM_MAINTENANCE)
                : ModelInvocationScope.open(budgets, scope, "MODEL_HEALTH_PROBE")) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            byte[] body = JSON.writeValueAsBytes(Map.of("model", target.model(), "max_tokens", 1, "stream", false,
                    "messages", List.of(Map.of("role", "user", "content", "ping"))));
            try (ClientHttpResponse response = endpoint.execute(headers, body)) {
                int status = response.getStatusCode().value();
                failure = status < 400 ? null : ModelFailureClassifier.classify(target, status, read(response));
            }
        } catch (IOException error) {
            failure = ModelFailureClassifier.classify(target, error);
        }
        health.probed(target, failure);
        boolean available = failure == null || failure.kind() == ModelFailure.Kind.REQUEST_REJECTED;
        return new Result(target, available, failure);
    }

    private static byte[] read(ClientHttpResponse response) throws IOException {
        try (InputStream in = response.getBody()) {
            return in.readNBytes(64 * 1024);
        }
    }
}
