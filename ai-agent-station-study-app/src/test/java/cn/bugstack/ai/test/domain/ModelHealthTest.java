package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IModelHealthRepository;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthNotifier;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRecord;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRegistry;
import cn.bugstack.ai.domain.agent.service.notification.AgentNotification;
import cn.bugstack.ai.domain.agent.service.notification.INotificationStore;
import cn.bugstack.ai.domain.agent.service.notification.NotificationService;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ModelHealthTest {
    private static final ModelTarget FLASH = new ModelTarget("p1", "mimo-v2.6-flash");
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<ModelHealthRecord> saved = new CopyOnWriteArrayList<>();
    private final ModelHealthRegistry health = new ModelHealthRegistry(new IModelHealthRepository() {
        @Override public List<ModelHealthRecord> loadAll() { return List.of(); }
        @Override public void save(ModelHealthRecord record) { saved.add(record); }
        @Override public void delete(ModelTarget target) { }
    }, now::get);

    private static ModelFailure failure(ModelFailure.Kind kind, int status) {
        return new ModelFailure(FLASH, kind, status, "provider text");
    }

    @Test public void modelOrAccountFailureMarksUnhealthyAtOnce() {
        health.failed(failure(ModelFailure.Kind.MODEL_UNAVAILABLE, 400));
        assertFalse(health.available(FLASH));
        assertTrue(health.awaitIdle(Duration.ofSeconds(2)));
        assertFalse(saved.get(saved.size() - 1).healthy());
        assertEquals("模型不存在或已下线，HTTP 400", saved.get(saved.size() - 1).lastReason());
    }

    @Test public void transientFailuresTripOnlyAtTheThresholdWithinTheWindow() {
        for (int i = 0; i < 4; i++) { health.failed(failure(ModelFailure.Kind.TRANSIENT, 503)); now.addAndGet(1_000); }
        // Four failures and no successes: more than 50% of at least four requests failed.
        assertFalse(health.available(FLASH));
        health.succeeded(FLASH);
        assertTrue(health.available(FLASH));
    }

    @Test public void occasionalNetworkErrorsAmongSuccessesStayHealthy() {
        health.failed(failure(ModelFailure.Kind.TRANSIENT, 0));
        for (int i = 0; i < 10; i++) { health.succeeded(FLASH); now.addAndGet(1_000); }
        health.failed(failure(ModelFailure.Kind.TRANSIENT, 0));
        now.addAndGet(61_000);
        health.failed(failure(ModelFailure.Kind.TRANSIENT, 0));
        assertTrue(health.available(FLASH));
    }

    @Test public void requestErrorsAreNotAHealthSignal() {
        for (int i = 0; i < 10; i++) health.failed(failure(ModelFailure.Kind.REQUEST_REJECTED, 400));
        assertTrue(health.available(FLASH));
        assertTrue(health.snapshot(FLASH).isEmpty());
    }

    @Test public void probeBackoffAndRecovery() {
        health.failed(failure(ModelFailure.Kind.MODEL_UNAVAILABLE, 401));
        assertTrue(health.dueForProbe().isEmpty());
        now.addAndGet(60_000);
        assertEquals(List.of(FLASH), health.dueForProbe());
        health.probed(FLASH, failure(ModelFailure.Kind.MODEL_UNAVAILABLE, 401));
        now.addAndGet(60_000);
        assertTrue("second probe waits two minutes", health.dueForProbe().isEmpty());
        now.addAndGet(60_000);
        assertEquals(List.of(FLASH), health.dueForProbe());
        health.probed(FLASH, null);
        assertTrue(health.available(FLASH));
        assertTrue(health.dueForProbe().isEmpty());
    }

    @Test public void twentyFailuresInOneOutageProduceOneCardPerRecipientAndRecoveryAddsOne() {
        var store = new MemoryStore();
        var notifications = new NotificationService(store);
        var catalog = new ModelCatalogPlanner(new Catalog(Map.of("p1", ModelCatalogEntry.PLATFORM, "u1", "alice")));
        new ModelHealthNotifier(notifications, catalog, () -> List.of("admin-1", "admin-2"), health);

        for (int i = 0; i < 20; i++) { health.failed(failure(ModelFailure.Kind.MODEL_UNAVAILABLE, 400)); now.addAndGet(1_000); }
        assertTrue(health.awaitIdle(Duration.ofSeconds(5)));

        for (String admin : List.of("admin-1", "admin-2")) {
            List<AgentNotification> cards = store.list(admin, 50);
            assertEquals("one card per outage, updated in place", 1, cards.size());
            AgentNotification card = cards.get(0);
            assertEquals(AgentNotification.Type.MODEL_UNAVAILABLE, card.getType());
            assertEquals("模型 mimo-v2.6-flash 暂不可用", card.getTitle());
            assertTrue(card.getSummary(), card.getSummary().contains("模型不存在或已下线"));
            assertEquals("/admin-models.html", card.getMetadata().get("link"));
            assertNotNull(card.getMetadata().get("popupKey"));
        }
        assertTrue("the owner of nothing is not told", store.list("alice", 50).isEmpty());

        health.probed(FLASH, null);
        assertTrue(health.awaitIdle(Duration.ofSeconds(5)));
        List<AgentNotification> cards = store.list("admin-1", 50);
        assertEquals(2, cards.size());
        var recovered = cards.stream().filter(c -> c.getType() == AgentNotification.Type.MODEL_RECOVERED).findFirst().orElseThrow();
        var down = cards.stream().filter(c -> c.getType() == AgentNotification.Type.MODEL_UNAVAILABLE).findFirst().orElseThrow();
        assertEquals("模型 mimo-v2.6-flash 已恢复", recovered.getTitle());
        assertEquals(AgentNotification.Status.RESOLVED, down.getStatus());
        assertNotEquals(down.getMetadata().get("popupKey"), recovered.getMetadata().get("popupKey"));
    }

    @Test public void personalModelOutageNotifiesOnlyItsOwner() {
        var store = new MemoryStore();
        var catalog = new ModelCatalogPlanner(new Catalog(Map.of("u1", "alice")));
        new ModelHealthNotifier(new NotificationService(store), catalog, () -> List.of("admin-1"), health);
        ModelTarget personal = new ModelTarget("u1", "my-model");
        health.failed(new ModelFailure(personal, ModelFailure.Kind.MODEL_UNAVAILABLE, 402, "Insufficient Balance"));
        assertTrue(health.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(1, store.list("alice", 50).size());
        assertEquals("/models.html", store.list("alice", 50).get(0).getMetadata().get("link"));
        assertTrue(store.list("admin-1", 50).isEmpty());
    }

    private record Catalog(Map<String, String> scopes) implements IModelCatalogRepository {
        @Override public List<ModelCatalogEntry> usableEntries() { return List.of(); }
        @Override public Map<String, String> connectionScopes() { return scopes; }
        @Override public List<ModelCatalogEntry> listByScope(String ownerScope) { return List.of(); }
        @Override public void upsert(ModelCatalogEntry entry) { }
        @Override public boolean update(String s, String a, String m, String t, int o, boolean e) { return false; }
        @Override public boolean delete(String s, String a, String m) { return false; }
    }

    static final class MemoryStore implements INotificationStore {
        final Map<String, AgentNotification> byId = new ConcurrentHashMap<>();
        @Override public void save(AgentNotification n) { byId.put(n.getUserId() + "/" + n.getNotificationId(), n); }
        @Override public Optional<AgentNotification> find(String userId, String id) { return Optional.ofNullable(byId.get(userId + "/" + id)); }
        @Override public Optional<AgentNotification> findByReference(String userId, String reference) {
            return byId.values().stream().filter(n -> n.getUserId().equals(userId) && reference.equals(n.getReferenceId())).findFirst();
        }
        @Override public List<AgentNotification> list(String userId, int limit) {
            return new ArrayList<>(byId.values().stream().filter(n -> n.getUserId().equals(userId)).toList());
        }
        @Override public int acknowledgeAll(String userId, long before, long at) { return 0; }
    }
}
