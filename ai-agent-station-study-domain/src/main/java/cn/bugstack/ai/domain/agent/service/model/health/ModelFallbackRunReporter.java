package cn.bugstack.ai.domain.agent.service.model.health;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelCall;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailoverObserver;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Marks a run whose answer came from a substitute: one {@code model_fallback} event per run and
 * requested → actual pair, e.g. "本次由 mimo-v2.6-flash 代答（mimo-v2.5 暂不可用：模型不存在或已下线）".
 * Only the safe reason is published; provider text stays with administrators and owners.
 */
@Slf4j
@Component
public class ModelFallbackRunReporter implements ModelFailoverObserver {
    private final RunEventPublisher events;
    private final ModelHealthRegistry health;
    private final Cache<String, Boolean> reported = CacheBuilder.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofHours(6)).build();
    private final ExecutorService publisher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "model-fallback-events");
        thread.setDaemon(true);
        return thread;
    });

    public ModelFallbackRunReporter(@Lazy RunEventPublisher events, ModelHealthRegistry health) {
        this.events = events;
        this.health = health;
    }

    @Override
    public void substituted(ModelCall call, ModelTarget requested, ModelTarget actual, List<ModelFailure> failures) {
        if (call == null || call.runId() == null || RunBudgetRegistry.find(call.runId()) == null) return;
        if (reported.asMap().putIfAbsent(call.runId() + "|" + requested + "|" + actual, Boolean.TRUE) != null) return;
        String reason = failures.stream().filter(f -> f.target().equals(requested)).map(ModelFailure::reason).findFirst()
                .orElseGet(() -> health.snapshot(requested).map(r -> r.lastFailureKind() == null ? null
                        : new ModelFailure(requested, r.lastFailureKind(), r.lastFailureStatus() == null ? 0 : r.lastFailureStatus(), null).reason())
                        .orElse("暂不可用"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", call.runId());
        payload.put("requestedModel", requested.model());
        payload.put("actualModel", actual.model());
        payload.put("reason", reason);
        payload.put("notice", "本次由 " + actual.model() + " 代答（" + requested.model() + " 暂不可用：" + reason + "）");
        try {
            // Event persistence is I/O; request and stream threads never wait for it.
            publisher.execute(() -> {
                try {
                    events.publishToRun(call.runId(), call.sessionId(), "model_fallback", payload);
                } catch (RuntimeException error) {
                    log.warn("[ModelFallback] run event failed runId={}: {}", call.runId(), error.toString());
                }
            });
        } catch (RuntimeException rejected) {
            log.warn("[ModelFallback] run event dropped runId={}", call.runId());
        }
    }
}
