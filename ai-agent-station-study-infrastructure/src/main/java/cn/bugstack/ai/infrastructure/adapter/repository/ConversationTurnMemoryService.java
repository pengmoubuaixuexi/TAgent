package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.memory.IConversationSummarizer;
import cn.bugstack.ai.domain.agent.service.memory.IConversationTurnMemoryService;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao;
import cn.bugstack.ai.infrastructure.dao.IAiChatMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemory;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ConversationTurnMemoryService implements IConversationTurnMemoryService {

    @Resource
    private IAiChatMemoryDao aiChatMemoryDao;

    @Resource
    private IAiChatMemorySummaryDao summaryDao;

    @Resource
    private IConversationSummarizer summarizer;

    @Resource
    private IAgentRepository agentRepository;

    @Autowired(required = false)
    private java.util.concurrent.ThreadPoolExecutor threadPoolExecutor;

    @Value("${agent.chat-memory.summarize-trigger:30}")
    private int triggerThreshold;

    @Value("${agent.chat-memory.summarize-keep-recent:10}")
    private int keepRecent;

    @Override
    public void saveFinalTurn(ExecuteCommandEntity request, String finalOutput) {
        if (request == null || finalOutput == null || finalOutput.isBlank()) return;
        String conversationId = buildConversationId(request);
        if (conversationId == null || conversationId.isBlank()) return;
        String userMessage = request.getMessage();
        if (userMessage == null || userMessage.isBlank()) return;

        String cleanedOutput = OutputFilter.cleanForUser(finalOutput);
        if (cleanedOutput == null || cleanedOutput.isBlank()) return;

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = extractUserId(conversationId);
        }

        LocalDateTime now = LocalDateTime.now();
        String agentId = request.getAiAgentId();
        List<AiChatMemory> rows = List.of(
                AiChatMemory.builder()
                        .conversationId(conversationId)
                        .userId(userId)
                        .agentId(agentId)
                        .messageType("USER")
                        .content(userMessage)
                        .createdAt(now)
                        .build(),
                AiChatMemory.builder()
                        .conversationId(conversationId)
                        .userId(userId)
                        .agentId(agentId)
                        .messageType("ASSISTANT")
                        .content(cleanedOutput)
                        .createdAt(now.plusNanos(1))
                        .build()
        );
        aiChatMemoryDao.insertBatch(rows);

        int count = countConversationRows(conversationId);
        agentRepository.updateChatMemoryCount(conversationId, count);
        log.info("[FinalTurnMemory] saved conversationId={} userId={} count={}", conversationId, userId, count);

        if (count > triggerThreshold) {
            triggerSummarizeAsync(conversationId, userId);
        }
    }

    private void triggerSummarizeAsync(String conversationId, String userId) {
        Runnable task = () -> {
            try {
                doSummarize(conversationId, userId);
            } catch (Exception e) {
                log.warn("[FinalTurnMemory] summarize failed for conversationId={}: {}", conversationId, e.getMessage(), e);
            }
        };
        if (threadPoolExecutor != null) {
            threadPoolExecutor.execute(task);
        } else {
            CompletableFuture.runAsync(task);
        }
    }

    private void doSummarize(String conversationId, String userId) {
        List<AiChatMemory> rows = aiChatMemoryDao.findByConversationIdFull(conversationId);
        if (rows == null || rows.size() <= triggerThreshold) return;

        AiChatMemorySummary existing = summaryDao.findByConversationId(conversationId);
        int watermark = existing != null && existing.getSummaryMsgCount() != null ? existing.getSummaryMsgCount() : 0;
        if (watermark > 0 && rows.size() < watermark + keepRecent) {
            return;
        }

        int digestEnd = Math.min(watermark + keepRecent, rows.size());
        if (digestEnd <= watermark) return;

        List<Message> toDigest = new ArrayList<>();
        for (AiChatMemory row : rows.subList(watermark, digestEnd)) {
            if ("USER".equals(row.getMessageType())) {
                toDigest.add(new UserMessage(row.getContent()));
            } else if ("ASSISTANT".equals(row.getMessageType())) {
                toDigest.add(new AssistantMessage(row.getContent()));
            }
        }
        if (toDigest.isEmpty()) return;

        String previousSummary = existing == null ? null : existing.getSummary();
        String newSummary = summarizer.summarizeWithPrevious(previousSummary, toDigest);
        if (newSummary == null || newSummary.isBlank()) return;

        AiChatMemorySummary summary = AiChatMemorySummary.builder()
                .conversationId(conversationId)
                .userId(userId != null && !userId.isBlank() ? userId : extractUserId(conversationId))
                .summary(newSummary)
                .lastMessageId(null)
                .version(existing == null || existing.getVersion() == null ? 1 : existing.getVersion() + 1)
                .summaryMsgCount(digestEnd)
                .detailMsgCount(rows.size())
                .build();
        summaryDao.upsert(summary);
        log.info("[FinalTurnMemory] summarized conversationId={} digestEnd={} total={}", conversationId, digestEnd, rows.size());
    }

    private int countConversationRows(String conversationId) {
        List<AiChatMemory> rows = aiChatMemoryDao.findByConversationIdFull(conversationId);
        return rows == null ? 0 : rows.size();
    }

    private String buildConversationId(ExecuteCommandEntity req) {
        String tid = req.getTenantId();
        String uid = req.getUserId();
        String sid = req.getSessionId();
        if (tid == null || tid.isBlank()) {
            if (uid == null || uid.isBlank()) return sid;
            return uid + ":" + sid;
        }
        if (uid == null || uid.isBlank()) return tid + ":" + sid;
        return tid + ":" + uid + ":" + sid;
    }

    private String extractUserId(String conversationId) {
        if (conversationId == null) return null;
        String[] parts = conversationId.split(":");
        if (parts.length >= 3) return parts[1];
        if (parts.length == 2) return parts[0];
        return null;
    }
}
