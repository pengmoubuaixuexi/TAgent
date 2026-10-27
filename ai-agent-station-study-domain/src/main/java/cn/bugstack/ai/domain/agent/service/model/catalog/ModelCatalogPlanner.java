package cn.bugstack.ai.domain.agent.service.model.catalog;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFallbackPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelRequestTraits;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Candidates from the catalog, within the requested connection's owner only: same tier (configured
 * order) → higher tiers → lower tiers. A request with images or tools only gets candidates that
 * support them. Health is applied by the request layer, not here.
 *
 * <p>The catalog is served from a snapshot. A stale snapshot is refreshed in the background, so a
 * model request never waits for the database once the first snapshot exists.</p>
 */
@Slf4j
@Component
public class ModelCatalogPlanner implements ModelFallbackPlanner {
    private static final Duration TTL = Duration.ofSeconds(30);
    private final IModelCatalogRepository repository;
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "model-catalog-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Snapshot snapshot;

    private record Snapshot(Map<String, String> scopes, Map<String, List<ModelCatalogEntry>> byScope, long loadedAt) {
        boolean stale() { return System.currentTimeMillis() - loadedAt > TTL.toMillis(); }
    }

    public ModelCatalogPlanner(IModelCatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ModelTarget> fallbacks(ModelTarget requested, ModelRequestTraits traits) {
        Snapshot current = snapshot();
        if (current == null) return List.of();
        String scope = current.scopes().get(requested.apiId());
        if (scope == null) return List.of();
        List<ModelCatalogEntry> pool = current.byScope().getOrDefault(scope, List.of());
        int tier = pool.stream().filter(e -> e.target().equals(requested)).findFirst()
                .map(ModelCatalogEntry::tierRank).orElse(1);
        return pool.stream()
                .filter(e -> !e.target().equals(requested))
                // Users may only be served platform models that are published to them.
                .filter(e -> !e.platform() || e.published())
                .filter(e -> !traits.images() || e.acceptsImages())
                .filter(e -> !traits.tools() || e.toolCalling())
                .sorted(Comparator.comparingInt((ModelCatalogEntry e) -> group(e.tierRank() - tier))
                        .thenComparingInt(ModelCatalogEntry::sortOrder)
                        .thenComparing(ModelCatalogEntry::modelName)
                        .thenComparing(ModelCatalogEntry::apiId))
                .map(ModelCatalogEntry::target)
                .toList();
    }

    /** Same tier first, then the nearest higher tier upwards, then the nearest lower tier downwards. */
    private static int group(int difference) {
        return difference == 0 ? 0 : difference > 0 ? difference : 2 - difference;
    }

    /** PLATFORM, the owner of a personal connection, or null for an unknown or disabled connection. */
    public String scopeOf(String apiId) {
        Snapshot current = snapshot();
        return current == null ? null : current.scopes().get(apiId);
    }

    public Optional<ModelCatalogEntry> entry(ModelTarget target) {
        Snapshot current = snapshot();
        String scope = current == null ? null : current.scopes().get(target.apiId());
        if (scope == null) return Optional.empty();
        return current.byScope().getOrDefault(scope, List.of()).stream().filter(e -> e.target().equals(target)).findFirst();
    }

    /** Database check for background work only: is this model still configured anywhere? */
    public boolean referenced(ModelTarget target) {
        try {
            return repository.referenced(target.apiId(), target.model());
        } catch (RuntimeException unavailable) {
            return true; // Unknown: keep it.
        }
    }

    /** After a catalog or connection change; the next request sees it as soon as the reload finishes. */
    public void invalidate() {
        Snapshot current = snapshot;
        if (current != null) snapshot = new Snapshot(current.scopes(), current.byScope(), 0);
        refreshAsync();
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) return load();
        if (current.stale()) refreshAsync();
        return current;
    }

    private synchronized Snapshot load() {
        if (snapshot != null && !snapshot.stale()) return snapshot;
        try {
            Map<String, List<ModelCatalogEntry>> byScope = repository.usableEntries().stream()
                    .collect(Collectors.groupingBy(ModelCatalogEntry::ownerScope));
            snapshot = new Snapshot(Map.copyOf(repository.connectionScopes()), byScope, System.currentTimeMillis());
        } catch (RuntimeException unavailable) {
            // Keep serving the previous catalog; without one, requests simply have no substitutes.
            log.warn("[ModelCatalog] reload failed, keeping previous snapshot: {}", unavailable.toString());
            if (snapshot != null) snapshot = new Snapshot(snapshot.scopes(), snapshot.byScope(), System.currentTimeMillis());
        }
        return snapshot;
    }

    private void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            refresher.execute(() -> {
                try { load(); } finally { refreshing.set(false); }
            });
        } catch (RuntimeException rejected) {
            refreshing.set(false);
        }
    }
}
