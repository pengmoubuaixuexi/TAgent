package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.memory.IConversationSummarizer;
import cn.bugstack.ai.infrastructure.dao.IAiChatMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 滚动摘要 ChatMemory（P0.2.2）。
 * <p>
 * 在 {@link ChatMemoryRepository} 之上加了两件事：
 * <ol>
 *   <li><b>窗口</b>：{@link #get(String)} 永远返回不超过 {@code maxMessages} 条最新消息</li>
 *   <li><b>摘要</b>：消息总数超过 {@code triggerThreshold} 时，异步调 {@link IConversationSummarizer} 把
 *       <i>除最近 {@code keepRecent} 条</i>之外的全部历史摘成一段，存到 {@code ai_chat_memory_summary}；
 *       同时从 ai_chat_memory 删掉已摘的部分。<br>
 *       下次 {@link #get(String)} 时把摘要作为 {@link SystemMessage} 拼到队首。</li>
 * </ol>
 * <p>
 * 默认 summarizer 是 no-op（{@code NoopConversationSummarizer}）：返回 null → 不写摘要 →
 * 行为退化为纯滑窗 + JDBC 持久化（等价于 P0.2.1 的 {@code MyBatisChatMemoryRepository} + MessageWindowChatMemory）。
 * 业务方接入 LLM-based summarizer 后，长对话场景才真正省 token。
 * <p>
 * <b>并发保护</b>：每个 conversation_id 用一个 AtomicBoolean 防止重复摘要任务在异步队列里堆积。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.chat-memory.summarize-enabled", havingValue = "true")
public class SummarizingChatMemory implements ChatMemory {

    @Resource
    private ChatMemoryRepository chatMemoryRepository;

    @Resource
    private IAiChatMemorySummaryDao summaryDao;

    @Resource
    private IConversationSummarizer summarizer;

    @Resource
    private cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository agentRepository;

    @Autowired(required = false)
    private java.util.concurrent.ThreadPoolExecutor threadPoolExecutor;

    /** 窗口大小：返回历史时最多带这么多条 */
    @Value("${agent.chat-memory.max-messages:20}")
    private int maxMessages;

    /** 消息总数超过这个阈值开始摘要 */
    @Value("${agent.chat-memory.summarize-trigger:30}")
    private int triggerThreshold;

    /** 摘要后保留的最新条数（其余全部归入摘要） */
    @Value("${agent.chat-memory.summarize-keep-recent:10}")
    private int keepRecent;

    /** 防重复摘要：同 conversationId 的摘要任务在跑就不再调度 */
    private final ConcurrentMap<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank() || messages == null || messages.isEmpty()) return;

        // saveAll 是覆盖语义；为追加效果，先读再合并
        // （Spring AI 的 MessageWindowChatMemory 也是这种做法）
        List<Message> existing = chatMemoryRepository.findByConversationId(conversationId);
        List<Message> merged = new ArrayList<>(existing.size() + messages.size());
        merged.addAll(existing);
        merged.addAll(messages);
        log.info("[STM.add] conversation={} existing={} new={} merged={}",
                conversationId, existing.size(), messages.size(), merged.size());
        chatMemoryRepository.saveAll(conversationId, merged);

        // 同步更新 Redis 中的消息计数（episodic 节流用）
        agentRepository.updateChatMemoryCount(conversationId, merged.size());

        // 窗口溢出 → 触发异步摘要
        if (merged.size() > triggerThreshold) {
            triggerSummarizeAsync(conversationId);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Collections.emptyList();

        List<Message> raw = chatMemoryRepository.findByConversationId(conversationId);
        if (raw == null) raw = Collections.emptyList();

        AiChatMemorySummary summary = summaryDao.findByConversationId(conversationId);
        int watermark = (summary != null && summary.getSummaryMsgCount() != null) ? summary.getSummaryMsgCount() : 0;

        // 取窗口：跳过已纳入摘要的消息，只取最近 maxMessages 条未摘要消息
        List<Message> unsummarized = watermark > 0 && watermark < raw.size()
                ? new ArrayList<>(raw.subList(watermark, raw.size()))
                : raw;
        List<Message> windowed = unsummarized.size() <= maxMessages
                ? unsummarized
                : new ArrayList<>(unsummarized.subList(unsummarized.size() - maxMessages, unsummarized.size()));
        log.info("[STM.get] conversation={} raw={} watermark={} unsummarized={} windowed={}",
                conversationId, raw.size(), watermark, unsummarized.size(), windowed.size());

        // 摘要存在则拼到队首；用 SystemMessage 让模型把它当背景而非对话
        if (summary != null && summary.getSummary() != null && !summary.getSummary().isBlank()) {
            List<Message> withSummary = new ArrayList<>(windowed.size() + 1);
            withSummary.add(new SystemMessage("【对话历史摘要】" + summary.getSummary()));
            withSummary.addAll(windowed);
            return withSummary;
        }
        return windowed;
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        chatMemoryRepository.deleteByConversationId(conversationId);
        summaryDao.deleteByConversationId(conversationId);
        inFlight.remove(conversationId);
    }

    /**
     * 异步摘要任务：保护性挑选最旧的部分给 summarizer，结果非空才写库 + 截断老消息。
     * 失败 / 返回 null 静默回退（消息保留在表里继续滚下次再试）。
     */
    private void triggerSummarizeAsync(String conversationId) {
        AtomicBoolean flag = inFlight.computeIfAbsent(conversationId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            log.debug("summarize already in-flight for conversation={}", conversationId);
            return;
        }
        Runnable task = () -> {
            try {
                doSummarize(conversationId);
            } catch (Exception e) {
                log.warn("summarize failed for conversation={}: {}", conversationId, e.getMessage());
            } finally {
                flag.set(false);
            }
        };
        if (threadPoolExecutor != null) {
            threadPoolExecutor.execute(task);
        } else {
            CompletableFuture.runAsync(task);
        }
    }

    /**
     * 渐进式摘要：
     * <ol>
     *   <li>首次：消息数 > triggerThreshold → 摘要前 keepRecent 条，水位线 = keepRecent</li>
     *   <li>后续：消息数 > 水位线 + keepRecent → 摘要"旧摘要 + 水位线后 keepRecent 条"，水位线 += keepRecent</li>
     * </ol>
     */
    private void doSummarize(String conversationId) {
        List<Message> all = chatMemoryRepository.findByConversationId(conversationId);
        if (all == null || all.size() <= triggerThreshold) return;

        AiChatMemorySummary existing = summaryDao.findByConversationId(conversationId);
        int watermark = existing != null ? existing.getSummaryMsgCount() : 0;
        int lastDetailCount = existing != null ? existing.getDetailMsgCount() : 0;

        // 首次：watermark=0，消化前 keepRecent 条
        // 后续：只有新增 keepRecent 条才触发下一轮
        if (watermark > 0 && all.size() < watermark + keepRecent) {
            log.debug("[STM.doSummarize] not enough new messages: total={}, watermark={}, need={}",
                    all.size(), watermark, watermark + keepRecent);
            return;
        }

        // 本轮消化的消息：messages[watermark .. watermark+keepRecent]
        int digestEnd = Math.min(watermark + keepRecent, all.size());
        if (digestEnd <= watermark) return;
        List<Message> toDigest = new ArrayList<>(all.subList(watermark, digestEnd));

        String previousSummary = existing != null ? existing.getSummary() : null;
        String newSummary = summarizer.summarizeWithPrevious(previousSummary, toDigest);
        if (newSummary == null || newSummary.isBlank()) {
            log.debug("[STM.doSummarize] summarizer returned empty for conversation={}, skip", conversationId);
            return;
        }

        int newVersion = existing == null ? 1 : existing.getVersion() + 1;
        AiChatMemorySummary po = AiChatMemorySummary.builder()
                .conversationId(conversationId)
                .userId(extractUserId(conversationId))
                .summary(newSummary)
                .lastMessageId(null)
                .version(newVersion)
                .summaryMsgCount(digestEnd)
                .detailMsgCount(all.size())
                .build();
        summaryDao.upsert(po);

        log.info("[STM.doSummarize] conversation={}: digested msgs {}-{}, watermark {}→{}, total={}, version={}",
                conversationId, watermark + 1, digestEnd, watermark, digestEnd, all.size(), newVersion);
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