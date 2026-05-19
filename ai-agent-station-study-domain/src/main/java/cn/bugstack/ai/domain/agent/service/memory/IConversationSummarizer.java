package cn.bugstack.ai.domain.agent.service.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 对话摘要器（P0.2.2）。
 * <p>
 * 给一段历史消息生成一句话摘要，{@link SummarizingChatMemory} 在窗口溢出时调它。
 * <p>
 * 实现策略：
 * <ul>
 *   <li>{@link NoopConversationSummarizer}（默认）：什么都不做，返回 null —— 让 SummarizingChatMemory
 *       退化为纯滑窗，等价于 MessageWindowChatMemory</li>
 *   <li>未来可加 LLM 版本：复用 {@code agent.intent-router.client-id} 的小模型生成摘要</li>
 * </ul>
 * <p>
 * 接口故意保持轻量（List&lt;Message&gt; → String），不强求异步——异步在 SummarizingChatMemory 里管。
 */
public interface IConversationSummarizer {

    /**
     * @return 摘要文本；返回 null 或空串表示放弃本次摘要（消息保留在内存里继续滚）
     */
    String summarize(List<Message> messagesToSummarize);

    /**
     * 渐进式摘要：把已有摘要 + 新消息合并为一份更详细的摘要。
     *
     * @param previousSummary 已有摘要（首次为 null）
     * @param newMessages     本轮新消化的消息
     * @return 合并后的摘要文本；返回 null 或空串表示放弃
     */
    default String summarizeWithPrevious(String previousSummary, List<Message> newMessages) {
        return summarize(newMessages);
    }
}
