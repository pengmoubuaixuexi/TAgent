package cn.bugstack.ai.infrastructure.dao;

import org.apache.ibatis.annotations.*;

@Mapper
public interface IConversationOwnerDao {
    @Select("SELECT user_id FROM auth_conversation_owner WHERE conversation_id = #{id}")
    String owner(@Param("id") String id);

    /**
     * Internal dispatches (including EvalOps) already persist an owner before the first answer.
     * Normalize to binary before UNION: legacy tables may use different collations, and owner
     * unanimity must distinguish case and trailing spaces just like the final Java equality check.
     */
    @Select("""
            SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                        WHEN COUNT(DISTINCT user_id) = 1
                         AND COUNT(*) = COUNT(NULLIF(user_id, CAST('' AS BINARY))) THEN MAX(user_id)
                        ELSE CAST('__legacy_unowned__' AS BINARY) END
            FROM (
              SELECT CAST(user_id AS BINARY) AS user_id FROM ai_chat_memory
              WHERE conversation_id IN (#{sessionId}, #{conversationId}, #{legacyConversationId})
              UNION ALL
              SELECT CAST(user_id AS BINARY) AS user_id FROM ai_run_history WHERE session_id = #{sessionId}
            ) persisted_owners
            """)
    String persistedOwner(@Param("sessionId") String sessionId,
                          @Param("conversationId") String conversationId,
                          @Param("legacyConversationId") String legacyConversationId);

    @Insert("INSERT IGNORE INTO auth_conversation_owner(conversation_id,user_id) VALUES(#{id},#{userId})")
    int claim(@Param("id") String id, @Param("userId") String userId);
}
