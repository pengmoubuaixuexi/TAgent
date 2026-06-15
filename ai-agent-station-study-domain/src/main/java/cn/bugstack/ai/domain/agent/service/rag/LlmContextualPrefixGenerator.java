package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM 驱动的上下文前缀生成器（P1.4 7.3 Contextual Retrieval）。
 * <p>
 * 为每个 chunk 发一次小模型调用，生成 1-2 句上下文描述拼到正文前。
 * 模型选择：通过 {@code agent.rag.contextual-retrieval.client-id} 配置一个 SMALL tier ChatClient。
 * <p>
 * 失败静默降级：单 chunk LLM 调用失败/超时返回 null，不影响整批入库。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.rag.contextual-retrieval", name = "enabled", havingValue = "true")
public class LlmContextualPrefixGenerator implements IContextualPrefixGenerator {

    private static final String PREFIX_PROMPT = """
            You are a document context writer. Given the document title and a text chunk,
            write 1-2 short sentences describing what this chunk is about within the document.
            Output ONLY the context text, no explanations, no labels, no markdown.

            Document title: %s
            Chunk text:
            %s
            Context:""";

    @Value("${agent.rag.contextual-retrieval.client-id:}")
    private String clientId;

    @Value("${agent.rag.contextual-retrieval.max-chunk-chars:2000}")
    private int maxChunkChars;

    private final ApplicationContext applicationContext;

    public LlmContextualPrefixGenerator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public List<String> generate(String documentTitle, List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) return Collections.emptyList();
        if (clientId == null || clientId.isBlank()) {
            log.debug("contextual-retrieval client-id not configured, skip all");
            return Collections.nCopies(chunks.size(), null);
        }

        ChatClient chatClient;
        try {
            chatClient = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId), ChatClient.class);
        } catch (Exception e) {
            log.warn("contextual-retrieval ChatClient bean not found for clientId={}, skip all", clientId);
            return Collections.nCopies(chunks.size(), null);
        }

        List<String> prefixes = new ArrayList<>(chunks.size());
        int success = 0, fail = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                prefixes.add(null);
                continue;
            }
            // 截断过长文本，控制 prompt token
            String snippet = text.length() > maxChunkChars ? text.substring(0, maxChunkChars) : text;
            String prompt = String.format(PREFIX_PROMPT, documentTitle != null ? documentTitle : "unknown", snippet);
            try {
                String prefix = chatClient.prompt(new Prompt(prompt)).call().content();
                if (prefix != null && !prefix.isBlank()) {
                    prefixes.add(prefix.trim());
                    success++;
                } else {
                    prefixes.add(null);
                    fail++;
                }
            } catch (Exception e) {
                log.debug("contextual prefix generation failed for chunk {} err={}", i, e.getMessage());
                prefixes.add(null);
                fail++;
            }
        }

        log.info("contextual prefix batch done: total={} success={} fail={} clientId={}",
                chunks.size(), success, fail, clientId);

        // 给每个成功的 chunk 更新 text
        for (int i = 0; i < chunks.size(); i++) {
            String prefix = prefixes.get(i);
            if (prefix != null) {
                Document chunk = chunks.get(i);
                String newText = "【上下文】" + prefix + "\n\n【正文】" + chunk.getText();
                chunks.set(i, chunk.mutate().text(newText).build());
            }
        }

        return prefixes;
    }
}
