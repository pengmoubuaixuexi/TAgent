package cn.bugstack.ai.domain.agent.service.model.failover;

import cn.bugstack.ai.domain.agent.service.execute.common.ModelStreamException;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Outermost WebClient filter of one connection. A substitute is tried before anything reaches
 * Spring AI: on an HTTP error status, or on an error inside the stream before its first meaningful
 * delta ({@link FirstOutputGuard}). Once output has been released, a failure stays a step failure.
 */
public final class StreamingModelFailover implements ExchangeFilterFunction {
    /** Server-owned MDC keys that ReactorContextPropagationConfig restores on worker threads. */
    private static final String[] PROPAGATED_MDC = {"sessionId", "userId", "tenantId", "runId", "agent.run_id", "modelPurpose"};
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String apiId;
    private final ModelEndpointRegistry endpoints;
    private final ModelFallbackPlanner planner;
    private final ModelFailoverObserver observer;
    private final ModelRetryPolicy retry;
    private final String purpose;

    public StreamingModelFailover(String apiId, ModelEndpointRegistry endpoints, ModelFallbackPlanner planner,
                                  ModelFailoverObserver observer, ModelRetryPolicy retry) {
        this(apiId, endpoints, planner, observer, retry, null);
    }

    /** @param purpose auxiliary usage purpose recorded for substitute attempts, or null for agent models */
    public StreamingModelFailover(String apiId, ModelEndpointRegistry endpoints, ModelFallbackPlanner planner,
                                  ModelFailoverObserver observer, ModelRetryPolicy retry, String purpose) {
        this.apiId = apiId;
        this.endpoints = endpoints;
        this.planner = planner;
        this.observer = observer;
        this.retry = retry;
        this.purpose = purpose;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (request.method() != HttpMethod.POST) return next.exchange(request);
        Map<String, Object> chat = ModelRequestBodies.chat(ModelRequestBodies.readNow(request));
        if (chat == null) return next.exchange(request);
        ModelTarget requested = new ModelTarget(apiId, (String) chat.get("model"));
        List<ModelTarget> route = ModelRequestBodies.route(requested, ModelRequestTraits.of(chat), planner, observer);
        // Later attempts subscribe on reactor threads: keep the caller's reasoning scope, activity and identity.
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Map<String, String> reasoning = ReasoningContentFilter.captureContext();
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.current();
        return Mono.deferContextual(context -> new Attempts(request, next, chat, requested, route, mdc, call(context, mdc)).run(0, 1))
                .contextWrite(context -> {
                    Context result = context;
                    for (var entry : reasoning.entrySet()) result = putIfAbsent(result, entry.getKey(), entry.getValue());
                    if (activity != null) result = putIfAbsent(result, StreamingActivityTracker.CONTEXT_KEY, activity);
                    if (mdc != null) for (String key : PROPAGATED_MDC) result = putIfAbsent(result, key, mdc.get(key));
                    if (purpose != null) result = putIfAbsent(result, "modelPurpose", purpose);
                    return result;
                });
    }

    private static Context putIfAbsent(Context context, String key, Object value) {
        return value == null || context.hasKey(key) ? context : context.put(key, value);
    }

    private static ModelCall call(ContextView context, Map<String, String> mdc) {
        String run = context.getOrDefault(ReasoningContentFilter.RUN_CONTEXT_KEY, null);
        if (run == null || RunBudgetRegistry.find(run) == null) run = mdc == null ? null : mdc.get("runId");
        String session = context.getOrDefault(ReasoningContentFilter.SESSION_CONTEXT_KEY, null);
        if (session == null || "unknown-session".equals(session)) session = mdc == null ? null : mdc.get("sessionId");
        return new ModelCall(run, session);
    }

    private record TransportFailure(ModelFailure failure, Throwable error) { }

    private record NotPermitted() { }

    private final class Attempts {
        private final ClientRequest request;
        private final ExchangeFunction next;
        private final Map<String, Object> chat;
        private final ModelTarget requested;
        private final List<ModelTarget> route;
        private final Map<String, String> mdc;
        private final ModelCall call;
        private final List<ModelFailure> failures = new ArrayList<>();

        Attempts(ClientRequest request, ExchangeFunction next, Map<String, Object> chat, ModelTarget requested,
                 List<ModelTarget> route, Map<String, String> mdc, ModelCall call) {
            this.request = request;
            this.next = next;
            this.chat = chat;
            this.requested = requested;
            this.route = route;
            this.mdc = mdc;
            this.call = call;
        }

        Mono<ClientResponse> run(int index, int attempt) {
            if (index >= route.size()) return Mono.error(new ModelsUnavailableException(requested, failures));
            ModelTarget target = route.get(index);
            if (target.apiId().equals(apiId)) return attempt(target, null, index, attempt);
            // Provisioning a lazily assembled connection reads the database: never on an I/O thread.
            return Mono.fromCallable(() -> Optional.ofNullable(endpoints.find(target.apiId())))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(found -> {
                        if (found.isPresent()) return attempt(target, found.get(), index, attempt);
                        failures.add(ModelRequestBodies.unusable(target, "候选连接不可用"));
                        return run(index + 1, 1);
                    });
        }

        private Mono<ClientResponse> attempt(ModelTarget target, ModelEndpointRegistry.Endpoint endpoint, int index, int attempt) {
            // The first attempt runs on the caller's thread, exactly like the unfiltered chain.
            Mono<ClientResponse> exchange = index == 0 && attempt == 1 && endpoint == null ? send(target, null)
                    : Mono.defer(() -> withCallerMdc(() -> send(target, endpoint)));
            // Classify only this attempt's own error; later attempts handle theirs.
            return exchange.<Object>map(response -> response)
                    .onErrorResume(error -> {
                        if (!target.equals(requested) && notPermitted(error)) return Mono.just(new NotPermitted());
                        ModelFailure failure = ModelFailureClassifier.classify(target, error);
                        return failure == null ? Mono.error(error) : Mono.just(new TransportFailure(failure, error));
                    })
                    .flatMap(result -> {
                        if (result instanceof NotPermitted) {
                            failures.add(ModelRequestBodies.unusable(target, "当前账号无权使用该候选模型"));
                            return run(index + 1, 1);
                        }
                        if (result instanceof TransportFailure transport) return retryOrFail(transport.failure(), transport.error(), index, attempt, null);
                        ClientResponse response = (ClientResponse) result;
                        return response.statusCode().isError() ? failed(response, target, index, attempt) : streamed(response, target, index, attempt);
                    });
        }

        private Mono<ClientResponse> send(ModelTarget target, ModelEndpointRegistry.Endpoint endpoint) {
            if (target.equals(requested)) return next.exchange(request);
            ClientRequest.Builder substitute = ClientRequest.from(request)
                    .body(BodyInserters.fromValue(ModelRequestBodies.withModel(chat, target.model())));
            if (endpoint == null) return next.exchange(substitute.build());
            return endpoint.streaming().exchange(substitute.url(endpoint.completionsUri())
                    .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION)).build());
        }

        private Mono<ClientResponse> failed(ClientResponse response, ModelTarget target, int index, int attempt) {
            // Reading the body completes the attempt, so its usage ticket and reservation settle now.
            return response.bodyToMono(byte[].class).onErrorReturn(new byte[0]).defaultIfEmpty(new byte[0]).flatMap(body -> {
                ModelFailure failure = ModelFailureClassifier.classify(target, response.statusCode().value(), body);
                if (failure.kind() == ModelFailure.Kind.REQUEST_REJECTED) {
                    observer.failed(failure);
                    return Mono.just(ClientResponse.from(response)
                            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body))).build());
                }
                return retryOrFail(failure, null, index, attempt, ModelRequestBodies.retryAfter(response.headers().asHttpHeaders()));
            });
        }

        /** A 2xx response: hold its body until the first output, so an early stream error can still switch. */
        private Mono<ClientResponse> streamed(ClientResponse response, ModelTarget target, int index, int attempt) {
            Flux<DataBuffer> body = FirstOutputGuard.apply(response.body(BodyExtractors.toDataBuffers()),
                    () -> {
                        observer.succeeded(target);
                        if (!target.equals(requested)) observer.substituted(call, requested, target, List.copyOf(failures));
                    },
                    error -> {
                        ModelFailure failure = streamFailure(target, error);
                        if (failure == null) return null;
                        if (failure.kind() == ModelFailure.Kind.REQUEST_REJECTED) { observer.failed(failure); return null; }
                        return retryOrFail(failure, error, index, attempt, null).flatMapMany(StreamingModelFailover::bodyOrError);
                    });
            return Mono.just(ClientResponse.from(response).body(body).build());
        }

        /** Unavailable: next candidate. Transient: same candidate again, never another one. */
        private Mono<ClientResponse> retryOrFail(ModelFailure failure, Throwable error, int index, int attempt, Duration retryAfter) {
            observer.failed(failure);
            if (failure.kind() == ModelFailure.Kind.MODEL_UNAVAILABLE) {
                failures.add(failure);
                return run(index + 1, 1);
            }
            if (attempt < retry.attemptsPerTarget())
                return Mono.delay(retry.delay(attempt, retryAfter)).then(Mono.defer(() -> run(index, attempt + 1)));
            ModelRetriesExhaustedException exhausted = new ModelRetriesExhaustedException(failure, attempt);
            if (error != null) exhausted.addSuppressed(error);
            return Mono.error(exhausted);
        }

        /** Downstream filters read the caller identity from MDC while they are invoked. */
        private <T> T withCallerMdc(Supplier<T> call) {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (mdc == null) MDC.clear(); else MDC.setContextMap(mdc);
                if (purpose != null) MDC.put("modelPurpose", purpose);
                return call.get();
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        }
    }

    private static Flux<DataBuffer> bodyOrError(ClientResponse response) {
        // The 200 status was already handed over, so a later error status becomes a body error.
        return response.statusCode().isError() ? response.createException().flatMapMany(Flux::error)
                : response.body(BodyExtractors.toDataBuffers());
    }

    /** A provider error frame or a broken connection before any output; null for anything else. */
    private static ModelFailure streamFailure(ModelTarget target, Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ModelStreamException stream && stream.providerError() != null)
                return ModelFailureClassifier.classifyStreamError(target, JSON.valueToTree(stream.providerError()));
            if (t.getCause() == t) break;
        }
        return ModelFailureClassifier.classify(target, error);
    }

    private static boolean notPermitted(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SecurityException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
