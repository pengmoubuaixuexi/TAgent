package cn.bugstack.ai.infrastructure.adapter.repository;

import org.junit.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RedisNotificationStoreTest {
    @Test @SuppressWarnings("unchecked")
    public void bulkReadCapturesAllRetainedIdsAndUsesPerItemAtomicUpdates() {
        ZSetOperations<String, String> index = mock(ZSetOperations.class);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < 205; i++) ids.add("notice-" + i);
        when(index.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(ids);
        List<String> itemKeys = new ArrayList<>();
        StringRedisTemplate redis = new StringRedisTemplate() {
            @Override public ZSetOperations<String, String> opsForZSet() { return index; }
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                assertEquals(1, keys.size());
                assertEquals("alice", args[0]); assertEquals("123", args[1]); assertEquals("456", args[2]);
                assertEquals(Long.class, script.getResultType());
                itemKeys.add(keys.get(0));
                // A concurrent archive or newer revision is skipped by the script.
                return (T) Long.valueOf(itemKeys.size() == 1 ? 0 : 1);
            }
        };
        RedisNotificationStore store = new RedisNotificationStore(redis);
        ReflectionTestUtils.setField(store, "keyPrefix", "test:notification:");
        assertEquals(204, store.acknowledgeAll("alice", 123, 456));
        assertEquals(205, itemKeys.size());
        assertTrue(itemKeys.stream().allMatch(key -> key.startsWith("test:notification:item:") && key.contains(":notice-")));
        verify(index).reverseRange(startsWith("test:notification:user:"), eq(0L), eq(-1L));
    }

    @Test @SuppressWarnings("unchecked") public void missingRedisAcknowledgementMustNotReportSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> index = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(index);
        when(index.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of("notice"));
        RedisNotificationStore store = new RedisNotificationStore(redis);
        ReflectionTestUtils.setField(store, "keyPrefix", "test:notification:");
        assertThrows(IllegalStateException.class, () -> store.acknowledgeAll("alice", 123, 456));
    }
}
