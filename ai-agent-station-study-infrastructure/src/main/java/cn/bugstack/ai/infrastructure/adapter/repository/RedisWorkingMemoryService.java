package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.memory.working.IWorkingMemoryService;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Working Memory 的 Redis 实现（P1.2.2）。
 * <p>
 * 单条 key 形如 {@code wm:<sessionId>:<key>}，value 走 fastjson 序列化。
 * TTL 在每次 put 时刷一次，意味着活跃会话会自动延寿，闲置 1h 后整组失效（Redis 自动 GC）。
 * <p>
 * 装配规则：
 * <ul>
 *   <li>{@code @ConditionalOnProperty}：开关 OFF 时整个 bean 不注册，自然降级到 Noop；</li>
 *   <li>{@code @Primary}：开关 ON 时与 NoopWorkingMemoryService 共存，靠 @Primary 赢得 @Resource 注入。</li>
 * </ul>
 * 之所以不再用 {@code @ConditionalOnBean(StringRedisTemplate.class)}：该条件作用于 component scan 类时，
 * 在 RedisAutoConfiguration 完成前就被评估，会误判为缺失。spring-data-redis starter 已加，
 * StringRedisTemplate 必然存在，无需再加守门。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "agent.working-memory", name = "enabled", havingValue = "true")
public class RedisWorkingMemoryService implements IWorkingMemoryService {

    /**
     * 按类型注入：避免 @Resource 走名字 → 命中 RedisAutoConfiguration 注册的
     * {@code redisTemplate}（RedisTemplate&lt;Object,Object&gt;）类型不匹配。
     * 用 @Autowired 走 by-type，直接拿到 stringRedisTemplate bean。
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${agent.working-memory.key-prefix:wm:}")
    private String keyPrefix;

    @Value("${agent.working-memory.ttl-seconds:3600}")
    private long defaultTtlSeconds;

    @PostConstruct
    public void logBoundImpl() {
        log.info("[WM] RedisWorkingMemoryService bound (prefix={}, ttl={}s) — wm:* 键将随每个 step doApply 写入",
                keyPrefix, defaultTtlSeconds);
    }

    @Override
    public void put(String sessionId, String key, Object value) {
        put(sessionId, key, value, Duration.ofSeconds(defaultTtlSeconds));
    }

    @Override
    public void put(String sessionId, String key, Object value, Duration ttl) {
        if (sessionId == null || sessionId.isBlank() || key == null) return;
        String redisKey = buildKey(sessionId, key);
        try {
            String json = value == null ? "null" : JSON.toJSONString(value);
            stringRedisTemplate.opsForValue().set(redisKey, json, ttl);
            log.debug("[WM] put {} ({} bytes)", redisKey, json.length());
        } catch (Exception e) {
            log.warn("WM put failed sessionId={} key={} err={}", sessionId, key, e.getMessage());
        }
    }

    @Override
    public <T> T get(String sessionId, String key, Class<T> type) {
        if (sessionId == null || sessionId.isBlank() || key == null) return null;
        try {
            String json = stringRedisTemplate.opsForValue().get(buildKey(sessionId, key));
            if (json == null || "null".equals(json)) return null;
            // 字符串特化：避免 fastjson 把带引号的 JSON 字符串再剥一层
            if (type == String.class) {
                if (json.length() >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
                    return type.cast(JSON.parseObject(json, String.class));
                }
                return type.cast(json);
            }
            return JSON.parseObject(json, type);
        } catch (Exception e) {
            log.warn("WM get failed sessionId={} key={} err={}", sessionId, key, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, String> dump(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return new HashMap<>();
        String pattern = buildKey(sessionId, "*");
        Map<String, String> result = new HashMap<>();
        try {
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) return result;
            String prefix = buildKey(sessionId, "");
            for (String full : keys) {
                String shortKey = full.startsWith(prefix) ? full.substring(prefix.length()) : full;
                String val = stringRedisTemplate.opsForValue().get(full);
                if (val != null) result.put(shortKey, val);
            }
        } catch (Exception e) {
            log.warn("WM dump failed sessionId={} err={}", sessionId, e.getMessage());
        }
        return result;
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            Set<String> keys = stringRedisTemplate.keys(buildKey(sessionId, "*"));
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("WM clear failed sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    /**
     * P1.2.3 多租户隔离：key = prefix + [tenantId:] + [userId:] + sessionId + : + key
     * tenantId/userId 从 MDC 读（MdcTraceFilter 写入，ThreadPoolConfig ContextSnapshot 接力到异步线程）。
     * 无 tenantId/userId 时退化为原来的 prefix + sessionId + : + key。
     */
    private String buildKey(String sessionId, String key) {
        StringBuilder sb = new StringBuilder(keyPrefix);
        String tenantId = MDC.get("tenantId");
        if (tenantId != null && !tenantId.isBlank()) {
            sb.append(tenantId).append(':');
        }
        String userId = MDC.get("userId");
        if (userId != null && !userId.isBlank()) {
            sb.append(userId).append(':');
        }
        sb.append(sessionId).append(':').append(key);
        return sb.toString();
    }
}
