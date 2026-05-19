package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiEpisodicMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 跨会话摘要记忆 DAO（P2.1 Episodic Memory）。
 */
@Mapper
public interface IAiEpisodicMemoryDao {

    void insert(AiEpisodicMemory po);

    List<AiEpisodicMemory> findByUserId(@Param("userId") String userId, @Param("limit") int limit);

    void deleteOlderThan(@Param("userId") String userId, @Param("keepCount") int keepCount);

    AiEpisodicMemory findBySessionId(@Param("sessionId") String sessionId);

    void updateBySessionId(@Param("sessionId") String sessionId, @Param("summary") String summary, @Param("topic") String topic, @Param("lastSummarizedMsgCount") Integer lastSummarizedMsgCount);

    /** 查用户最近 N 条且在 5 天内的摘要 */
    List<AiEpisodicMemory> findByUserIdRecent(@Param("userId") String userId, @Param("limit") int limit, @Param("withinDays") int withinDays);

    /** 查用户最近 N 条（5 天内），排除指定 sessionId */
    List<AiEpisodicMemory> findByUserIdExcludeSessionRecent(@Param("userId") String userId, @Param("excludeSessionId") String excludeSessionId, @Param("limit") int limit, @Param("withinDays") int withinDays);
}
