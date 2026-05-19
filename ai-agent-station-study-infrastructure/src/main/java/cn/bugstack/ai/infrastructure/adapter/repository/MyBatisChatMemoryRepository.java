package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spring AI {@link ChatMemoryRepository} 的 MyBatis 实现（P0.2.1）。
 * <p>
 * 替换默认的 {@code InMemoryChatMemoryRepository}，把对话历史落到 {@code ai_chat_memory} 表，
 * JVM 重启后多轮对话不丢。
 * <p>
 * 类型映射：
 * <ul>
 *   <li>{@link UserMessage}      → message_type='USER'</li>
 *   <li>{@link AssistantMessage} → message_type='ASSISTANT'</li>
 *   <li>{@link SystemMessage}    → message_type='SYSTEM'</li>
 *   <li>TOOL（工具调用/结果消息）→ 当前跳过（含 toolCalls 结构，单字段无法还原）；
 *       未来需要时另起表 {@code ai_chat_memory_tool} 存 toolCallId / toolName / arguments</li>
 * </ul>
 * <p>
 * <b>批量写入</b>：{@code saveAll} 用 INSERT ... VALUES (...),(...),...，单次 round-trip 节省连接开销。
 * <p>
 * <b>未实现历史压缩</b>：单 conversation 持续累积；P0.2.2 滚动摘要落地后会有清理 / 摘要任务。
 */
@Slf4j
@Component
public class MyBatisChatMemoryRepository implements ChatMemoryRepository {

    @Resource
    private IAiChatMemoryDao aiChatMemoryDao;

    /** 由调用方（FixedAgentExecuteStrategy 等）通过 {@link cn.bugstack.ai.domain.agent.service.memory.ChatMemoryContext} 设置 */

    @Override
    public List<String> findConversationIds() {
        List<String> ids = aiChatMemoryDao.findConversationIds();
        return ids == null ? Collections.emptyList() : ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Collections.emptyList();
        List<AiChatMemory> rows = aiChatMemoryDao.findByConversationId(conversationId);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<Message> messages = new ArrayList<>(rows.size());
        for (AiChatMemory r : rows) {
            Message m = toSpringMessage(r);
            if (m != null) messages.add(m);
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (messages == null || messages.isEmpty()) return;

        // Spring AI 语义：saveAll 是"覆盖该 conversation 的全部历史"。先删后插保持幂等。
        // 这意味着每轮对话都是 [全量历史] 写入；和 InMemoryChatMemoryRepository 行为一致。
        // 高频 conversation 后续可优化为追加增量（按 id 比对），当前阶段简单起。
        aiChatMemoryDao.deleteByConversationId(conversationId);

        List<AiChatMemory> rows = new ArrayList<>(messages.size());
        LocalDateTime now = LocalDateTime.now();
        String userId = extractUserId(conversationId);
        String agentId = cn.bugstack.ai.domain.agent.service.memory.ChatMemoryContext.getAgentId();
        for (Message msg : messages) {
            String type = msg.getMessageType() == null ? null : msg.getMessageType().name();
            if (type == null || type.equals("TOOL")) continue;
            rows.add(AiChatMemory.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .agentId(agentId)
                    .messageType(type)
                    .content(msg.getText())
                    .createdAt(now)
                    .build());
        }
        if (!rows.isEmpty()) {
            aiChatMemoryDao.insertBatch(rows);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        aiChatMemoryDao.deleteByConversationId(conversationId);
    }

    private Message toSpringMessage(AiChatMemory r) {
        if (r == null || r.getMessageType() == null) return null;
        MessageType type;
        try {
            type = MessageType.valueOf(r.getMessageType());
        } catch (IllegalArgumentException e) {
            log.warn("ai_chat_memory row id={} unknown message_type={}, treating as USER", r.getId(), r.getMessageType());
            return new UserMessage(r.getContent());
        }
        return switch (type) {
            case USER       -> new UserMessage(r.getContent());
            case ASSISTANT  -> new AssistantMessage(r.getContent());
            case SYSTEM     -> new SystemMessage(r.getContent());
            case TOOL       -> null; // 见类注释
        };
    }

    /**
     * 从 conversationId 提取 userId。格式: tenant:userId:sessionId 或 userId:sessionId
     */
    private String extractUserId(String conversationId) {
        if (conversationId == null) return null;
        String[] parts = conversationId.split(":");
        if (parts.length >= 3) return parts[1];  // tenant:userId:sessionId
        if (parts.length == 2) return parts[0];   // userId:sessionId
        return null;
    }
}
