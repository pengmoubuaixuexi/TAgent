package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiChatAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiChatAttachmentDao {

    void insertBatch(@Param("list") List<AiChatAttachment> list);

    List<AiChatAttachment> findByAttachmentIds(@Param("ids") List<String> attachmentIds);

    AiChatAttachment findOwned(@Param("attachmentId") String attachmentId,
                               @Param("userId") String userId);

    List<AiChatAttachment> findOwnedByConversation(@Param("conversationId") String conversationId,
                                                   @Param("userId") String userId);

    List<AiChatAttachment> findLegacyWithoutObjectKey();

    int markStoredInOss(@Param("attachmentId") String attachmentId,
                        @Param("storageProvider") String storageProvider,
                        @Param("bucketName") String bucketName,
                        @Param("objectKey") String objectKey,
                        @Param("etag") String etag);

    int deleteOwnedByConversation(@Param("conversationId") String conversationId,
                                  @Param("userId") String userId);
}
