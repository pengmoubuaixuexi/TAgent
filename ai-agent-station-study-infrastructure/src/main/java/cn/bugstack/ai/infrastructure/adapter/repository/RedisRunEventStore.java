package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.execute.event.IRunEventStore;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "agent.run-events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisRunEventStore implements IRunEventStore {

    private final StringRedisTemplate redis;

    @Value("${agent.run-events.key-prefix:agent:run-events:}")
    private String keyPrefix;

    @Value("${agent.run-events.ttl-seconds:21600}")
    private long ttlSeconds;

    @Value("${agent.run-events.max-length:10000}")
    private long maxLength;

    public RedisRunEventStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RunEventRecord append(String runId, String sessionId, String eventType, String payloadJson) {
        long createdAt = System.currentTimeMillis();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sessionId", sessionId == null ? "" : sessionId);
        fields.put("eventType", eventType);
        fields.put("payloadJson", payloadJson);
        fields.put("createdAt", Long.toString(createdAt));

        String key = key(runId);
        RecordId id = redis.opsForStream().add(
                MapRecord.create(key, fields),
                RedisStreamCommands.XAddOptions.maxlen(Math.max(100, maxLength)).approximateTrimming(true));
        redis.expire(key, Duration.ofSeconds(Math.max(60, ttlSeconds)));
        return RunEventRecord.builder()
                .eventId(id == null ? null : id.getValue())
                .runId(runId)
                .sessionId(sessionId)
                .eventType(eventType)
                .payloadJson(payloadJson)
                .createdAt(createdAt)
                .build();
    }

    @Override
    public List<RunEventRecord> readAfter(String runId, String afterEventId) {
        Range<String> range = afterEventId == null || afterEventId.isBlank()
                ? Range.unbounded()
                : Range.leftOpen(afterEventId, "+");
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(key(runId), range);
        if (records == null || records.isEmpty()) return List.of();
        return records.stream().map(record -> {
            Map<Object, Object> value = record.getValue();
            String created = string(value.get("createdAt"));
            return RunEventRecord.builder()
                    .eventId(record.getId().getValue())
                    .runId(runId)
                    .sessionId(string(value.get("sessionId")))
                    .eventType(string(value.get("eventType")))
                    .payloadJson(string(value.get("payloadJson")))
                    .createdAt(created == null || created.isBlank() ? null : Long.parseLong(created))
                    .build();
        }).toList();
    }

    private String key(String runId) {
        return keyPrefix + runId;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
