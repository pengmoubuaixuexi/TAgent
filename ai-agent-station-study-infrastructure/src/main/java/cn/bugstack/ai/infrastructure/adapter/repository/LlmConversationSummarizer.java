package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.memory.IConversationSummarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM-backed rolling summary for persisted final-turn chat memory.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.chat-memory.summarize-enabled", havingValue = "true")
public class LlmConversationSummarizer implements IConversationSummarizer {

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public String summarize(List<Message> messagesToSummarize) {
        return summarizeWithPrevious(null, messagesToSummarize);
    }

    @Override
    public String summarizeWithPrevious(String previousSummary, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) return null;

        ChatClient client;
        try {
            client = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName("router-small"), ChatClient.class);
        } catch (Exception e) {
            log.warn("[ChatMemorySummary] router-small client not available: {}", e.getMessage());
            return null;
        }

        String prompt = buildPrompt(previousSummary, newMessages);
        try {
            String summary = client.prompt().user(prompt).call().content();
            if (summary == null || summary.isBlank()) return null;
            summary = summary.replaceAll("(?s)<think>.*?</think>", "").trim();
            if (summary.isBlank()) return null;
            return summary.length() > 1200 ? summary.substring(0, 1200) : summary;
        } catch (Exception e) {
            log.warn("[ChatMemorySummary] summarize failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildPrompt(String previousSummary, List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话短期记忆摘要器。请把已持久化的用户输入和最终助手输出压缩成中文摘要，用于后续对话续接。\n");
        sb.append("只总结用户真实输入和助手最终回答中的事实、偏好、目标、约束和已给出的结论。\n");
        sb.append("不要加入执行步骤、工具过程、内部推理或不存在的信息。\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【已有摘要】\n").append(previousSummary).append("\n\n");
            sb.append("【新增对话】\n");
        } else {
            sb.append("【待摘要对话】\n");
        }

        int shown = 0;
        for (Message message : messages) {
            if (message == null || message.getText() == null || message.getText().isBlank()) continue;
            if (shown >= 24) break;
            String role = message.getMessageType() == null ? "UNKNOWN" : message.getMessageType().name();
            String text = message.getText().trim();
            if (text.length() > 600) {
                text = text.substring(0, 600) + "...";
            }
            sb.append("[").append(role).append("] ").append(text).append("\n");
            shown++;
        }

        sb.append("\n输出要求：\n");
        sb.append("1. 只输出摘要正文，不要标题，不要解释。\n");
        sb.append("2. 保留已有摘要中的关键信息，并合并新增对话。\n");
        sb.append("3. 控制在 300-800 字之间。\n");
        return sb.toString();
    }
}
