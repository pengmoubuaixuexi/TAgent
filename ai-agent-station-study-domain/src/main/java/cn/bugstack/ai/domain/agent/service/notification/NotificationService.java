package cn.bugstack.ai.domain.agent.service.notification;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationService {

    private final INotificationStore store;

    public NotificationService(INotificationStore store) {
        this.store = store;
    }

    /**
     * Create a notification. The same user/type/reference tuple is idempotent,
     * which keeps reconnect/replay from duplicating cards in the bell panel.
     */
    public AgentNotification publish(NotificationCommand command) {
        if (command == null) throw new IllegalArgumentException("notification command is required");
        String userId = required(command.getUserId(), "userId");
        AgentNotification.Type type = command.getType();
        if (type == null) throw new IllegalArgumentException("notification type is required");
        long now = System.currentTimeMillis();
        String notificationId = firstNonBlank(command.getNotificationId(), stableId(userId, type, command.getReferenceId()));
        AgentNotification existing = store.find(userId, notificationId).orElse(null);

        AgentNotification notification = AgentNotification.builder()
                .notificationId(notificationId)
                .userId(userId)
                .tenantId(trim(command.getTenantId()))
                .sessionId(trim(command.getSessionId()))
                .runId(trim(command.getRunId()))
                .stepId(trim(command.getStepId()))
                .type(type)
                .referenceId(trim(command.getReferenceId()))
                .status(command.getStatus() == null ? defaultStatus(type) : command.getStatus())
                .readStatus(existing == null || existing.getReadStatus() == null
                        ? AgentNotification.ReadStatus.UNREAD : existing.getReadStatus())
                .title(required(command.getTitle(), "title"))
                .summary(trim(command.getSummary()))
                .createdAt(existing == null || existing.getCreatedAt() == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .expiresAt(command.getExpiresAt())
                .acknowledgedAt(existing == null ? null : existing.getAcknowledgedAt())
                .archivedAt(existing == null ? null : existing.getArchivedAt())
                .metadata(command.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(command.getMetadata()))
                .build();
        store.save(notification);
        return notification;
    }

    public List<AgentNotification> list(String userId, int limit, boolean includeArchived) {
        String owner = required(userId, "userId");
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<AgentNotification> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AgentNotification notification : store.list(owner, 500)) {
            expireIfNeeded(notification, now);
            if (!includeArchived && notification.getReadStatus() == AgentNotification.ReadStatus.ARCHIVED) continue;
            result.add(notification);
            if (result.size() >= effectiveLimit) break;
        }
        return result;
    }

    public AgentNotification acknowledge(String userId, String notificationId) {
        AgentNotification notification = requireOwned(userId, notificationId);
        expireIfNeeded(notification, System.currentTimeMillis());
        if (notification.getReadStatus() != AgentNotification.ReadStatus.ARCHIVED) {
            long now = System.currentTimeMillis();
            notification.setReadStatus(AgentNotification.ReadStatus.ACKNOWLEDGED);
            notification.setAcknowledgedAt(now);
            notification.setUpdatedAt(now);
            store.save(notification);
        }
        return notification;
    }

    public AgentNotification archive(String userId, String notificationId) {
        AgentNotification notification = requireOwned(userId, notificationId);
        long now = System.currentTimeMillis();
        notification.setReadStatus(AgentNotification.ReadStatus.ARCHIVED);
        notification.setArchivedAt(now);
        notification.setUpdatedAt(now);
        store.save(notification);
        return notification;
    }

    /** Update a pending card from an ask/approval/plan result event. */
    public Optional<AgentNotification> resolve(String userId,
                                               String referenceId,
                                               AgentNotification.Status status) {
        String owner = required(userId, "userId");
        String reference = required(referenceId, "referenceId");
        if (status == null) throw new IllegalArgumentException("notification status is required");
        return store.findByReference(owner, reference).map(notification -> {
            long now = System.currentTimeMillis();
            notification.setStatus(status);
            if (notification.getReadStatus() == AgentNotification.ReadStatus.UNREAD) {
                notification.setReadStatus(AgentNotification.ReadStatus.ACKNOWLEDGED);
                notification.setAcknowledgedAt(now);
            }
            notification.setUpdatedAt(now);
            store.save(notification);
            return notification;
        });
    }

    public Optional<AgentNotification> find(String userId, String notificationId) {
        String owner = required(userId, "userId");
        Optional<AgentNotification> found = store.find(owner, required(notificationId, "notificationId"));
        found.ifPresent(notification -> expireIfNeeded(notification, System.currentTimeMillis()));
        return found;
    }

    private AgentNotification requireOwned(String userId, String notificationId) {
        return find(userId, notificationId)
                .orElseThrow(() -> new IllegalArgumentException("notification does not exist or is not owned by current user"));
    }

    private void expireIfNeeded(AgentNotification notification, long now) {
        if (notification == null || notification.getExpiresAt() == null || notification.getExpiresAt() > now) return;
        AgentNotification.Status status = notification.getStatus();
        if (status != AgentNotification.Status.WAITING && status != AgentNotification.Status.PLAN_REVIEW) return;
        notification.setStatus(AgentNotification.Status.EXPIRED);
        notification.setUpdatedAt(now);
        store.save(notification);
    }

    private static AgentNotification.Status defaultStatus(AgentNotification.Type type) {
        return switch (type) {
            case TASK_FAILED -> AgentNotification.Status.TASK_FAILED;
            case TASK_COMPLETED -> AgentNotification.Status.TASK_COMPLETED;
            case PLAN_REVIEW -> AgentNotification.Status.PLAN_REVIEW;
            default -> AgentNotification.Status.WAITING;
        };
    }

    private static String stableId(String userId, AgentNotification.Type type, String referenceId) {
        if (referenceId == null || referenceId.isBlank()) return "ntf-" + UUID.randomUUID();
        String seed = userId + "\n" + type.name() + "\n" + referenceId.trim();
        return "ntf-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first.trim();
    }
}
