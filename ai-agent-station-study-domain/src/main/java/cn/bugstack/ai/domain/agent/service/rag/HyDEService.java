package cn.bugstack.ai.domain.agent.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * P2.3 12.1 HyDE（Hypothetical Document Embeddings）。
 * <p>
 * 用户查询 → LLM 生成"假想完美答案" → 用该答案做检索 query。
 * 假想答案比用户口语 query 语义更丰富，召回率更高。
 */
@Slf4j
public class HyDEService {

    private static final String HYDE_PROMPT = """
            You are a helpful assistant. Write a brief passage (2-4 sentences) that answers
            the following question. Do NOT say you're writing a hypothetical answer.
            Just write the passage as if it were a real answer.

            Question: %s

            Passage:""";

    private final ChatClient chatClient;

    public HyDEService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成假想答案用作检索 query。
     * @param userQuery 用户原始问题
     * @return 假想答案文本；失败返回 null，调用方用 original query fallback
     */
    public String generateHypotheticalDocument(String userQuery) {
        if (chatClient == null || userQuery == null || userQuery.isBlank()) return null;
        try {
            String prompt = String.format(HYDE_PROMPT, userQuery);
            String result = chatClient.prompt(new Prompt(prompt)).call().content();
            if (result != null && !result.isBlank()) {
                log.debug("HyDE generated {} chars for query='{}...'", result.length(),
                        userQuery.substring(0, Math.min(40, userQuery.length())));
                return result.trim();
            }
        } catch (Exception e) {
            log.debug("HyDE generation failed: {}", e.getMessage());
        }
        return null;
    }
}
