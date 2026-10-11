package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.ToolEvidenceRecord;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RedisToolEvidenceAccessTest {
    static RedisRunSnapshotService service(StringRedisTemplate redis) {
        RedisRunSnapshotService result = new RedisRunSnapshotService();
        ReflectionTestUtils.setField(result, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(result, "keyPrefix", "agent:run:snapshot:");
        ReflectionTestUtils.setField(result, "ttlSeconds", 21600L);
        return result;
    }

    @Test @SuppressWarnings("unchecked") public void ownerIsCheckedBeforeReadingTheSingleEvidenceField() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        HashOperations<String,Object,Object> hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object,Object>opsForHash()).thenReturn(hashes);
        when(values.get("agent:run:snapshot:run-a")).thenReturn(JSON.toJSONString(
                RunSnapshot.builder().runId("run-a").userId("alice").build()));
        when(hashes.get("agent:run:snapshot:tool-evidence:run-a", "tool:a")).thenReturn(JSON.toJSONString(
                ToolEvidenceRecord.builder().evidenceId("tool:a").output("private").build()));
        RedisRunSnapshotService service = service(redis);
        assertTrue(service.findToolEvidence("run-a", "bob", "tool:a").isEmpty());
        assertTrue(service.findToolEvidence("expired", "alice", "tool:a").isEmpty());
        verifyNoInteractions(hashes);
        assertEquals("private", service.findToolEvidence("run-a", "alice", "tool:a").orElseThrow().getOutput());
        verify(hashes).get("agent:run:snapshot:tool-evidence:run-a", "tool:a");
        verify(hashes, never()).entries(anyString());
    }

    @Test public void oversizeResultRejectedBeforeRedisAndStorageFailureIsNotAcknowledged() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisRunSnapshotService service = service(redis);
        assertFalse(service.saveToolEvidence("run-a", "alice", ToolEvidenceRecord.builder()
                .evidenceId("tool:a").output("x".repeat(262145)).build()));
        verifyNoInteractions(redis);
        // No acknowledgement from Redis must never be presented as a readable resultRef.
        assertFalse(service.saveToolEvidence("run-a", "alice", ToolEvidenceRecord.builder()
                .evidenceId("tool:a").output("x").build()));
    }

    @Test public void acknowledgedWritePassesOwnerAndPerRunBoundsToAtomicScript() {
        final java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();
        StringRedisTemplate redis = new StringRedisTemplate() {
            @Override @SuppressWarnings("unchecked") public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                assertEquals(List.of("agent:run:snapshot:run-a", "agent:run:snapshot:tool-evidence:run-a",
                        "agent:run:snapshot:tool-evidence-size:run-a"), keys);
                assertEquals("alice", args[0]); assertEquals("tool:a", args[1]);
                assertEquals("21600", args[3]); assertEquals("100", args[4]); assertEquals("8388608", args[5]);
                assertEquals("private", JSON.parseObject((String) args[2], ToolEvidenceRecord.class).getOutput());
                executed.set(true); return (T) Long.valueOf(1L);
            }
        };
        assertTrue(service(redis).saveToolEvidence("run-a", "alice", ToolEvidenceRecord.builder()
                .evidenceId("tool:a").output("private").build()));
        assertTrue(executed.get());
    }
}
