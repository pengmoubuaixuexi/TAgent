package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelRequestTraits;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ModelCatalogPlannerTest {
    private static final ModelRequestTraits TEXT = new ModelRequestTraits(false, false, true);

    private static ModelCatalogEntry platform(String api, String model, String tier, int order) {
        return new ModelCatalogEntry(ModelCatalogEntry.PLATFORM, api, model, tier, order, Set.of("TEXT"), true, true, true);
    }

    private static ModelCatalogPlanner planner(List<ModelCatalogEntry> entries) {
        return new ModelCatalogPlanner(new Repository(entries, Map.of("p1", "PLATFORM", "p2", "PLATFORM", "alice-api", "alice", "alice-api-2", "alice", "bob-api", "bob")));
    }

    private static List<String> names(List<ModelTarget> targets) { return targets.stream().map(ModelTarget::model).toList(); }

    @Test public void sameTierInOrderThenHigherThenLower() {
        var planner = planner(List.of(platform("p1", "medium-b", "medium", 20), platform("p1", "medium-a", "medium", 10),
                platform("p1", "requested", "medium", 1), platform("p2", "large", "large", 1), platform("p1", "small", "small", 1)));
        assertEquals(List.of("medium-a", "medium-b", "large", "small"), names(planner.fallbacks(new ModelTarget("p1", "requested"), TEXT)));
    }

    @Test public void smallGoesUpThroughMediumAndLargeGoesDownNearestFirst() {
        var entries = List.of(platform("p1", "s", "small", 1), platform("p1", "m", "medium", 1), platform("p1", "l", "large", 1),
                platform("p1", "l2", "large", 2), platform("p1", "s2", "small", 2));
        assertEquals(List.of("s2", "m", "l", "l2"), names(planner(entries).fallbacks(new ModelTarget("p1", "s"), TEXT)));
        assertEquals(List.of("l2", "m", "s", "s2"), names(planner(entries).fallbacks(new ModelTarget("p1", "l"), TEXT)));
    }

    @Test public void personalModelsNeverBorrowPlatformModelsAndViceVersa() {
        var entries = List.of(platform("p1", "platform-model", "medium", 1),
                new ModelCatalogEntry("alice", "alice-api", "alice-main", "medium", 1, Set.of("TEXT"), true, true, false),
                new ModelCatalogEntry("alice", "alice-api-2", "alice-backup", "large", 1, Set.of("TEXT"), true, true, false),
                new ModelCatalogEntry("bob", "bob-api", "bob-model", "medium", 1, Set.of("TEXT"), true, true, false));
        var planner = planner(entries);
        assertEquals(List.of("alice-backup"), names(planner.fallbacks(new ModelTarget("alice-api", "alice-main"), TEXT)));
        assertEquals(List.of(), names(planner.fallbacks(new ModelTarget("bob-api", "bob-model"), TEXT)));
        assertEquals(List.of(), names(planner.fallbacks(new ModelTarget("p1", "platform-model"), TEXT)));
        assertEquals("unknown or disabled connection has no substitutes", List.of(), planner.fallbacks(new ModelTarget("gone", "x"), TEXT));
    }

    @Test public void imagesAndToolsNeedAMatchingCandidateAndUnpublishedPlatformModelsAreNeverOffered() {
        var entries = List.of(platform("p1", "requested", "medium", 1),
                new ModelCatalogEntry(ModelCatalogEntry.PLATFORM, "p1", "vision", "medium", 5, Set.of("TEXT", "IMAGE"), true, true, true),
                new ModelCatalogEntry(ModelCatalogEntry.PLATFORM, "p1", "no-tools", "medium", 1, Set.of("TEXT", "IMAGE"), false, true, true),
                new ModelCatalogEntry(ModelCatalogEntry.PLATFORM, "p1", "unpublished", "medium", 0, Set.of("TEXT", "IMAGE"), true, true, false));
        var planner = planner(entries);
        ModelTarget requested = new ModelTarget("p1", "requested");
        assertEquals(List.of("no-tools", "vision"), names(planner.fallbacks(requested, new ModelRequestTraits(false, true, true))));
        assertEquals(List.of("vision"), names(planner.fallbacks(requested, new ModelRequestTraits(true, true, true))));
        assertEquals(List.of("no-tools", "vision"), names(planner.fallbacks(requested, TEXT)));
    }

    @Test public void aModelOutsideTheCatalogIsTreatedAsMedium() {
        var planner = planner(List.of(platform("p1", "m", "medium", 1), platform("p1", "l", "large", 1), platform("p1", "s", "small", 1)));
        assertEquals(List.of("m", "l", "s"), names(planner.fallbacks(new ModelTarget("p1", "legacy-name"), TEXT)));
    }

    private record Repository(List<ModelCatalogEntry> entries, Map<String, String> scopes) implements IModelCatalogRepository {
        @Override public List<ModelCatalogEntry> usableEntries() { return new ArrayList<>(entries); }
        @Override public Map<String, String> connectionScopes() { return scopes; }
        @Override public List<ModelCatalogEntry> listByScope(String ownerScope) { return entries.stream().filter(e -> e.ownerScope().equals(ownerScope)).toList(); }
        @Override public void upsert(ModelCatalogEntry entry) { }
        @Override public boolean update(String s, String a, String m, String t, int o, boolean e) { return false; }
        @Override public boolean delete(String s, String a, String m) { return false; }
    }
}
