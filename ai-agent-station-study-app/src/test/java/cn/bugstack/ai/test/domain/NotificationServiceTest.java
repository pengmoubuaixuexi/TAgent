package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.notification.AgentNotification;
import cn.bugstack.ai.domain.agent.service.notification.INotificationStore;
import cn.bugstack.ai.domain.agent.service.notification.NotificationCommand;
import cn.bugstack.ai.domain.agent.service.notification.NotificationService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NotificationServiceTest {

    @Test
    public void shouldPublishIdempotentlyAndResolveByReference() {
        MemoryStore store = new MemoryStore();
        NotificationService service = new NotificationService(store);
        NotificationCommand command = command("u1", "input-1", System.currentTimeMillis() + 60_000);

        AgentNotification first = service.publish(command);
        AgentNotification replay = service.publish(command);

        assertEquals(first.getNotificationId(), replay.getNotificationId());
        assertEquals(1, store.items.size());
        assertTrue(service.resolve("u1", "input-1", AgentNotification.Status.RESOLVED).isPresent());
        AgentNotification resolved = service.find("u1", first.getNotificationId()).orElseThrow();
        assertEquals(AgentNotification.Status.RESOLVED, resolved.getStatus());
        assertEquals(AgentNotification.ReadStatus.ACKNOWLEDGED, resolved.getReadStatus());
    }

    @Test
    public void shouldSeparateAcknowledgeFromBusinessStatusAndArchive() {
        NotificationService service = new NotificationService(new MemoryStore());
        AgentNotification created = service.publish(command("u1", "approval-1", System.currentTimeMillis() + 60_000));

        AgentNotification acknowledged = service.acknowledge("u1", created.getNotificationId());
        assertEquals(AgentNotification.Status.WAITING, acknowledged.getStatus());
        assertEquals(AgentNotification.ReadStatus.ACKNOWLEDGED, acknowledged.getReadStatus());
        assertNotNull(acknowledged.getAcknowledgedAt());

        service.archive("u1", created.getNotificationId());
        assertTrue(service.list("u1", 20, false).isEmpty());
        assertEquals(1, service.list("u1", 20, true).size());
    }

    @Test
    public void shouldExpireOnlyActionableNotificationsDuringRead() {
        NotificationService service = new NotificationService(new MemoryStore());
        AgentNotification created = service.publish(command("u1", "input-old", System.currentTimeMillis() - 1));
        AgentNotification expired = service.find("u1", created.getNotificationId()).orElseThrow();
        assertEquals(AgentNotification.Status.EXPIRED, expired.getStatus());

        AgentNotification completed = service.publish(NotificationCommand.builder()
                .userId("u1").type(AgentNotification.Type.TASK_COMPLETED).referenceId("execution-1")
                .title("done").expiresAt(System.currentTimeMillis() - 1).build());
        assertEquals(AgentNotification.Status.TASK_COMPLETED,
                service.find("u1", completed.getNotificationId()).orElseThrow().getStatus());
    }

    @Test
    public void shouldEnforceOwnerOnMutations() {
        NotificationService service = new NotificationService(new MemoryStore());
        AgentNotification created = service.publish(command("u1", "input-2", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.acknowledge("u2", created.getNotificationId()));
        assertFalse(service.resolve("u2", "input-2", AgentNotification.Status.RESOLVED).isPresent());
    }

    @Test
    public void shouldAcknowledgeEntireOwnerSnapshotWithoutResolvingPendingOrReadingNewArrivals() {
        MemoryStore store = new MemoryStore();
        NotificationService service = new NotificationService(store);
        long before = System.currentTimeMillis() - 1000;
        for (int i = 0; i < 205; i++) {
            AgentNotification item = service.publish(command("alice", "question-" + i, null));
            item.setUpdatedAt(before); item.setCreatedAt(before); store.save(item);
        }
        AgentNotification plan = service.publish(NotificationCommand.builder().userId("alice")
                .type(AgentNotification.Type.PLAN_REVIEW).referenceId("plan").title("Review").build());
        plan.setCreatedAt(before); plan.setUpdatedAt(before); store.save(plan);
        AgentNotification archived = service.publish(command("alice", "archive", null));
        service.archive("alice", archived.getNotificationId());
        AgentNotification newer = service.publish(command("alice", "new-arrival", null));
        AgentNotification other = service.publish(command("bob", "other-owner", null));

        assertEquals(206, service.acknowledgeAll("alice", before));
        assertEquals(0, service.acknowledgeAll("alice", before));
        assertEquals(AgentNotification.ReadStatus.UNREAD, service.find("alice", newer.getNotificationId()).orElseThrow().getReadStatus());
        assertEquals(AgentNotification.ReadStatus.UNREAD, service.find("bob", other.getNotificationId()).orElseThrow().getReadStatus());
        assertEquals(AgentNotification.ReadStatus.ARCHIVED, service.find("alice", archived.getNotificationId()).orElseThrow().getReadStatus());
        AgentNotification readPlan = service.find("alice", plan.getNotificationId()).orElseThrow();
        assertEquals(AgentNotification.Status.PLAN_REVIEW, readPlan.getStatus());
        assertEquals(AgentNotification.ReadStatus.ACKNOWLEDGED, readPlan.getReadStatus());
        assertNotNull(readPlan.getAcknowledgedAt());
        assertTrue(store.items.values().stream().filter(item -> item.getReferenceId().startsWith("question-"))
                .allMatch(item -> item.getStatus() == AgentNotification.Status.WAITING
                        && item.getReadStatus() == AgentNotification.ReadStatus.ACKNOWLEDGED));
    }

    @Test
    public void shouldRejectMissingSnapshotAndSkipNewVersionOfExistingNotification() {
        MemoryStore store = new MemoryStore();
        NotificationService service = new NotificationService(store);
        long before = System.currentTimeMillis() - 1000;
        AgentNotification changed = service.publish(command("alice", "changed", null));
        changed.setCreatedAt(before - 1); changed.setUpdatedAt(before + 1); store.save(changed);
        assertEquals(0, service.acknowledgeAll("alice", before));
        assertEquals(AgentNotification.ReadStatus.UNREAD, service.find("alice", changed.getNotificationId()).orElseThrow().getReadStatus());
        assertThrows(IllegalArgumentException.class, () -> service.acknowledgeAll("alice", 0));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledgeAll(" ", before));
    }

    @Test
    public void duplicateTerminalPreservesReadStatusAndPlanCardRemainsResolvable() {
        NotificationService service = new NotificationService(new MemoryStore());
        AgentNotification plan = service.publish(NotificationCommand.builder().userId("u1")
                .type(AgentNotification.Type.PLAN_REVIEW).referenceId("r1").title("Review plan").build());
        NotificationCommand completion = NotificationCommand.builder().userId("u1")
                .notificationId(NotificationService.terminalId("u1", "r1"))
                .type(AgentNotification.Type.TASK_COMPLETED).referenceId("run-terminal:r1")
                .runId("r1").sessionId("s1").title("Completed").build();
        AgentNotification terminal = service.publish(completion);
        service.acknowledge("u1", terminal.getNotificationId());
        service.publish(completion);
        service.resolve("u1", "r1", AgentNotification.Status.RESOLVED);
        assertEquals(2, service.list("u1", 20, false).size());
        assertEquals(AgentNotification.ReadStatus.ACKNOWLEDGED,
                service.find("u1", terminal.getNotificationId()).orElseThrow().getReadStatus());
        assertEquals(AgentNotification.Status.RESOLVED, service.find("u1", plan.getNotificationId()).orElseThrow().getStatus());
        assertEquals(AgentNotification.Status.TASK_COMPLETED, service.find("u1", terminal.getNotificationId()).orElseThrow().getStatus());
    }

    private static NotificationCommand command(String userId, String referenceId, Long expiresAt) {
        return NotificationCommand.builder()
                .userId(userId)
                .sessionId("s1")
                .runId("r1")
                .type(AgentNotification.Type.ASK_USER)
                .referenceId(referenceId)
                .title("Need more information")
                .summary("Choose a target platform")
                .expiresAt(expiresAt)
                .build();
    }

    private static final class MemoryStore implements INotificationStore {
        private final Map<String, AgentNotification> items = new LinkedHashMap<>();

        @Override
        public void save(AgentNotification notification) {
            items.put(key(notification.getUserId(), notification.getNotificationId()), copy(notification));
        }

        @Override
        public Optional<AgentNotification> find(String userId, String notificationId) {
            return Optional.ofNullable(items.get(key(userId, notificationId))).map(MemoryStore::copy);
        }

        @Override
        public Optional<AgentNotification> findByReference(String userId, String referenceId) {
            return items.values().stream()
                    .filter(item -> userId.equals(item.getUserId()) && referenceId.equals(item.getReferenceId()))
                    .findFirst().map(MemoryStore::copy);
        }

        @Override
        public List<AgentNotification> list(String userId, int limit) {
            return items.values().stream().filter(item -> userId.equals(item.getUserId()))
                    .sorted(Comparator.comparing(AgentNotification::getCreatedAt).reversed())
                    .limit(limit).map(MemoryStore::copy).toList();
        }

        @Override
        public int acknowledgeAll(String userId, long before, long acknowledgedAt) {
            int changed = 0;
            for (AgentNotification item : items.values()) {
                long updated = item.getUpdatedAt() == null ? item.getCreatedAt() : item.getUpdatedAt();
                if (!userId.equals(item.getUserId()) || item.getReadStatus() != AgentNotification.ReadStatus.UNREAD
                        || updated > before) continue;
                item.setReadStatus(AgentNotification.ReadStatus.ACKNOWLEDGED);
                item.setAcknowledgedAt(acknowledgedAt); item.setUpdatedAt(acknowledgedAt); changed++;
            }
            return changed;
        }

        private static AgentNotification copy(AgentNotification source) {
            return AgentNotification.builder()
                    .notificationId(source.getNotificationId()).userId(source.getUserId()).tenantId(source.getTenantId())
                    .sessionId(source.getSessionId()).runId(source.getRunId()).stepId(source.getStepId())
                    .type(source.getType()).referenceId(source.getReferenceId()).status(source.getStatus())
                    .readStatus(source.getReadStatus()).title(source.getTitle()).summary(source.getSummary())
                    .createdAt(source.getCreatedAt()).updatedAt(source.getUpdatedAt()).expiresAt(source.getExpiresAt())
                    .acknowledgedAt(source.getAcknowledgedAt()).archivedAt(source.getArchivedAt())
                    .metadata(source.getMetadata() == null ? null : new LinkedHashMap<>(source.getMetadata())).build();
        }

        private static String key(String userId, String notificationId) {
            return userId + ":" + notificationId;
        }
    }
}
