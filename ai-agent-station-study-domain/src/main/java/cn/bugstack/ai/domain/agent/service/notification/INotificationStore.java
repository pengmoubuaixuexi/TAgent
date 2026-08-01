package cn.bugstack.ai.domain.agent.service.notification;

import java.util.List;
import java.util.Optional;

/** Persistence port. Redis is the default adapter; no SQL migration is required. */
public interface INotificationStore {

    void save(AgentNotification notification);

    Optional<AgentNotification> find(String userId, String notificationId);

    Optional<AgentNotification> findByReference(String userId, String referenceId);

    List<AgentNotification> list(String userId, int limit);
}
