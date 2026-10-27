package cn.bugstack.ai.domain.agent.service.attachment;

import cn.bugstack.ai.domain.agent.model.entity.ChatFileInput;
import cn.bugstack.ai.domain.agent.model.entity.ChatFileRef;

import java.util.List;
import java.util.Map;

/**
 * Chat attachments: uploaded files and references to the user's own artifacts.
 *
 * <p>Every method takes the owner from the caller's authenticated identity or the
 * server-authored run scope, never from model arguments.</p>
 */
public interface IChatFileService {

    /** Attaches the selected uploads and artifacts to a run's user message, in order. */
    List<ChatFileRef> bindToRun(String userId, String conversationId, String sessionId, String runId,
                                List<ChatFileInput> inputs);

    /** Gives a redo run the attachments its source run received. */
    List<ChatFileRef> copyRun(String userId, String sourceRunId, String runId);

    List<ChatFileRef> forRun(String userId, String runId);

    /** All sent files in this owner's session, including purged entries; metadata only, never bodies/previews. */
    List<ChatFileRef> sessionIndex(String userId, String sessionId);

    /** Whether earlier messages of this session carry attachments the model may still read. */
    boolean sessionHasFiles(String userId, String sessionId);

    /** Current metadata (including PURGED status) for attachments listed in stored messages. */
    List<ChatFileRef> describe(String userId, List<String> attachmentIds);

    /** read_attachment: the scope is the request's trusted ToolContext. Returns a JSON result for the model. */
    String read(Map<String, Object> trustedScope, String arguments);

    int deleteByConversation(String conversationId, String userId);

    int deleteByUser(String userId);
}
