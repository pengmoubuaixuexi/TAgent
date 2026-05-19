package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * 基于小模型的 Query 改写器（P1.4）。
 * <p>
 * 复用 {@link LlmCallGateway} 走 Resilience4j 容错；client-id 取项目里已装配的轻量 ChatClient。
 * <p>
 * <b>容错策略</b>：rewrite 失败一律返回原 query（无声降级）。检索还能跑、只是召回略差，比抛异常好。
 * <p>
 * <b>触发条件</b>：
 * <ul>
 *   <li>{@code agent.query-rewriter.client-id} 没配置 → disabled</li>
 *   <li>query 长度过短（&lt; minLengthChars）→ skip（短 query 改写经常画蛇添足）</li>
 *   <li>query 长度过长（&gt; maxLengthChars）→ skip（已经够具体不需要改写）</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmQueryRewriter implements IQueryRewriter {

    private static final String REWRITE_PROMPT = """
            你是 RAG 检索 query 改写专家。把用户的口语化 / 含代词 / 含上下文依赖的 query 改写成
            一个独立完整的"陈述式检索 query"，让向量库和关键词检索都能更准地命中相关文档。

            规则：
            1. 只输出改写后的 query 文本本身，<b>不要</b>加引号、不要前缀（"改写："等）、不要解释。
            2. 保留原 query 的核心实体词；补全代词指代（"它/这个/上面那个" → 具体名词）。
            3. 把疑问句改为陈述句（"X 怎么用" → "X 的使用方法"）。
            4. 不要凭空加未提及的信息；不知道的指代就保留原样。
            5. 长度尽量控制在 50 字以内。

            原 query: %s
            """;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private LlmCallGateway llmCallGateway;

    @Value("${agent.query-rewriter.client-id:}")
    private String rewriterClientId;

    @Value("${agent.query-rewriter.min-length-chars:6}")
    private int minLengthChars;

    @Value("${agent.query-rewriter.max-length-chars:80}")
    private int maxLengthChars;

    @Override
    public String rewrite(String original) {
        if (original == null) return null;
        if (rewriterClientId == null || rewriterClientId.isBlank()) return original;

        String trimmed = original.trim();
        if (trimmed.isEmpty()) return original;
        if (trimmed.length() < minLengthChars || trimmed.length() > maxLengthChars) return original;

        ChatClient client;
        try {
            client = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName(rewriterClientId), ChatClient.class);
        } catch (Exception e) {
            log.warn("query-rewriter client bean missing: {}", rewriterClientId);
            return original;
        }

        String prompt = String.format(REWRITE_PROMPT, trimmed);
        try {
            ChatResponse resp = llmCallGateway.call(() -> client.prompt(prompt).call());
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) return original;
            String rewritten = resp.getResult().getOutput().getText();
            if (rewritten == null || rewritten.isBlank()) return original;
            // 去掉模型偶尔加的引号和前后空白
            rewritten = rewritten.trim().replaceAll("^[\"'《【\\[]+|[\"'》】\\]]+$", "");
            if (rewritten.isBlank() || rewritten.length() > maxLengthChars * 2) return original;
            log.debug("query rewritten: '{}' → '{}'", trimmed, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("query-rewriter llm call failed silently: {}", e.getMessage());
            return original;
        }
    }
}
