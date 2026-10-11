package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Spring AI ChatMemory 滚动摘要 DAO（P0.2.2）。
 * <p>
 * 每个 conversation_id 唯一一行；空摘要行兼作会话写锁，摘要通过版本和水位线 CAS 推进。
 */
@Mapper
public interface IAiChatMemorySummaryDao {

    AiChatMemorySummary findByConversationId(@Param("conversationId") String conversationId);

    /** Cache validation without reading the summary body. */
    AiChatMemorySummary findRevision(@Param("conversationId") String conversationId);

    /** 必须在事务内调用；唯一键冲突时仅获取行锁，不覆盖已保存摘要。 */
    void ensureConversation(@Param("conversationId") String conversationId, @Param("userId") String userId);

    AiChatMemorySummary findByConversationIdForUpdate(@Param("conversationId") String conversationId);

    /** 返回 0 表示快照已过期；调用方丢弃生成结果，不能覆盖新摘要。 */
    int advanceSummary(@Param("summary") AiChatMemorySummary summary,
                       @Param("expectedVersion") int expectedVersion,
                       @Param("expectedWatermark") int expectedWatermark);

    void deleteByConversationId(@Param("conversationId") String conversationId);
}
