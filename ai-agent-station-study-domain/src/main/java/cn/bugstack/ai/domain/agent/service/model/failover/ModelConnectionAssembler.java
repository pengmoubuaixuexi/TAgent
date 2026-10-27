package cn.bugstack.ai.domain.agent.service.model.failover;

import cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.WireTraceRecorder;
import cn.bugstack.ai.domain.agent.service.model.ModelCredentialRequestAuthorizer;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageContext;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import cn.bugstack.ai.domain.agent.service.trace.ModelTraceFilter;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFunctions;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one model connection: the Spring AI client with the per-request guards and the failover
 * layer, plus the failover-free sender that other connections use when they hand a request over.
 */
public final class ModelConnectionAssembler {
    /**
     * @param auxiliaryPurpose platform helper purpose (e.g. CHAT_HELPER) checked and accounted per request; null for agent models
     * @param shared           register this connection's sender for other connections' substitutes; helper clients keep theirs private
     */
    public record Settings(String apiId, String baseUrl, String completionsPath, String embeddingsPath, String noThinkBodyParams,
                           String auxiliaryPurpose, boolean shared) {
        public Settings(String apiId, String baseUrl, String completionsPath, String embeddingsPath, String noThinkBodyParams) {
            this(apiId, baseUrl, completionsPath, embeddingsPath, noThinkBodyParams, null, true);
        }
    }

    private final ModelCredentialRequestAuthorizer credentials;
    private final ModelEndpointRegistry endpoints;
    private final ModelFallbackPlanner planner;
    private final ModelFailoverObserver observer;
    private final ModelRetryPolicy retry;

    public ModelConnectionAssembler(ModelCredentialRequestAuthorizer credentials, ModelEndpointRegistry endpoints,
                                    ModelFallbackPlanner planner, ModelFailoverObserver observer, ModelRetryPolicy retry) {
        this.credentials = credentials;
        this.endpoints = endpoints;
        this.planner = planner;
        this.observer = observer;
        this.retry = retry;
    }

    public OpenAiApi assemble(Settings settings) {
        return build(settings).api();
    }

    /** The sender only, for a connection that other connections fail over to. */
    public ModelEndpointRegistry.Endpoint endpoint(Settings settings) {
        return build(settings).endpoint();
    }

    private record Built(OpenAiApi api, ModelEndpointRegistry.Endpoint endpoint) { }

    private Built build(Settings settings) {
        String apiId = settings.apiId();
        String purpose = settings.auxiliaryPurpose();
        String completionsPath = OpenAiCompatibleApiSupport.chatCompletionsPath(settings.baseUrl(), settings.completionsPath());
        String embeddingsPath = OpenAiCompatibleApiSupport.embeddingsPath(settings.baseUrl(), settings.embeddingsPath());
        var usageContext = ModelUsageContext.api(apiId, purpose, settings.baseUrl());

        // Order matters: reasoning is injected first, so the wire trace logs the body actually sent.
        List<ExchangeFilterFunction> streamingGuards = new ArrayList<>();
        streamingGuards.add(new ReasoningContentFilter(settings.noThinkBodyParams()).withUsageContext(usageContext));
        streamingGuards.add(purpose == null ? credentials.streaming(apiId) : credentials.streaming(apiId, purpose));
        if (purpose == null) streamingGuards.add(new WireTraceRecorder());
        // Innermost: the run trace records each attempt exactly as it goes out.
        streamingGuards.add(new ModelTraceFilter(apiId, purpose));
        ExchangeFunction base = ExchangeFunctions.create(connector());
        ExchangeFunction guardedStreaming = streamingGuards.stream().reduce(ExchangeFilterFunction::andThen).orElseThrow().apply(base);

        ClientHttpRequestFactory requestFactory = ModelRequestGuard.requestFactory();
        List<ClientHttpRequestInterceptor> synchronousGuards = new ArrayList<>();
        if (purpose != null) synchronousGuards.add(credentials.platformAuxiliary(purpose));
        synchronousGuards.add(credentials.synchronous(apiId));
        synchronousGuards.add(ModelRequestGuard.synchronous(usageContext));
        URI completionsUri = UriComponentsBuilder.fromUriString(settings.baseUrl()).path(completionsPath).build().toUri();
        var endpoint = new ModelEndpointRegistry.Endpoint(apiId, completionsUri,
                new InterceptingClientHttpRequestFactory(requestFactory, synchronousGuards), guardedStreaming);
        if (settings.shared()) endpoints.register(endpoint);

        SynchronousModelFailover synchronous = new SynchronousModelFailover(apiId, endpoints, planner, observer, retry, purpose);
        synchronous.self(endpoint);
        WebClient.Builder webClient = WebClient.builder().exchangeFunction(base)
                .filter(new StreamingModelFailover(apiId, endpoints, planner, observer, retry, purpose))
                .filters(filters -> filters.addAll(streamingGuards));
        RestClient.Builder restClient = RestClient.builder().requestFactory(requestFactory)
                .requestInterceptor(synchronous)
                .requestInterceptors(interceptors -> interceptors.addAll(synchronousGuards));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(settings.baseUrl())
                .apiKey("managed-at-request-boundary")
                .completionsPath(completionsPath)
                .embeddingsPath(embeddingsPath)
                .restClientBuilder(restClient)
                .webClientBuilder(webClient)
                .build();
        return new Built(api, endpoint);
    }

    /** Same choice as WebClient's default; the application ships without Reactor Netty, so the JDK client. */
    private static ClientHttpConnector connector() {
        return REACTOR_NETTY ? new ReactorClientHttpConnector() : new JdkClientHttpConnector();
    }

    private static final boolean REACTOR_NETTY = ClassUtils.isPresent(
            "reactor.netty.http.client.HttpClient", ModelConnectionAssembler.class.getClassLoader());
}
