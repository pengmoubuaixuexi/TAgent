package cn.bugstack.ai.domain.agent.service.model;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import org.springframework.stereotype.Component;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Re-resolves secrets and revocation on every HTTP attempt, including recursive tool calls. */
@Component
public final class ModelCredentialRequestAuthorizer {
    private final ModelProviderAccess access;
    public ModelCredentialRequestAuthorizer(ModelProviderAccess access) { this.access = access; }
    public ClientHttpRequestInterceptor synchronous(String apiId) {
        return (request,body,next) -> {
            request.getHeaders().setBearerAuth(access.apiKeyForRequest(apiId, ModelInvocationScope.currentUser(), request.getURI()));
            return next.execute(request,body);
        };
    }
    public ExchangeFilterFunction streaming(String apiId) {
        return streaming(apiId,null);
    }
    public ExchangeFilterFunction streaming(String apiId,String auxiliaryPurpose) {
        return (request,next) -> {
            String capturedUser = ModelInvocationScope.currentUser();
            return Mono.deferContextual(context -> {
                String run = context.getOrDefault("agent.reasoning_run", null);
                var state = RunBudgetRegistry.find(run);
                String owner = state == null ? capturedUser : state.ownerUserId();
                return Mono.fromCallable(() -> {
                            if(state!=null)state.checkActive();
                            if(auxiliaryPurpose!=null)access.requirePlatformAuxiliary(owner,auxiliaryPurpose);
                            return access.apiKeyForRequest(apiId,owner,request.url());
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(key -> next.exchange(ClientRequest.from(request)
                                .headers(headers -> headers.setBearerAuth(key)).build()));
            });
        };
    }
    public ClientHttpRequestInterceptor platformAuxiliary(String purpose) {
        return (request,body,next) -> {
            access.requirePlatformAuxiliary(ModelInvocationScope.currentUser(), purpose);
            return next.execute(request,body);
        };
    }
}
