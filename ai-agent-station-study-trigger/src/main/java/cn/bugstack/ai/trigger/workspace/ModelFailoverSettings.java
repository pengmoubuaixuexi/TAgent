package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthProbe;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRecord;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Catalog edits and health views shared by the personal model page and the administrator catalog. */
@Service
public class ModelFailoverSettings {
    private final IModelCatalogRepository catalog;
    private final ModelCatalogPlanner planner;
    private final ModelHealthRegistry health;
    private final ModelHealthProbe probe;

    public ModelFailoverSettings(IModelCatalogRepository catalog, ModelCatalogPlanner planner, ModelHealthRegistry health, ModelHealthProbe probe) {
        this.catalog = catalog;
        this.planner = planner;
        this.health = health;
        this.probe = probe;
    }

    public void register(String scope, String apiId, String model) {
        catalog.upsert(new ModelCatalogEntry(scope, apiId, model, "medium", 100, Set.of("TEXT"), true, true, false));
        planner.invalidate();
    }

    public void add(ModelCatalogEntry entry) {
        catalog.upsert(entry);
        planner.invalidate();
    }

    public boolean delete(String scope, String apiId, String model) {
        boolean deleted = catalog.delete(scope, apiId, model);
        planner.invalidate();
        return deleted;
    }

    public void save(String scope, String apiId, String model, String tier, int sortOrder) {
        save(scope, apiId, model, tier, sortOrder, true);
    }

    public void save(String scope, String apiId, String model, String tier, int sortOrder, boolean enabled) {
        if (!catalog.update(scope, apiId, model, tier, sortOrder, enabled))
            catalog.upsert(new ModelCatalogEntry(scope, apiId, model, tier, sortOrder, Set.of("TEXT"), true, enabled, false));
        planner.invalidate();
    }

    /** @param withDetail include the redacted provider text: only for administrators or the model owner */
    public Map<String, Object> health(String apiId, String model, boolean withDetail) {
        Map<String, Object> view = new LinkedHashMap<>();
        var record = health.snapshot(new ModelTarget(apiId, model));
        if (record.isEmpty()) {
            view.put("state", "UNKNOWN");
            return view;
        }
        ModelHealthRecord r = record.get();
        view.put("state", r.healthy() ? "HEALTHY" : "UNHEALTHY");
        view.put("reason", r.lastReason());
        if (withDetail) view.put("detail", r.lastFailureMessage());
        view.put("consecutiveFailures", r.consecutiveFailures());
        view.put("lastFailureAt", r.lastFailureAt());
        view.put("lastSuccessAt", r.lastSuccessAt());
        view.put("unhealthySince", r.unhealthySince());
        view.put("lastProbeAt", r.lastProbeAt());
        return view;
    }

    public Map<String, Object> check(String apiId, String model, boolean withDetail) {
        var result = probe.check(new ModelTarget(apiId, model));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", result.available());
        view.put("reason", result.reason());
        if (withDetail && result.failure() != null) view.put("detail", result.failure().message());
        view.put("health", health(apiId, model, withDetail));
        return view;
    }
}
