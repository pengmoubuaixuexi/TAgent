package cn.bugstack.ai.domain.agent.service.model.health;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelHealthRepository;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailoverObserver;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Health per connection + model.
 * <ul>
 *   <li>A model or account failure (removed model, revoked key, no balance) marks the target unhealthy at once.</li>
 *   <li>Network failures, 5xx and rate limits only count: the target becomes unhealthy after
 *       {@code failureThreshold} of them within the window, or when more than {@code failureRate} of at least
 *       {@code minSamples} recent requests failed. A request error (bad parameters) is not a health signal.</li>
 *   <li>An unhealthy target is skipped while a healthy candidate exists; any success (probe, manual check,
 *       or a request when every candidate was unhealthy) restores it.</li>
 * </ul>
 * State changes are written asynchronously and announced to {@link Listener}s; request threads never wait for I/O.
 */
@Slf4j
@Component
public class ModelHealthRegistry implements ModelFailoverObserver {
    public enum Change { BECAME_UNHEALTHY, STILL_UNHEALTHY, RECOVERED }

    /** Called on the registry's worker thread, never on a request thread. */
    @FunctionalInterface
    public interface Listener {
        void changed(Change change, ModelHealthRecord record, ModelFailure failure);
    }

    /** Probe backoff for a target that stays unhealthy: 1, 2, 5, then every 10 minutes. */
    private static final long[] PROBE_BACKOFF_MILLIS = {60_000, 120_000, 300_000, 600_000};

    private final ConcurrentMap<ModelTarget, Health> targets = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "model-health");
        thread.setDaemon(true);
        return thread;
    });
    private final IModelHealthRepository repository;
    private final LongSupplier clock;
    @Value("${agent.model-failover.health.window-seconds:60}") private long windowSeconds = 60;
    @Value("${agent.model-failover.health.failure-threshold:5}") private int failureThreshold = 5;
    @Value("${agent.model-failover.health.failure-rate:0.5}") private double failureRate = 0.5;
    @Value("${agent.model-failover.health.min-samples:4}") private int minSamples = 4;

    @Autowired
    public ModelHealthRegistry(Optional<IModelHealthRepository> repository) {
        this(repository.orElse(null), System::currentTimeMillis);
    }

    public ModelHealthRegistry(IModelHealthRepository repository, LongSupplier clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @jakarta.annotation.PostConstruct
    public void load() {
        if (repository == null) return;
        try {
            for (ModelHealthRecord record : repository.loadAll()) targets.put(record.target(), new Health(record));
        } catch (RuntimeException unavailable) {
            log.warn("[ModelHealth] could not load persisted health, starting with all models healthy: {}", unavailable.toString());
        }
    }

    public void addListener(Listener listener) { listeners.add(listener); }

    @Override public boolean available(ModelTarget target) {
        Health health = targets.get(target);
        return health == null || health.healthy;
    }

    @Override public void succeeded(ModelTarget target) {
        Health health = targets.get(target);
        if (health == null) return; // Never failed: nothing to track.
        ModelHealthRecord recovered = null;
        synchronized (health) {
            long now = clock.getAsLong();
            health.record(now, false, windowMillis());
            health.consecutiveFailures = 0;
            health.lastSuccessAt = now;
            if (!health.healthy) {
                health.healthy = true;
                health.probeFailures = 0;
                recovered = health.snapshot();
                health.unhealthySince = null;
            }
        }
        if (recovered != null) {
            ModelHealthRecord stored = snapshot(target).orElse(recovered);
            announce(Change.RECOVERED, recovered, null, stored);
        }
    }

    @Override public void failed(ModelFailure failure) {
        if (failure == null || failure.kind() == ModelFailure.Kind.REQUEST_REJECTED) return;
        Health health = targets.computeIfAbsent(failure.target(), Health::new);
        Change change = null;
        ModelHealthRecord record;
        synchronized (health) {
            long now = clock.getAsLong();
            health.record(now, true, windowMillis());
            health.consecutiveFailures++;
            health.lastFailure = failure;
            health.lastFailureAt = now;
            if (health.healthy && trips(health, failure)) {
                health.healthy = false;
                health.unhealthySince = now;
                health.probeFailures = 0;
                change = Change.BECAME_UNHEALTHY;
            } else if (!health.healthy) {
                change = Change.STILL_UNHEALTHY;
            }
            record = health.snapshot();
        }
        if (change == Change.BECAME_UNHEALTHY)
            log.warn("[ModelHealth] {} unhealthy: {} ({})", failure.target(), failure.describe(), failure.kind());
        if (change != null) announce(change, record, failure, record);
        else persist(record);
    }

    /** Result of a background probe or a manual check; null failure means the model answered. */
    public void probed(ModelTarget target, ModelFailure failure) {
        Health health = targets.computeIfAbsent(target, Health::new);
        synchronized (health) {
            health.lastProbeAt = clock.getAsLong();
            if (failure != null && failure.kind() != ModelFailure.Kind.REQUEST_REJECTED) health.probeFailures++;
        }
        // A request error means the model exists and accepted the credentials.
        if (failure == null || failure.kind() == ModelFailure.Kind.REQUEST_REJECTED) succeeded(target);
        else failed(failure);
    }

    public Optional<ModelHealthRecord> snapshot(ModelTarget target) {
        Health health = targets.get(target);
        if (health == null) return Optional.empty();
        synchronized (health) { return Optional.of(health.snapshot()); }
    }

    /** Unhealthy targets whose probe backoff has elapsed. */
    public List<ModelTarget> dueForProbe() {
        long now = clock.getAsLong();
        return targets.values().stream().filter(health -> {
            synchronized (health) {
                if (health.healthy) return false;
                long last = health.lastProbeAt == null ? (health.unhealthySince == null ? 0 : health.unhealthySince) : health.lastProbeAt;
                long backoff = PROBE_BACKOFF_MILLIS[Math.min(health.probeFailures, PROBE_BACKOFF_MILLIS.length - 1)];
                return now - last >= backoff;
            }
        }).map(health -> health.target).toList();
    }

    /** Remember when a notification went out, so it survives a restart with the health state. */
    public void notified(ModelTarget target, long at) {
        Health health = targets.get(target);
        if (health == null) return;
        ModelHealthRecord record;
        synchronized (health) {
            health.lastNotifiedAt = at;
            record = health.snapshot();
        }
        persist(record);
    }

    /** A model nothing uses any more: stop tracking and probing it. */
    public void forget(ModelTarget target) {
        if (targets.remove(target) == null || repository == null) return;
        execute(() -> {
            try {
                repository.delete(target);
            } catch (RuntimeException unavailable) {
                log.warn("[ModelHealth] could not delete {}: {}", target, unavailable.toString());
            }
        });
    }

    /** Waits until earlier state changes were persisted and announced (tests, orderly shutdown). */
    public boolean awaitIdle(java.time.Duration timeout) {
        try {
            return worker.submit(() -> { }).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) == null;
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean trips(Health health, ModelFailure failure) {
        if (failure.kind() == ModelFailure.Kind.MODEL_UNAVAILABLE) return true;
        int failures = health.recentFailures(), samples = health.window.size();
        return failures >= failureThreshold || samples >= minSamples && failures > failureRate * samples;
    }

    private long windowMillis() { return Math.max(1, windowSeconds) * 1000L; }

    private void announce(Change change, ModelHealthRecord record, ModelFailure failure, ModelHealthRecord persisted) {
        execute(() -> {
            save(persisted);
            for (Listener listener : listeners) {
                try {
                    listener.changed(change, record, failure);
                } catch (RuntimeException error) {
                    log.warn("[ModelHealth] listener failed for {}: {}", record.target(), error.toString());
                }
            }
        });
    }

    private void persist(ModelHealthRecord record) { execute(() -> save(record)); }

    private void save(ModelHealthRecord record) {
        if (repository == null) return;
        try {
            repository.save(record);
        } catch (RuntimeException unavailable) {
            log.warn("[ModelHealth] could not persist {}: {}", record.target(), unavailable.toString());
        }
    }

    private void execute(Runnable task) {
        try {
            worker.execute(task);
        } catch (RuntimeException rejected) {
            log.warn("[ModelHealth] worker unavailable: {}", rejected.toString());
        }
    }

    private static final class Health {
        final ModelTarget target;
        /** Recent outcomes inside the window: {time, 1 = failure / 0 = success}. */
        final ArrayDeque<long[]> window = new ArrayDeque<>();
        boolean healthy = true;
        int consecutiveFailures;
        int probeFailures;
        ModelFailure lastFailure;
        Long lastFailureAt, lastSuccessAt, unhealthySince, lastProbeAt, lastNotifiedAt;

        Health(ModelTarget target) { this.target = target; }

        Health(ModelHealthRecord record) {
            this.target = record.target();
            this.healthy = record.healthy();
            this.consecutiveFailures = record.consecutiveFailures();
            this.lastFailure = record.lastFailureKind() == null ? null : new ModelFailure(record.target(), record.lastFailureKind(),
                    record.lastFailureStatus() == null ? 0 : record.lastFailureStatus(), record.lastFailureMessage());
            this.lastFailureAt = record.lastFailureAt();
            this.lastSuccessAt = record.lastSuccessAt();
            this.unhealthySince = record.unhealthySince();
            this.lastProbeAt = record.lastProbeAt();
            this.lastNotifiedAt = record.lastNotifiedAt();
        }

        void record(long now, boolean failure, long windowMillis) {
            window.addLast(new long[]{now, failure ? 1 : 0});
            while (!window.isEmpty() && now - window.peekFirst()[0] > windowMillis) window.removeFirst();
            while (window.size() > 1000) window.removeFirst();
        }

        int recentFailures() {
            int count = 0;
            for (long[] outcome : window) count += (int) outcome[1];
            return count;
        }

        ModelHealthRecord snapshot() {
            return new ModelHealthRecord(target, healthy, consecutiveFailures,
                    lastFailure == null ? null : lastFailure.kind(), lastFailure == null ? null : lastFailure.status(),
                    lastFailure == null ? null : lastFailure.message(), lastFailureAt, lastSuccessAt, unhealthySince,
                    lastProbeAt, lastNotifiedAt);
        }
    }
}
