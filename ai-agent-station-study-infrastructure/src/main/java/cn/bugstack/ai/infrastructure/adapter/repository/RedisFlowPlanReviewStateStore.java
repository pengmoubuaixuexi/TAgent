package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewState;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewStateStore;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.flow.plan-review", name = "store-enabled", havingValue = "true", matchIfMissing = true)
public class RedisFlowPlanReviewStateStore implements FlowPlanReviewStateStore {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${agent.flow.plan-review.key-prefix:agent:plan-review:}")
    private String keyPrefix;

    @Override
    public void save(FlowPlanReviewState state, Duration ttl) {
        if (state == null || state.getRunId() == null || state.getRunId().isBlank()) {
            return;
        }
        String json = JSON.toJSONString(state);
        stringRedisTemplate.opsForValue().set(buildKey(state.getRunId()), json, ttl);
        log.info("[FlowPlanReview] saved runId={} ttl={}s bytes={}",
                state.getRunId(), ttl.toSeconds(), json.length());
    }

    @Override
    public Optional<FlowPlanReviewState> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String json = stringRedisTemplate.opsForValue().get(buildKey(runId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(JSON.parseObject(json, FlowPlanReviewState.class));
    }

    @Override
    public void update(FlowPlanReviewState state) {
        if (state == null || state.getRunId() == null || state.getRunId().isBlank()) {
            return;
        }
        Long expiresAt = state.getExpiresAt();
        if (expiresAt == null) {
            return;
        }
        long ttlMillis = expiresAt - System.currentTimeMillis();
        if (ttlMillis <= 0) {
            delete(state.getRunId());
            return;
        }
        stringRedisTemplate.opsForValue().set(buildKey(state.getRunId()), JSON.toJSONString(state), Duration.ofMillis(ttlMillis));
    }

    @Override
    public void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(buildKey(runId));
    }

    private String buildKey(String runId) {
        return keyPrefix + runId;
    }

}
