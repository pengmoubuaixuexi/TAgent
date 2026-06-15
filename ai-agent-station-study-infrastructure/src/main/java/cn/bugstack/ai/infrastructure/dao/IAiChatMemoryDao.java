package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiChatMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Spring AI ChatMemory 持久化 DAO（P0.2.1）。
 * <p>
 * 简单四方法对齐 {@code ChatMemoryRepository}：列对话 / 取消息 / 批量保存 / 删对话。
 */
@Mapper
public interface IAiChatMemoryDao {

    List<String> findConversationIds();

    List<AiChatMemory> findByConversationId(@Param("conversationId") String conversationId);

    void insertBatch(@Param("list") List<AiChatMemory> list);

    void deleteByConversationId(@Param("conversationId") String conversationId);

    /** 带归属校验的删除：只删属于该 userId 的会话，返回删除行数（0=不属于该用户/不存在） */
    int deleteByConversationIdAndUserId(@Param("conversationId") String conversationId,
                                        @Param("userId") String userId);

    /** 查用户的会话列表：conversation_id + 消息数 + 首条用户消息预览 */
    List<java.util.Map<String, Object>> findConversationsByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 查用户的完整会话消息（按创建时间排序） */
    List<AiChatMemory> findByConversationIdFull(@Param("conversationId") String conversationId);

    /**
     * 游标分页查会话消息。SQL 取最近 N 条按 id DESC，应用层倒置成 ASC 给前端按时间正序展示。
     * <p>
     * 用法：
     * - 首次：beforeId=null, limit=30 → 返回最近 30 条
     * - 滚到顶加载更早：beforeId=本批最早消息 id, limit=30 → 返回再往前 30 条
     * - beforeId 为 null 时不加 WHERE 限制，否则加 {@code WHERE id < #{beforeId}}
     * @return DESC 顺序的 N 条（应用层负责反转）
     */
    List<AiChatMemory> findByConversationIdPaged(@Param("conversationId") String conversationId,
                                                 @Param("beforeId") Long beforeId,
                                                 @Param("limit") int limit);
}
