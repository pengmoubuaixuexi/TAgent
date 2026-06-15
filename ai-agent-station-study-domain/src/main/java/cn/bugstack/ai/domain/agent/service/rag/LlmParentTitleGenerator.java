package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import lombok.extern.slf4j.Slf4j;
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
 * 第 61 轮 Phase 2：LLM 驱动的 parent title 生成器（A 方案）。
 * <p>
 * 跟 {@link LlmContextualPrefixGenerator} 共用一套小模型 client，但 prompt 不同：
 * 这里生成"5-15 字精炼小标题"，落到 {@code ai_parent_document.title} 列；
 * contextual prefix 拼到 child chunk 头部送向量库/ES，两者职责不重叠。
 * <p>
 * 失败静默降级：单 parent LLM 调用失败/超时返回 null，不影响整批入库；前端 fallback 到 source / knowledge。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.rag.parent-title", name = "enabled", havingValue = "true")
public class LlmParentTitleGenerator implements IParentTitleGenerator {

    private static final String TITLE_PROMPT = """
            你是文档标题生成助手。给定文档标题和一段文本块，为这个文本块提炼一个 5-15 字的精炼小标题，
            让读者一眼能知道这段讲什么。不要带书名号、引号、句号、标点；不要解释；只输出标题文本本身。

            文档标题: %s
            文本块:
            %s
            小标题:""";

    @Value("${agent.rag.parent-title.client-id:}")
    private String clientId;

    @Value("${agent.rag.parent-title.max-chunk-chars:2000}")
    private int maxChunkChars;

    @Value("${agent.rag.parent-title.max-title-chars:30}")
    private int maxTitleChars;

    private final ApplicationContext applicationContext;

    public LlmParentTitleGenerator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public List<String> generate(String documentTitle, List<Document> parents) {
        if (parents == null || parents.isEmpty()) return Collections.emptyList();
        if (clientId == null || clientId.isBlank()) {
            log.debug("parent-title client-id not configured, skip all");
            return Collections.nCopies(parents.size(), null);
        }

        ChatClient chatClient;
        try {
            chatClient = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId), ChatClient.class);
        } catch (Exception e) {
            log.warn("parent-title ChatClient bean not found for clientId={}, skip all", clientId);
            return Collections.nCopies(parents.size(), null);
        }

        List<String> titles = new ArrayList<>(parents.size());
        int success = 0, fail = 0;
        for (int i = 0; i < parents.size(); i++) {
            Document parent = parents.get(i);
            String text = parent.getText();
            if (text == null || text.isBlank()) {
                titles.add(null);
                continue;
            }
            String snippet = text.length() > maxChunkChars ? text.substring(0, maxChunkChars) : text;
            String prompt = String.format(TITLE_PROMPT,
                    documentTitle != null ? documentTitle : "unknown", snippet);
            try {
                String raw = chatClient.prompt(new Prompt(prompt)).call().content();
                String cleaned = cleanTitle(raw);
                if (cleaned != null && !cleaned.isEmpty()) {
                    titles.add(cleaned);
                    success++;
                } else {
                    titles.add(null);
                    fail++;
                }
            } catch (Exception e) {
                log.debug("parent-title generation failed for parent {} err={}", i, e.getMessage());
                titles.add(null);
                fail++;
            }
        }

        log.info("parent-title batch done: total={} success={} fail={} clientId={}",
                parents.size(), success, fail, clientId);
        return titles;
    }

    /**
     * 清洗 LLM 输出 —— 去包装引号 / 截断到 maxTitleChars / 删尾部标点。
     * public 暴露给单测。
     */
    public String cleanTitle(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // 去前缀像 "小标题:" "Title:" 这种 LLM 偶尔会带的 label
        s = s.replaceFirst("^(小标题|标题|Title)\\s*[：:]\\s*", "");
        // 去包裹的引号 / 书名号
        s = s.replaceAll("^[\"'《\\[\\(]+", "").replaceAll("[\"'》\\]\\)]+$", "");
        s = s.trim();
        if (s.isEmpty()) return null;
        // 去尾部标点
        s = s.replaceAll("[，,。.！!？?；;]+$", "");
        // 截断
        if (s.length() > maxTitleChars) {
            s = s.substring(0, maxTitleChars);
        }
        return s.trim().isEmpty() ? null : s.trim();
    }
}
