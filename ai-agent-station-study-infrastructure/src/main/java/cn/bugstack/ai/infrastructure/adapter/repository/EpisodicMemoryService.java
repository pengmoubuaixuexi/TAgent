package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.infrastructure.dao.IAiEpisodicMemoryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEpisodicMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨会话摘要记忆服务实现（P2.1 Episodic Memory）。
 * <p>
 * 每次会话结束把 Step4 的最终总结另存一份到 MySQL。
 * 新会话开场 EpisodicMemoryAdvisor 取最近 N 条注入 system prompt。
 * <p>
 * 无需向量检索——episodic 只读最近 N 条，MySQL 按时间倒序直接查。
 */
@Slf4j
@Service
public class EpisodicMemoryService implements IEpisodicMemoryService {

    @Resource
    private IAiEpisodicMemoryDao dao;

    @Override
    public void save(String userId, String tenantId, String sessionId, String topic, String summary) {
        if (userId == null || userId.isBlank() || summary == null || summary.isBlank()) {
            log.debug("episodic.save skip: empty userId or summary");
            return;
        }
        if (summary.length() > 512) {
            summary = summary.substring(0, 512);
        }
        AiEpisodicMemory po = AiEpisodicMemory.builder()
                .userId(userId)
                .tenantId(tenantId != null ? tenantId : "default")
                .sessionId(sessionId)
                .topic(topic)
                .summary(summary)
                .build();
        dao.insert(po);
        log.info("episodic.save OK userId={} sessionId={} topic={} summaryLen={}",
                userId, sessionId, topic, summary.length());
    }

    @Override
    public List<String> getRecent(String userId, int topN) {
        return getRecentWithinDays(userId, topN, 5);
    }

    @Override
    public List<String> getRecentWithinDays(String userId, int topN, int withinDays) {
        if (userId == null || userId.isBlank() || topN <= 0) {
            return Collections.emptyList();
        }
        List<AiEpisodicMemory> rows = dao.findByUserIdRecent(userId, topN, withinDays);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream()
                .map(AiEpisodicMemory::getSummary)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
    }

    @Override
    public void trim(String userId, int keepCount) {
        if (userId == null || userId.isBlank() || keepCount <= 0) {
            return;
        }
        dao.deleteOlderThan(userId, keepCount);
        log.info("episodic.trim userId={} keepCount={}", userId, keepCount);
    }

    @Override
    public void upsert(String userId, String tenantId, String sessionId, String topic, String summary, Integer lastSummarizedMsgCount) {
        if (userId == null || userId.isBlank() || summary == null || summary.isBlank()) return;
        if (summary.length() > 512) summary = summary.substring(0, 512);
        AiEpisodicMemory existing = dao.findBySessionId(sessionId);
        if (existing != null) {
            // 覆盖式：新 summary 直接替换旧的（LLM 已经做了"旧摘要 + 新消息 → 重新摘要"）
            dao.updateBySessionId(sessionId, summary, topic, lastSummarizedMsgCount);
            log.info("episodic.upsert UPDATED (overwrite) sessionId={} newLen={} lastSummarizedMsgCount={}",
                    sessionId, summary.length(), lastSummarizedMsgCount);
        } else {
            AiEpisodicMemory po = AiEpisodicMemory.builder()
                    .userId(userId).tenantId(tenantId != null ? tenantId : "default")
                    .sessionId(sessionId).topic(topic).summary(summary)
                    .lastSummarizedMsgCount(lastSummarizedMsgCount)
                    .build();
            dao.insert(po);
            log.info("episodic.upsert INSERTED userId={} sessionId={} summaryLen={} lastSummarizedMsgCount={}",
                    userId, sessionId, summary.length(), lastSummarizedMsgCount);
        }
    }

    @Override
    public String findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        AiEpisodicMemory po = dao.findBySessionId(sessionId);
        return po != null ? po.getSummary() : null;
    }

    @Override
    public int getLastSummarizedMsgCount(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return -1;
        AiEpisodicMemory po = dao.findBySessionId(sessionId);
        if (po == null || po.getLastSummarizedMsgCount() == null) return -1;
        return po.getLastSummarizedMsgCount();
    }

    @Override
    public List<String> getOtherSessions(String userId, String currentSessionId, int k, int withinDays) {
        if (userId == null || userId.isBlank() || k <= 0) return Collections.emptyList();
        List<AiEpisodicMemory> rows = dao.findByUserIdExcludeSessionRecent(userId, currentSessionId, k, withinDays);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        return rows.stream()
                .map(AiEpisodicMemory::getSummary)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
    }
}
