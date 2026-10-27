package cn.bugstack.ai.domain.agent.service.model.failover;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Failover-free senders for every assembled connection. A substitute request always uses the
 * substitute connection's own credentials, budget guard and reasoning cache, and cannot fail over again.
 * Connections are assembled lazily, so a missing sender is provisioned on first use.
 */
@Component
public final class ModelEndpointRegistry {
    /** Hop-by-hop or per-connection headers that each connection sets for itself. */
    private static final Set<String> CONNECTION_HEADERS = Set.of(
            HttpHeaders.AUTHORIZATION.toLowerCase(), HttpHeaders.CONTENT_LENGTH.toLowerCase(),
            HttpHeaders.HOST.toLowerCase(), HttpHeaders.TRANSFER_ENCODING.toLowerCase(), HttpHeaders.COOKIE.toLowerCase());

    /**
     * @param synchronous credentials and budget interceptors over the guarded request factory
     * @param streaming   reasoning, credentials and wire-trace filters over the base exchange
     */
    public record Endpoint(String apiId, URI completionsUri, ClientHttpRequestFactory synchronous, ExchangeFunction streaming) {
        public ClientHttpResponse execute(HttpHeaders headers, byte[] body) throws IOException {
            ClientHttpRequest request = synchronous.createRequest(completionsUri, HttpMethod.POST);
            headers.forEach((name, values) -> {
                if (!CONNECTION_HEADERS.contains(name.toLowerCase())) request.getHeaders().addAll(name, values);
            });
            request.getBody().write(body);
            return request.execute();
        }
    }

    /** Builds the sender of a connection that has not been assembled yet; null when it is unknown or disabled. */
    @FunctionalInterface
    public interface Provisioner {
        Endpoint provision(String apiId);
    }

    private final ConcurrentMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private volatile Provisioner provisioner;

    /** Re-assembly replaces the previous sender for the same connection. */
    public void register(Endpoint endpoint) { endpoints.put(endpoint.apiId(), endpoint); }

    public void provisioner(Provisioner provisioner) { this.provisioner = provisioner; }

    /** Null when the connection cannot be used; callers skip that candidate. */
    public Endpoint find(String apiId) {
        Endpoint endpoint = endpoints.get(apiId);
        if (endpoint != null || provisioner == null) return endpoint;
        Endpoint provisioned;
        try {
            provisioned = provisioner.provision(apiId);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (provisioned == null) return null;
        Endpoint raced = endpoints.putIfAbsent(apiId, provisioned);
        return raced == null ? provisioned : raced;
    }
}
