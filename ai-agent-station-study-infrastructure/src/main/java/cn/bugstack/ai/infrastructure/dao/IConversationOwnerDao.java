package cn.bugstack.ai.infrastructure.dao;

import org.apache.ibatis.annotations.*;

@Mapper
public interface IConversationOwnerDao {
    @Select("SELECT user_id FROM auth_conversation_owner WHERE conversation_id = #{id}")
    String owner(@Param("id") String id);

    @Insert("INSERT IGNORE INTO auth_conversation_owner(conversation_id,user_id) VALUES(#{id},#{userId})")
    int claim(@Param("id") String id, @Param("userId") String userId);
}
