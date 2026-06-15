package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiLongTermMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆元数据 DAO（P1.7）。
 * <p>
 * 向量数据走 PgVectorStore，本表只保留可读元信息和访问统计（用于衰减/归档/审计）。
 */
@Mapper
public interface IAiLongTermMemoryDao {

    void insert(AiLongTermMemory po);

    AiLongTermMemory findByMemoryId(@Param("memoryId") String memoryId);

    List<AiLongTermMemory> findByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 召回时累计访问次数 + 更新最近访问时间（衰减算法用） */
    void touchAccess(@Param("memoryId") String memoryId);

    void archive(@Param("memoryId") String memoryId);

    /**
     * P2.1 10.2 遗忘衰减（2026-06-07 重设计）：找闲置超过 "baseDays + k(topic) × min(access_count, cap)"
     * 宽限期的冷记忆候选。access_count 经 1 天节流后≈被召回的不同天数；k 按 topic 分档（技能/偏好耐久
     * 大 k，计划/情况会过期小 k，其它默认）；cap 为热度宽限上界。画像永不归档。
     */
    List<AiLongTermMemory> findStaleCandidates(@Param("baseDays") int baseDays,
                                               @Param("kDurable") int kDurable,
                                               @Param("kEphemeral") int kEphemeral,
                                               @Param("kDefault") int kDefault,
                                               @Param("cap") int cap,
                                               @Param("limit") int limit);

    /** 批量归档 */
    void archiveByIds(@Param("ids") List<Long> ids);

    /** P2.1 10.3 冲突解决：查找同用户同 topic 的活跃记忆 */
    List<AiLongTermMemory> findByUserIdAndTopic(@Param("userId") String userId,
                                                @Param("topic") String topic,
                                                @Param("limit") int limit);

    /** 更新记忆内容（合并场景） */
    void updateContent(@Param("memoryId") String memoryId, @Param("content") String content);

    /** 核心记忆（V041）：返回用户全部"画像:"槽位，全量注入，不按频率排序 */
    List<AiLongTermMemory> findCoreByUser(@Param("userId") String userId, @Param("limit") int limit);

    /** 获取用户所有活跃 memory_id 集合，用于向量检索后交叉校验过滤归档记录 */
    List<String> findActiveMemoryIds(@Param("userId") String userId);
}
