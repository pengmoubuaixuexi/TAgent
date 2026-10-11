package cn.bugstack.ai.infrastructure.adapter.repository;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
public class ConversationTurnMemoryService implements IConversationTurnMemoryService {
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository runHistory;

    @Resource
    private IAiChatMemoryDao aiChatMemoryDao;

    @Resource
    private IAiChatMemorySummaryDao summaryDao;

    @Resource
    private IConversationSummarizer summarizer;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private MemoryCacheService memoryCache;

    @Autowired(required = false)
    private java.util.concurrent.ThreadPoolExecutor threadPoolExecutor;

    @Value("${agent.chat-memory.summarize-trigger:30}")
    private int triggerThreshold;

    @Value("${agent.chat-memory.summarize-keep-recent:10}")
    private int keepRecent;

    private final Set<String> summariesInFlight = ConcurrentHashMap.newKeySet();

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

        String owner = userId;
        int count = inNewTransaction(() -> {
            // The existing unique conversation key serializes writers across application instances.
            // Lock before reading so retrying the same run cannot append a second final turn.
            summaryDao.ensureConversation(conversationId, owner);
            summaryDao.findByConversationIdForUpdate(conversationId);
            List<AiChatMemory> existing = aiChatMemoryDao.findByConversationIdFull(conversationId);
            if (existing == null) existing = List.of();
            LocalDateTime now = LocalDateTime.now();
            // Preserve the historical (created_at, id) order even if another instance's clock is ahead.
            if (!existing.isEmpty()) {
                LocalDateTime latest = existing.get(existing.size() - 1).getCreatedAt();
                if (latest != null && latest.isAfter(now)) now = latest;
            }
            String agentId = request.getAiAgentId();
            List<AiChatMemory> newRows = List.of(
                    AiChatMemory.builder()
                            .conversationId(conversationId)
                            .userId(owner)
                            .agentId(agentId)
                            .runId(request.getRunId())
                            .messageType("USER")
                            .content(memoryUserMessage)
                            .contentParts(ChatMessagePartsCodec.encode(memoryUserMessage, request.getImages()))
                            .mediaCount(request.getImages() == null ? 0 : request.getImages().size())
                            .createdAt(now)
                            .build(),
                    AiChatMemory.builder()
                            .conversationId(conversationId)
                            .userId(owner)
                            .agentId(agentId)
                            .runId(request.getRunId())
                            .messageType("ASSISTANT")
                            .content(cleanedOutput)
                            .mediaCount(0)
                            .createdAt(now.plusNanos(1))
                            .build()
            );

            List<AiChatMemory> missing = new ArrayList<>(newRows);
            if (request.getRunId() != null && !request.getRunId().isBlank()) {
                for (AiChatMemory row : existing) {
                    if (request.getRunId().equals(row.getRunId())) {
                        missing.removeIf(candidate -> candidate.getMessageType().equals(row.getMessageType()));
                    }
                }
            }
            if (!missing.isEmpty()) aiChatMemoryDao.insertBatch(missing);
            if(runHistory!=null) {
                String canonical=existing.stream().filter(row -> java.util.Objects.equals(request.getRunId(),row.getRunId())
                        && "ASSISTANT".equals(row.getMessageType())).map(AiChatMemory::getContent).findFirst().orElse(cleanedOutput);
                runHistory.answer(owner,request.getRunId(),canonical);
            }
            return existing.size() + missing.size();
        });
        // Never publish an uncommitted or stale merged snapshot. Readers refill from committed DB rows.
        memoryCache.evictChatList(conversationId);
        log.info("[FinalTurnMemory] committed conversationId={} count={}", conversationId, count);
        if (count > triggerThreshold) {
            summarizeAsync(conversationId);
        }
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // Final history has its own durable boundary, even if a caller has an unrelated transaction.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> action.get());
    }

    /** Shared by the final-turn path and the legacy ChatMemory.add adapter. */
    public void summarizeAsync(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || !summariesInFlight.add(conversationId)) return;
        Runnable task = () -> {
            try {
                doSummarize(conversationId, extractUserId(conversationId));
            } catch (Exception e) {
                log.warn("[FinalTurnMemory] summarize failed for conversationId={}: {}", conversationId, e.getMessage(), e);
            }
        };
        try {
            Runnable maintenance = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.captureMaintenance(task);
            Runnable queued = () -> {
                try {
                    maintenance.run();
                } catch (RuntimeException e) {
                    log.debug("[FinalTurnMemory] queued summary no longer permitted conversationId={}: {}", conversationId, e.getMessage());
                } finally {
                    // A revoked permit can reject before task.run(), so release the gate outside the permit as well.
                    summariesInFlight.remove(conversationId);
                }
            };
            if (threadPoolExecutor != null) threadPoolExecutor.execute(queued);
            else CompletableFuture.runAsync(queued);
        } catch (RuntimeException e) {
            summariesInFlight.remove(conversationId);
            log.warn("[FinalTurnMemory] summary scheduling failed conversationId={}", conversationId, e);
        }
    }

    private void doSummarize(String conversationId, String userId) {
        SummarySnapshot snapshot = inNewTransaction(() -> {
            summaryDao.ensureConversation(conversationId, userId);
            AiChatMemorySummary existing = summaryDao.findByConversationIdForUpdate(conversationId);
            List<AiChatMemory> rows = aiChatMemoryDao.findByConversationIdFull(conversationId);
            return new SummarySnapshot(existing, rows == null ? List.of() : rows);
        });
        List<AiChatMemory> rows = snapshot.rows();
        if (rows.size() <= triggerThreshold || keepRecent <= 0) return;
        AiChatMemorySummary existing = snapshot.summary();
        int watermark = existing == null || existing.getSummaryMsgCount() == null ? 0 : existing.getSummaryMsgCount();
        int version = existing == null || existing.getVersion() == null ? 0 : existing.getVersion();
        if (watermark < 0 || watermark >= rows.size()) return;
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
                .lastMessageId(rows.get(digestEnd - 1).getId())
                .version(version + 1)
                .summaryMsgCount(digestEnd)
                .detailMsgCount(rows.size())
                .build();

        int updated = inNewTransaction(() -> summaryDao.advanceSummary(summary, version, watermark));
        if (updated == 0) {
            log.debug("[FinalTurnMemory] discarded stale summary conversationId={} version={}", conversationId, version);
            return;
        }
        memoryCache.evictSummary(conversationId);
        log.info("[FinalTurnMemory] summarized conversationId={} digestEnd={} total={} version={}",
                conversationId, digestEnd, rows.size(), summary.getVersion());
    }

    private record SummarySnapshot(AiChatMemorySummary summary, List<AiChatMemory> rows) { }

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
