package cn.bugstack.ai.infrastructure.adapter.repository.cache;

import cn.bugstack.ai.infrastructure.dao.po.AiChatMemory;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import cn.bugstack.ai.infrastructure.dao.po.AiEpisodicMemory;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Memory 层 Redis 缓存（chat_memory 列表 / chat_memory_summary / episodic by session）。
 * <p>
 * 策略：
 * <ul>
 *   <li>读：cache hit 返回；miss 由调用方查 DB，再回填缓存</li>
 *   <li>写：调用方先 put 缓存（同步），再异步落 DB；下次读永远命中 Redis</li>
 *   <li>失败兜底：任何 Redis 异常被 catch，调用方退回纯 DB 路径，不阻断业务</li>
 * </ul>
 * <p>
 * <b>不缓存</b>的查询：LTM 向量检索、episodic getOtherSessions/getRecent（参数组合多，命中率低）。
 */
@Slf4j
@Component
public class MemoryCacheService {

    private static final String K_CHAT_LIST = "memory:chat:list:";       // + conversationId
    private static final String K_SUMMARY   = "memory:chat:summary:";    // + conversationId
    private static final String K_EPISODIC  = "memory:episodic:sess:";   // + sessionId

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${agent.memory-cache.ttl-seconds:3600}")
    private long ttlSeconds;

    @Value("${agent.memory-cache.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void logState() {
        log.info("[MemoryCache] enabled={} ttl={}s", enabled, ttlSeconds);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ---------------- chat_memory list ----------------

    /** @return null 表示 cache miss；空 list 表示已缓存为"无消息" */
    public List<AiChatMemory> getChatList(String conversationId) {
        if (!enabled || conversationId == null || conversationId.isBlank()) return null;
        try {
            String json = stringRedisTemplate.opsForValue().get(K_CHAT_LIST + conversationId);
            if (json == null) return null;
            return JSON.parseObject(json, new TypeReference<List<AiChatMemory>>() {});
        } catch (Exception e) {
            log.warn("[MemoryCache] getChatList failed conv={}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    public void putChatList(String conversationId, List<AiChatMemory> list) {
        if (!enabled || conversationId == null || conversationId.isBlank() || list == null) return;
        try {
            String json = JSON.toJSONString(list);
            stringRedisTemplate.opsForValue().set(K_CHAT_LIST + conversationId, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[MemoryCache] putChatList failed conv={}: {}", conversationId, e.getMessage());
        }
    }

    public void evictChatList(String conversationId) {
        if (!enabled || conversationId == null || conversationId.isBlank()) return;
        try {
            stringRedisTemplate.delete(K_CHAT_LIST + conversationId);
        } catch (Exception e) {
            log.warn("[MemoryCache] evictChatList failed conv={}: {}", conversationId, e.getMessage());
        }
    }

    // ---------------- chat_memory_summary ----------------

    /** @return null 表示 cache miss 或显式无 summary（调用方自行处理 ambiguity） */
    public AiChatMemorySummary getSummary(String conversationId) {
        if (!enabled || conversationId == null || conversationId.isBlank()) return null;
        try {
            String json = stringRedisTemplate.opsForValue().get(K_SUMMARY + conversationId);
            if (json == null || "null".equals(json)) return null;
            return JSON.parseObject(json, AiChatMemorySummary.class);
        } catch (Exception e) {
            log.warn("[MemoryCache] getSummary failed conv={}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    public void putSummary(String conversationId, AiChatMemorySummary summary) {
        if (!enabled || conversationId == null || conversationId.isBlank() || summary == null) return;
        try {
            String json = JSON.toJSONString(summary);
            stringRedisTemplate.opsForValue().set(K_SUMMARY + conversationId, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[MemoryCache] putSummary failed conv={}: {}", conversationId, e.getMessage());
        }
    }

    public void evictSummary(String conversationId) {
        if (!enabled || conversationId == null || conversationId.isBlank()) return;
        try {
            stringRedisTemplate.delete(K_SUMMARY + conversationId);
        } catch (Exception e) {
            log.warn("[MemoryCache] evictSummary failed conv={}: {}", conversationId, e.getMessage());
        }
    }

    // ---------------- episodic by sessionId ----------------

    public AiEpisodicMemory getEpisodicBySession(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) return null;
        try {
            String json = stringRedisTemplate.opsForValue().get(K_EPISODIC + sessionId);
            if (json == null || "null".equals(json)) return null;
            return JSON.parseObject(json, AiEpisodicMemory.class);
        } catch (Exception e) {
            log.warn("[MemoryCache] getEpisodicBySession failed session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public void putEpisodicBySession(String sessionId, AiEpisodicMemory memory) {
        if (!enabled || sessionId == null || sessionId.isBlank() || memory == null) return;
        try {
            String json = JSON.toJSONString(memory);
            stringRedisTemplate.opsForValue().set(K_EPISODIC + sessionId, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[MemoryCache] putEpisodicBySession failed session={}: {}", sessionId, e.getMessage());
        }
    }

    public void evictEpisodicBySession(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) return;
        try {
            stringRedisTemplate.delete(K_EPISODIC + sessionId);
        } catch (Exception e) {
            log.warn("[MemoryCache] evictEpisodicBySession failed session={}: {}", sessionId, e.getMessage());
        }
    }
}
