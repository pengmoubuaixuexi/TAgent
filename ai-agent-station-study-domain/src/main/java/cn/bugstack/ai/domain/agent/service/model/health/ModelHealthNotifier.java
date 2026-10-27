package cn.bugstack.ai.domain.agent.service.model.health;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelAlertRecipients;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.notification.AgentNotification;
import cn.bugstack.ai.domain.agent.service.notification.NotificationCommand;
import cn.bugstack.ai.domain.agent.service.notification.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Model health notifications, one per owner + model name and outage. Becoming unavailable creates
 * the card (the pages show it once, keyed by {@code popupKey}); further failures in the same outage
 * only update that card; recovery resolves it and adds a "recovered" card.
 * Platform models notify every administrator, personal models notify their owner.
 */
@Slf4j
@Component
public class ModelHealthNotifier implements ModelHealthRegistry.Listener {
    /** Repeated failures during one outage refresh the card at most this often. */
    private static final long UPDATE_INTERVAL_MILLIS = 30_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private final NotificationService notifications;
    private final ModelCatalogPlanner catalog;
    private final IModelAlertRecipients recipients;
    private final ModelHealthRegistry health;
    private final ConcurrentMap<String, Long> lastUpdate = new ConcurrentHashMap<>();

    public ModelHealthNotifier(NotificationService notifications, ModelCatalogPlanner catalog, IModelAlertRecipients recipients,
                               ModelHealthRegistry health) {
        this.notifications = notifications;
        this.catalog = catalog;
        this.recipients = recipients;
        this.health = health;
        health.addListener(this);
    }

    @Override
    public void changed(ModelHealthRegistry.Change change, ModelHealthRecord record, ModelFailure failure) {
        String scope = catalog.scopeOf(record.target().apiId());
        if (scope == null) return;
        boolean platform = ModelCatalogEntry.PLATFORM.equals(scope);
        List<String> users = platform ? recipients.administrators() : List.of(scope);
        if (users.isEmpty() || record.unhealthySince() == null) return;
        String outage = "model-health:" + scope + ":" + record.target().model() + ":" + record.unhealthySince();
        long now = System.currentTimeMillis();
        switch (change) {
            case BECAME_UNHEALTHY -> {
                lastUpdate.put(outage, now);
                for (String user : users) notifications.publish(unavailable(user, scope, record, outage, platform));
                health.notified(record.target(), now);
            }
            case STILL_UNHEALTHY -> {
                Long previous = lastUpdate.get(outage);
                if (previous != null && now - previous < UPDATE_INTERVAL_MILLIS) return;
                lastUpdate.put(outage, now);
                for (String user : users) notifications.publish(unavailable(user, scope, record, outage, platform));
            }
            case RECOVERED -> {
                lastUpdate.remove(outage);
                for (String user : users) {
                    notifications.resolve(user, outage, AgentNotification.Status.RESOLVED);
                    notifications.publish(recovered(user, scope, record, outage, platform, now));
                }
                health.notified(record.target(), now);
            }
        }
        log.info("[ModelHealth] notified {} recipient(s) of {} for {}", users.size(), change, record.target());
    }

    private static NotificationCommand unavailable(String user, String scope, ModelHealthRecord record, String outage, boolean platform) {
        String model = record.target().model();
        StringBuilder summary = new StringBuilder(record.lastReason() == null ? "请求失败" : record.lastReason());
        summary.append("。需要它的任务会自动改用同").append(platform ? "平台" : "账号").append("的备用模型（如已配置）。")
                .append("本次已失败 ").append(record.consecutiveFailures()).append(" 次，最近一次 ")
                .append(TIME.format(Instant.ofEpochMilli(record.lastFailureAt() == null ? System.currentTimeMillis() : record.lastFailureAt())));
        if (record.lastFailureMessage() != null && !record.lastFailureMessage().isBlank())
            summary.append("。服务商返回：").append(record.lastFailureMessage());
        Map<String, Object> metadata = metadata(scope, record, outage, platform);
        metadata.put("failures", record.consecutiveFailures());
        return NotificationCommand.builder().userId(user).type(AgentNotification.Type.MODEL_UNAVAILABLE)
                .status(AgentNotification.Status.MODEL_UNAVAILABLE).referenceId(outage)
                .title("模型 " + model + " 暂不可用").summary(summary.toString()).metadata(metadata).build();
    }

    private static NotificationCommand recovered(String user, String scope, ModelHealthRecord record, String outage, boolean platform, long now) {
        long minutes = Math.max(1, (now - record.unhealthySince()) / 60_000);
        String reference = outage + ":recovered";
        Map<String, Object> metadata = metadata(scope, record, reference, platform);
        return NotificationCommand.builder().userId(user).type(AgentNotification.Type.MODEL_RECOVERED)
                .status(AgentNotification.Status.MODEL_RECOVERED).referenceId(reference)
                .title("模型 " + record.target().model() + " 已恢复")
                .summary("已于 " + TIME.format(Instant.ofEpochMilli(now)) + " 恢复可用，此前不可用约 " + minutes + " 分钟。")
                .metadata(metadata).build();
    }

    private static Map<String, Object> metadata(String scope, ModelHealthRecord record, String popupKey, boolean platform) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", "MODEL_HEALTH");
        metadata.put("popupKey", popupKey);
        metadata.put("modelName", record.target().model());
        metadata.put("apiId", record.target().apiId());
        metadata.put("ownerScope", scope);
        metadata.put("unhealthySince", record.unhealthySince());
        metadata.put("link", platform ? "/admin-models.html" : "/models.html");
        return metadata;
    }
}
