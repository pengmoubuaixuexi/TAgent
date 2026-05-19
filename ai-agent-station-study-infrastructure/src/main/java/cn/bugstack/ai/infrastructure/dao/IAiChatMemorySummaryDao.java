package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Spring AI ChatMemory 滚动摘要 DAO（P0.2.2）。
 * <p>
 * UPSERT 语义：每个 conversation_id 唯一一行；用 INSERT ... ON DUPLICATE KEY UPDATE 简化业务侧逻辑。
 */
@Mapper
public interface IAiChatMemorySummaryDao {

    AiChatMemorySummary findByConversationId(@Param("conversationId") String conversationId);

    void upsert(AiChatMemorySummary po);

    void deleteByConversationId(@Param("conversationId") String conversationId);
}
