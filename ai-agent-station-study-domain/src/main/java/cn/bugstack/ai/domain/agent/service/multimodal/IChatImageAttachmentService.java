package cn.bugstack.ai.domain.agent.service.multimodal;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageInput;
import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;

import java.util.List;

/**
 * Persists and restores image attachments independently from provider APIs.
 */
public interface IChatImageAttachmentService {

    List<ChatImageRef> prepareAndStore(String conversationId,
                                       String userId,
                                       String runId,
                                       String message,
                                       List<ChatImageInput> inputs);

    List<ChatImageRef> loadByAttachmentIds(List<String> attachmentIds);

    ChatImageRef loadOwned(String attachmentId, String userId);

    int deleteOwnedByConversation(String conversationId, String userId);
}
