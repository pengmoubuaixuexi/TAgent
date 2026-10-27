package cn.bugstack.ai.domain.agent.service.model.failover;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Outermost RestClient interceptor of one connection. Spring's interceptor chain can be walked only
 * once per request, so the first attempt on this connection uses the chain; a retry on this
 * connection uses {@code self} (the same guards), and a substitute uses the target connection's own
 * failover-free sender.
 */
public final class SynchronousModelFailover implements ClientHttpRequestInterceptor {
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private final String apiId;
    private final ModelEndpointRegistry endpoints;
    private final ModelFallbackPlanner planner;
    private final ModelFailoverObserver observer;
    private final ModelRetryPolicy retry;
    private final String purpose;
    private volatile ModelEndpointRegistry.Endpoint self;

    public SynchronousModelFailover(String apiId, ModelEndpointRegistry endpoints, ModelFallbackPlanner planner,
                                    ModelFailoverObserver observer, ModelRetryPolicy retry) {
        this(apiId, endpoints, planner, observer, retry, null);
    }

    /** @param purpose auxiliary usage purpose recorded for substitute attempts, or null for agent models */
    public SynchronousModelFailover(String apiId, ModelEndpointRegistry endpoints, ModelFallbackPlanner planner,
                                    ModelFailoverObserver observer, ModelRetryPolicy retry, String purpose) {
        this.apiId = apiId;
        this.endpoints = endpoints;
        this.planner = planner;
        this.observer = observer;
        this.retry = retry;
        this.purpose = purpose;
    }

    /** The sender with this client's own guards, set by the assembler. */
    void self(ModelEndpointRegistry.Endpoint endpoint) { this.self = endpoint; }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Map<String, Object> chat = request.getMethod() == HttpMethod.POST ? ModelRequestBodies.chat(body) : null;
        if (chat == null) return execution.execute(request, body);
        ModelTarget requested = new ModelTarget(apiId, (String) chat.get("model"));
        List<ModelTarget> route = ModelRequestBodies.route(requested, ModelRequestTraits.of(chat), planner, observer);
        List<ModelFailure> failures = new ArrayList<>();
        boolean chainUnused = true;
        for (ModelTarget target : route) {
            boolean sameConnection = target.apiId().equals(apiId);
            ModelEndpointRegistry.Endpoint other = sameConnection ? null : endpoints.find(target.apiId());
            if (!sameConnection && other == null) {
                failures.add(ModelRequestBodies.unusable(target, "候选连接不可用"));
                continue;
            }
            byte[] payload = target.model().equals(requested.model())
                    ? body : ModelRequestBodies.bytes(ModelRequestBodies.withModel(chat, target.model()));
            for (int attempt = 1; ; attempt++) {
                ClientHttpResponse response;
                try {
                    if (chainUnused && sameConnection) {
                        chainUnused = false;
                        response = execution.execute(request, payload);
                    } else {
                        response = send(sameConnection ? self : other, request.getHeaders(), payload);
                    }
                } catch (IOException error) {
                    ModelFailure failure = ModelFailureClassifier.classify(target, error);
                    observer.failed(failure);
                    if (attempt < retry.attemptsPerTarget()) { pause(retry.delay(attempt, null)); continue; }
                    throw new ModelRetriesExhaustedException(failure, attempt);
                } catch (SecurityException notPermitted) {
                    // The requested model keeps its authorization error; a candidate that the
                    // caller may not use is skipped instead of failing the whole request.
                    if (target.equals(requested)) throw notPermitted;
                    failures.add(ModelRequestBodies.unusable(target, "当前账号无权使用该候选模型"));
                    break;
                }
                HttpStatusCode status = response.getStatusCode();
                if (!status.isError()) {
                    observer.succeeded(target);
                    if (!target.equals(requested)) observer.substituted(call(), requested, target, List.copyOf(failures));
                    return response;
                }
                byte[] error = readBounded(response);
                ModelFailure failure = ModelFailureClassifier.classify(target, status.value(), error);
                observer.failed(failure);
                if (failure.kind() == ModelFailure.Kind.MODEL_UNAVAILABLE) {
                    response.close();
                    failures.add(failure);
                    break;
                }
                if (failure.kind().retryable()) {
                    response.close();
                    if (attempt >= retry.attemptsPerTarget()) throw new ModelRetriesExhaustedException(failure, attempt);
                    pause(retry.delay(attempt, ModelRequestBodies.retryAfter(response.getHeaders())));
                    continue;
                }
                return replay(response, error);
            }
        }
        throw new ModelsUnavailableException(requested, failures);
    }

    private ClientHttpResponse send(ModelEndpointRegistry.Endpoint endpoint, HttpHeaders headers, byte[] payload) throws IOException {
        if (endpoint == null) throw new IllegalStateException("模型连接未登记发送器：" + apiId);
        if (purpose == null || endpoint == self) return endpoint.execute(headers, payload);
        // A substitute connection has no auxiliary purpose of its own; account it as this client's purpose.
        String previous = MDC.get("modelPurpose");
        MDC.put("modelPurpose", purpose);
        try {
            return endpoint.execute(headers, payload);
        } finally {
            if (previous == null) MDC.remove("modelPurpose"); else MDC.put("modelPurpose", previous);
        }
    }

    private static ModelCall call() {
        RunBudgetRegistry.State budget = RunBudgetRegistry.find(Map.of());
        return new ModelCall(budget == null ? MDC.get("runId") : budget.runId(), MDC.get("sessionId"));
    }

    private static void pause(Duration delay) {
        if (delay.isZero()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Not an IOException: Spring AI would retry a ResourceAccessException.
            throw new CancellationException("任务已取消");
        }
    }

    private static byte[] readBounded(ClientHttpResponse response) throws IOException {
        try (InputStream in = response.getBody()) {
            return in.readNBytes(MAX_ERROR_BYTES);
        }
    }

    private static ClientHttpResponse replay(ClientHttpResponse delegate, byte[] body) {
        return new ClientHttpResponse() {
            @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
            @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
            @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
            @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override public void close() { delegate.close(); }
        };
    }
}
