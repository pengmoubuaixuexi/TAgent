package cn.bugstack.ai.domain.agent.service.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 默认的 No-op 摘要器：返回 null，等价于不摘要（SummarizingChatMemory 退化为纯滑窗）。
 * <p>
 * 用 {@code @Bean + @ConditionalOnMissingBean} 提供：业务方接入 LLM 摘要器后会覆盖这个默认实现。
 */
@Configuration
public class NoopConversationSummarizer {

    @Bean
    @ConditionalOnMissingBean(IConversationSummarizer.class)
    public IConversationSummarizer noopSummarizer() {
        return new IConversationSummarizer() {
            @Override
            public String summarize(List<Message> messagesToSummarize) {
                return null;
            }
        };
    }
}
