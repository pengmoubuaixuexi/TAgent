package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RedisLifecycleContractTest {

    @Test
    @SuppressWarnings("unchecked")
    public void readingSnapshotRenewsItsWholeRedisFamilyForSixHours() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);

        RunSnapshot snapshot = RunSnapshot.builder()
                .runId("run-1")
                .sessionId("session-1")
                .userId("user-1")
                .build();
        Duration ttl = Duration.ofHours(6);
        when(values.getAndExpire("agent:run:snapshot:run-1", ttl))
                .thenReturn(JSON.toJSONString(snapshot));
        when(values.get("agent:run:snapshot:timeline:run-1")).thenReturn(null);
        when(hashes.entries("agent:run:snapshot:tool-evidence:run-1")).thenReturn(Map.of());

        RedisRunSnapshotService service = snapshotService(redis);
        Optional<RunSnapshot> result = service.find("run-1");

        assertTrue(result.isPresent());
        verify(redis).expire("agent:run:snapshot:timeline:run-1", ttl);
        verify(redis).expire("agent:run:snapshot:tool-evidence:run-1", ttl);
        verify(redis).expire("agent:run:snapshot:session:session-1", ttl);
        verify(redis).expire("agent:run:snapshot:user:user-1", ttl);
    }

    @Test
    public void evidenceMapUsesIndependentSevenDaySlidingTtlAndNeedsNoSnapshotToSave() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Duration ttl = Duration.ofDays(7);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", "run-1");
        when(values.get("agent:run:snapshot:evidence-map:run-1")).thenReturn(JSON.toJSONString(Map.of(
                "signature", "sig-1",
                "data", data)));

        RedisRunSnapshotService service = snapshotService(redis);
        Optional<Map<String, Object>> retained = service.findEvidenceMap("run-1");
        service.saveEvidenceMap("run-1", "sig-2", Map.of("runId", "run-1", "version", "v4"));

        assertEquals("run-1", retained.orElseThrow().get("runId"));
        verify(redis).expire("agent:run:snapshot:evidence-map:run-1", ttl);
        verify(values).set(eq("agent:run:snapshot:evidence-map:run-1"), anyString(), eq(ttl));
        verify(values, never()).getAndExpire(anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void reconnectReadRenewsRunEventStreamEvenWithoutNewEvents() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.range(eq("agent:run-events:run-1"), any(Range.class)))
                .thenReturn((List<MapRecord<String, Object, Object>>) (List<?>) List.of());
        RedisRunEventStore store = new RedisRunEventStore(redis);
        ReflectionTestUtils.setField(store, "keyPrefix", "agent:run-events:");
        ReflectionTestUtils.setField(store, "ttlSeconds", 21600L);

        assertTrue(store.readAfter("run-1", "0-0").isEmpty());

        verify(redis).expire("agent:run-events:run-1", Duration.ofHours(6));
    }

    private static RedisRunSnapshotService snapshotService(StringRedisTemplate redis) {
        RedisRunSnapshotService service = new RedisRunSnapshotService();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "keyPrefix", "agent:run:snapshot:");
        ReflectionTestUtils.setField(service, "sessionIndexPrefix", "agent:run:snapshot:session:");
        ReflectionTestUtils.setField(service, "userIndexPrefix", "agent:run:snapshot:user:");
        ReflectionTestUtils.setField(service, "ttlSeconds", 21600L);
        ReflectionTestUtils.setField(service, "evidenceMapTtlSeconds", 604800L);
        return service;
    }
}
