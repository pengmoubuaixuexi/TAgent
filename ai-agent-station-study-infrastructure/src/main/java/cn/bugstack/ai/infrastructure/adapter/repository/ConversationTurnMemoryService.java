package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.memory.IConversationSummarizer;
import cn.bugstack.ai.domain.agent.service.memory.IConversationTurnMemoryService;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.MemoryCacheService;
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

    @Resource
    private MemoryCacheService memoryCache;

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
        String memoryUserMessage = buildMemoryUserMessage(request, userMessage);

        String cleanedOutput = OutputFilter.cleanForUser(finalOutput);
        if (cleanedOutput == null || cleanedOutput.isBlank()) return;

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = extractUserId(conversationId);
        }

        LocalDateTime now = LocalDateTime.now();
        String agentId = request.getAiAgentId();
        List<AiChatMemory> newRows = List.of(
                AiChatMemory.builder()
                        .conversationId(conversationId)
                        .userId(userId)
                        .agentId(agentId)
                        .runId(request.getRunId())
                        .messageType("USER")
                        .content(memoryUserMessage)
                        .createdAt(now)
                        .build(),
                AiChatMemory.builder()
                        .conversationId(conversationId)
                        .userId(userId)
                        .agentId(agentId)
                        .runId(request.getRunId())
                        .messageType("ASSISTANT")
                        .content(cleanedOutput)
                        .createdAt(now.plusNanos(1))
                        .build()
        );

        // 1) 先把 cache 列表拼齐 — Redis 已有则在内存中追加；Redis 未命中再回源 DB
        //    这样下一轮 advisor.before 读 chat_memory 永远命中 Redis，不查 DB
        List<AiChatMemory> existing = memoryCache.getChatList(conversationId);
        if (existing == null) {
            existing = aiChatMemoryDao.findByConversationIdFull(conversationId);
            if (existing == null) existing = new ArrayList<>();
        }
        List<AiChatMemory> merged = new ArrayList<>(existing.size() + newRows.size());
        merged.addAll(existing);
        merged.addAll(newRows);
        memoryCache.putChatList(conversationId, merged);

        int count = merged.size();
        agentRepository.updateChatMemoryCount(conversationId, count);
        log.info("[FinalTurnMemory] saved conversationId={} userId={} count={} (cache-first)", conversationId, userId, count);

        // 2) DB 落库异步 — 用户感知零延迟；失败不影响 Redis 命中（1h TTL 内仍可读）
        scheduleDbInsert(conversationId, newRows);

        // 3) 摘要任务异步触发（保持原行为）
        if (count > triggerThreshold) {
            triggerSummarizeAsync(conversationId, userId);
        }
    }

    private void scheduleDbInsert(String conversationId, List<AiChatMemory> rows) {
        Runnable task = () -> {
            try {
                aiChatMemoryDao.insertBatch(rows);
                log.debug("[FinalTurnMemory] async DB insert OK conv={} rows={}", conversationId, rows.size());
            } catch (Exception e) {
                // DB 写失败 → Redis 仍有最新数据，1h 内业务无感；记 ERROR 让运维感知
                log.error("[FinalTurnMemory] async DB insert FAILED conv={} rows={}: {}",
                        conversationId, rows.size(), e.getMessage(), e);
            }
        };
        if (threadPoolExecutor != null) {
            threadPoolExecutor.execute(task);
        } else {
            CompletableFuture.runAsync(task);
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
        // 优先用 cache 中的最新视图（包含本轮新追加的 2 条）；miss 才回源 DB
        List<AiChatMemory> rows = memoryCache.getChatList(conversationId);
        if (rows == null) {
            rows = aiChatMemoryDao.findByConversationIdFull(conversationId);
        }
        if (rows == null || rows.size() <= triggerThreshold) return;

        AiChatMemorySummary existing = memoryCache.getSummary(conversationId);
        if (existing == null) {
            existing = summaryDao.findByConversationId(conversationId);
            if (existing != null) memoryCache.putSummary(conversationId, existing);
        }
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

        // 先 put cache（下次 advisor.before 立刻读到新摘要），再落 DB
        memoryCache.putSummary(conversationId, summary);
        try {
            summaryDao.upsert(summary);
        } catch (Exception e) {
            // DB 失败：cache 已有新摘要，下次读不影响；但 detail 推进了，要让 cache 1h 内自然过期或显式 evict
            log.error("[FinalTurnMemory] summary DB upsert FAILED conv={}: {}", conversationId, e.getMessage(), e);
        }
        log.info("[FinalTurnMemory] summarized conversationId={} digestEnd={} total={} version={}",
                conversationId, digestEnd, rows.size(), summary.getVersion());
    }

    private String buildConversationId(ExecuteCommandEntity req) {
        String tid = req.getTenantId();
        String uid = req.getUserId();
        String sid = req.getSessionId();
        if (uid == null || uid.isBlank()) return sid;
        if (tid == null || tid.isBlank()) {
            return uid + ":" + sid;
        }
        return tid + ":" + uid + ":" + sid;
    }

    private String buildMemoryUserMessage(ExecuteCommandEntity request, String userMessage) {
        if (request == null || request.getSourceRunId() == null || request.getSourceRunId().isBlank()) {
            return userMessage;
        }
        String step = request.getRedoFromStep() != null ? "Step" + request.getRedoFromStep() : "指定步骤";
        return "基于历史运行 " + request.getSourceRunId() + " 的 " + step + " 进行修正：" + userMessage;
    }

    private String extractUserId(String conversationId) {
        if (conversationId == null) return null;
        String[] parts = conversationId.split(":");
        if (parts.length >= 3) return parts[1];
        if (parts.length == 2) return parts[0];
        return null;
    }
}
