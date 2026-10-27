package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A run without images or attachments must still store a readable snapshot. */
public class RedisRunSnapshotStartTest {

    @Test @SuppressWarnings("unchecked")
    public void runWithoutAttachmentsStoresParsableSnapshot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(mock(ListOperations.class));
        RedisRunSnapshotService service = RedisToolEvidenceAccessTest.service(redis);

        service.startRun(ExecuteCommandEntity.builder().runId("run-a").userId("alice").sessionId("s1")
                .aiAgentId("8011").message("今天天气怎么样").images(java.util.List.of()).attachments(java.util.List.of()).build(), "ROUTING", "测试Agent");

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("agent:run:snapshot:run-a"), stored.capture(), any(Duration.class));
        assertFalse(stored.getValue(), stored.getValue().contains("$ref"));
        RunSnapshot snapshot = JSON.parseObject(stored.getValue(), RunSnapshot.class);
        assertEquals("alice", snapshot.getUserId());
        assertTrue(snapshot.getImages().isEmpty());
        assertTrue(snapshot.getAttachments().isEmpty());
    }
}
