package cn.bugstack.ai.domain.agent.service.memory.working;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * 默认 Working Memory 兜底实现：什么都不做。
 * <p>
 * 始终注册（不带 @ConditionalOnMissingBean——后者作用于 @Service 类时 component scan 阶段评估不可靠）。
 * Redis 实现通过 {@code @Primary + @ConditionalOnProperty} 装上后压倒 Noop 赢得 @Resource 注入。
 */
@Slf4j
@Service
public class NoopWorkingMemoryService implements IWorkingMemoryService {

    @PostConstruct
    public void logBoundImpl() {
        log.info("[WM] NoopWorkingMemoryService registered — 若 Redis 实现也注册（@Primary），@Resource 会拿到 Redis；否则现在主链路用的就是这个 noop");
    }

    @Override
    public void put(String sessionId, String key, Object value) {
        // intentionally empty
    }

    @Override
    public void put(String sessionId, String key, Object value, Duration ttl) {
        // intentionally empty
    }

    @Override
    public <T> T get(String sessionId, String key, Class<T> type) {
        return null;
    }

    @Override
    public Map<String, String> dump(String sessionId) {
        return Collections.emptyMap();
    }

    @Override
    public void clear(String sessionId) {
        // intentionally empty
    }
}
