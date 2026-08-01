package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.notification.AgentNotification;
import cn.bugstack.ai.domain.agent.service.notification.INotificationStore;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RedisNotificationStore implements INotificationStore {

    private final StringRedisTemplate redis;

    @Value("${agent.notifications.key-prefix:agent:notification:}")
    private String keyPrefix;

    @Value("${agent.notifications.ttl-seconds:2592000}")
    private long ttlSeconds;

    @Value("${agent.notifications.max-per-user:500}")
    private int maxPerUser;

    public RedisNotificationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(AgentNotification notification) {
        if (notification == null || blank(notification.getUserId()) || blank(notification.getNotificationId())) {
            throw new IllegalArgumentException("notification userId and notificationId are required");
        }
        Duration ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
        String itemKey = itemKey(notification.getUserId(), notification.getNotificationId());
        redis.opsForValue().set(itemKey, JSON.toJSONString(notification), ttl);
        String indexKey = indexKey(notification.getUserId());
        double score = notification.getCreatedAt() == null ? System.currentTimeMillis() : notification.getCreatedAt();
        redis.opsForZSet().add(indexKey, notification.getNotificationId(), score);
        redis.expire(indexKey, ttl);
        if (!blank(notification.getReferenceId())) {
            redis.opsForValue().set(referenceKey(notification.getUserId(), notification.getReferenceId()),
                    notification.getNotificationId(), ttl);
        }
        trimOldest(notification.getUserId());
    }

    @Override
    public Optional<AgentNotification> find(String userId, String notificationId) {
        if (blank(userId) || blank(notificationId)) return Optional.empty();
        String json = redis.opsForValue().get(itemKey(userId, notificationId));
        return blank(json) ? Optional.empty() : Optional.ofNullable(JSON.parseObject(json, AgentNotification.class));
    }

    @Override
    public Optional<AgentNotification> findByReference(String userId, String referenceId) {
        if (blank(userId) || blank(referenceId)) return Optional.empty();
        String notificationId = redis.opsForValue().get(referenceKey(userId, referenceId));
        return blank(notificationId) ? Optional.empty() : find(userId, notificationId);
    }

    @Override
    public List<AgentNotification> list(String userId, int limit) {
        if (blank(userId)) return List.of();
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, Math.max(1, maxPerUser)));
        Set<String> ids = redis.opsForZSet().reverseRange(indexKey(userId), 0, effectiveLimit - 1L);
        if (ids == null || ids.isEmpty()) return List.of();
        List<AgentNotification> result = new ArrayList<>();
        for (String id : ids) find(userId, id).ifPresent(result::add);
        return result;
    }

    private void trimOldest(String userId) {
        String indexKey = indexKey(userId);
        Long size = redis.opsForZSet().zCard(indexKey);
        int max = Math.max(10, maxPerUser);
        if (size == null || size <= max) return;
        Set<String> evicted = redis.opsForZSet().range(indexKey, 0, size - max - 1);
        if (evicted == null || evicted.isEmpty()) return;
        redis.opsForZSet().remove(indexKey, evicted.toArray());
        for (String id : evicted) redis.delete(itemKey(userId, id));
    }

    private String itemKey(String userId, String notificationId) {
        return keyPrefix + "item:" + digest(userId) + ":" + notificationId;
    }

    private String indexKey(String userId) {
        return keyPrefix + "user:" + digest(userId);
    }

    private String referenceKey(String userId, String referenceId) {
        return keyPrefix + "ref:" + digest(userId) + ":" + digest(referenceId);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
