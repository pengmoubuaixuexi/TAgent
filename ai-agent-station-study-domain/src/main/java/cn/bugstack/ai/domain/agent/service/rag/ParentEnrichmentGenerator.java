package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入库增强（合并调用版）：一个 parent 一次模型调用，同时产出小标题和全部 child 的检索上下文。
 * <p>
 * 取代原来"每个 parent 调一次生成标题 + 每个 child 再调一次生成前缀"的串行做法：调用次数从
 * parent 数 + child 数降到 parent 数，且模型能看到整个 parent，上下文可以消解指代。
 * <ul>
 *   <li>{@code agent.rag.enrichment.mode=llm}（默认）：调用小模型</li>
 *   <li>{@code agent.rag.enrichment.mode=rule}：不调模型，用文件名 + Markdown 标题生成</li>
 * </ul>
 * 标题和上下文仍分别受 {@code agent.rag.parent-title.enabled} / {@code agent.rag.contextual-retrieval.enabled} 控制。
 */
@Slf4j
@Service
public class ParentEnrichmentGenerator implements IParentEnrichmentGenerator {

    private static final String PROMPT = """
            你在为知识库检索准备索引。下面是文档《%s》中连续的一段内容，已按顺序切成 %d 个片段。
            请完成两件事：
            1. title：为这一整段内容写一个 5-15 字的小标题，不带标点、引号或书名号。
            2. contexts：为每个片段写 1-2 句简短的上下文，说明它在这段内容里讲什么，并写明"它 / 该方法 / 上述实验"等指代的具体对象，使片段被单独检索到时也能看懂。不要复述片段原文。
            使用与原文相同的语言。只输出一个 JSON 对象，不要解释，不要 Markdown 代码块，格式：
            {"title":"…","contexts":{"1":"…","2":"…"}}

            %s""";
    private static final String REPAIR = """

            上一次的输出不是合法 JSON。请严格只输出一个 JSON 对象，contexts 的键为片段序号 1 到 %d。""";
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+)$");
    private static final Pattern HTTP_STATUS = Pattern.compile("^\\s*(\\d{3})\\b");
    private static final int MAX_CONTEXT_CHARS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${agent.rag.enrichment.mode:llm}")
    private String mode = "llm";

    @Value("${agent.rag.enrichment.client-id:${agent.rag.contextual-retrieval.client-id:}}")
    private String clientId;

    @Value("${agent.rag.parent-title.enabled:false}")
    private boolean titleEnabled;

    @Value("${agent.rag.contextual-retrieval.enabled:false}")
    private boolean contextEnabled;

    @Value("${agent.rag.enrichment.max-chunk-chars:${agent.rag.contextual-retrieval.max-chunk-chars:2000}}")
    private int maxChunkChars = 2000;

    @Value("${agent.rag.parent-title.max-title-chars:30}")
    private int maxTitleChars = 30;

    private final ApplicationContext applicationContext;

    public ParentEnrichmentGenerator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public String strategy() {
        if (!titleEnabled && !contextEnabled) return "none";
        return (rule() ? "rule-v1" : "llm-combined-v1") + "|title=" + titleEnabled + "|context=" + contextEnabled;
    }

    @Override
    public boolean titleRequired() { return titleEnabled && !rule(); }

    @Override
    public Enrichment enrich(String documentTitle, List<Document> children) {
        if (children == null || children.isEmpty()) return Enrichment.none(0);
        if (!titleEnabled && !contextEnabled) return Enrichment.none(children.size());
        String document = documentTitle == null || documentTitle.isBlank() ? "未命名文档" : documentTitle;
        Enrichment result = rule() ? ruleBased(document, children) : modelBased(document, children);
        return new Enrichment(titleEnabled ? result.title() : null,
                contextEnabled ? result.contexts() : Enrichment.none(children.size()).contexts(), result.degraded());
    }

    private boolean rule() { return "rule".equalsIgnoreCase(mode == null ? "" : mode.trim()); }

    private Enrichment modelBased(String document, List<Document> children) {
        ChatClient client = chatClient();
        if (client == null) throw new UnavailableException("增强模型未配置（" + clientId + "）", null);
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < children.size(); i++) {
            String text = children.get(i).getText() == null ? "" : children.get(i).getText();
            if (text.length() > maxChunkChars) text = text.substring(0, maxChunkChars);
            blocks.append("[片段").append(i + 1).append("]\n").append(text).append("\n\n");
        }
        String prompt = String.format(PROMPT, document, children.size(), blocks.toString().trim());
        // 解析失败或调用异常时只再试一次；仍失败就降级，不拖慢整份文件。
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String raw = client.prompt(new Prompt(attempt == 0 ? prompt
                        : prompt + String.format(REPAIR, children.size()))).call().content();
                Enrichment parsed = parse(raw, children.size());
                if (parsed != null) return parsed;
                log.debug("parent enrichment returned invalid JSON, attempt={}", attempt + 1);
            } catch (Exception e) {
                StreamingActivityTracker.rethrowCancellation(e);
                Integer status = clientErrorStatus(e);
                // 4xx（429 除外）说明模型名、密钥或权限配置有问题，再试或换下一个 parent 都不会成功。
                if (status != null) throw new UnavailableException("增强模型不可用（HTTP " + status + "）", e);
                log.debug("parent enrichment failed attempt={} err={}", attempt + 1, e.getMessage());
            }
        }
        log.warn("parent enrichment degraded: document='{}' children={}", document, children.size());
        return Enrichment.failed(children.size());
    }

    /** 非 429 的 4xx 返回状态码；超时、5xx、限流等可能自行恢复的错误返回 null。 */
    private static Integer clientErrorStatus(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof NonTransientAiException) {
                Matcher status = HTTP_STATUS.matcher(String.valueOf(cause.getMessage()));
                if (status.find()) {
                    int code = Integer.parseInt(status.group(1));
                    return code >= 400 && code < 500 && code != 429 ? code : null;
                }
            }
        }
        return null;
    }

    private ChatClient chatClient() {
        if (clientId == null || clientId.isBlank()) {
            log.warn("agent.rag.enrichment.client-id not configured, skip enrichment");
            return null;
        }
        try {
            return applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId), ChatClient.class);
        } catch (Exception e) {
            log.warn("enrichment ChatClient bean not found for clientId={}, skip enrichment", clientId);
            return null;
        }
    }

    /** 宽松解析：容忍代码块包裹和前后杂字；contexts 可以是对象（"1":…）或数组。完全无法解析返回 null。 */
    public Enrichment parse(String raw, int size) {
        if (raw == null) return null;
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonNode root;
        try {
            root = JSON.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        String title = cleanTitle(root.path("title").isTextual() ? root.path("title").asText() : null);
        JsonNode node = root.path("contexts");
        List<String> contexts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            JsonNode value = node.isArray() ? node.path(i) : node.path(String.valueOf(i + 1));
            contexts.add(cleanContext(value.isTextual() ? value.asText() : null));
        }
        if (title == null && contexts.stream().allMatch(java.util.Objects::isNull)) return null;
        return new Enrichment(title, contexts);
    }

    private Enrichment ruleBased(String document, List<Document> children) {
        String title = null;
        for (Document child : children) {
            Matcher heading = HEADING.matcher(child.getText() == null ? "" : child.getText());
            if (heading.find()) { title = cleanTitle(heading.group(1)); break; }
        }
        String context = "文档：" + document + (title == null ? "" : "；章节：" + title);
        List<String> contexts = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) contexts.add(context);
        return new Enrichment(title, contexts);
    }

    private static String cleanContext(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_CONTEXT_CHARS ? s.substring(0, MAX_CONTEXT_CHARS) : s;
    }

    /** 去掉"小标题："等前缀、包裹引号和尾部标点，截断到 max-title-chars。 */
    public String cleanTitle(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.replaceFirst("^(小标题|标题|Title)\\s*[：:]\\s*", "");
        s = s.replaceAll("^[\"'《\\[\\(]+", "").replaceAll("[\"'》\\]\\)]+$", "");
        s = s.trim();
        if (s.isEmpty()) return null;
        s = s.replaceAll("[，,。.！!？?；;]+$", "");
        if (s.length() > maxTitleChars) s = s.substring(0, maxTitleChars);
        return s.trim().isEmpty() ? null : s.trim();
    }
}
