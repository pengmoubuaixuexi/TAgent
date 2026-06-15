package cn.bugstack.ai.domain.agent.service.rag.agentic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

/**
 * P2.3 12.3 Agentic RAG：LLM 自主决定是否需要检索。
 * <p>
 * 用轻量 LLM（router-small）判断当前 query 是否需要 RAG，
 * 不需要时在 request context 标记 skip_rag=true，RAG advisor 可据此跳过检索。
 * 与 {@code HeuristicRagRouter}（白名单+长度阈值）互补：本 advisor 更精准但有调用成本。
 * <p>
 * order=90（在 {@code RagAnswerAdvisor} 之前运行，决定是否执行检索）。
 */
@Slf4j
public class AgenticRagAdvisor implements BaseAdvisor {

    private static final String RAG_DECISION_PROMPT = """
            判断用户问题是否需要检索知识库（RAG）才能准确回答。
            请只回答 "YES" 或 "NO"，不要输出其它内容。

            需要检索（YES）：
            - 涉及具体事实、数据、上传文档、业务资料或技术细节
            - 明确引用了知识库、文件、文档、资料、手册、历史记录
            - 需要当前知识库或特定领域信息支持

            不需要检索（NO）：
            - 简单寒暄或闲聊
            - 模型可以可靠直接回答的通用常识
            - 关于当前对话本身的问题
            - 纯创作、头脑风暴或开放式想法发散

            用户问题：%s
            """;

    private final ChatClient routerClient;
    private final int order;

    public AgenticRagAdvisor(ChatClient routerClient) {
        // Claude 修复：order 从 90 改为 -10。
        // 原版 90 > RagAnswerAdvisor.order(0)，意味着 AgenticRag.before 在 RagAnswer 之后才跑，
        // 决策来得太晚——RagAnswer 已经把 PgVector + ES + Rerank 都跑完了，
        // 写 agentic_rag_skip=true 也影响不到当前请求。
        // 改成 -10 让它在 RagAnswer(0) 之前跑、PromptInjection(-50) 之后跑，决策才能真正生效。
        this(routerClient, -10);
    }

    public AgenticRagAdvisor(ChatClient routerClient, int order) {
        this.routerClient = routerClient;
        this.order = order;
    }

    @Override
    public String getName() {
        return "agentic_rag";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /** Context key 写入后 RagAnswerAdvisor.before() 读出来决定是否真的检索 */
    public static final String CTX_SKIP_RAG = "agentic_rag_skip";

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 从 prompt 中提取用户消息文本
        String userText = null;
        List<Message> messages = request.prompt().getInstructions();
        if (messages != null) {
            for (Message msg : messages) {
                if (msg.getMessageType() == MessageType.USER) {
                    userText = msg.getText();
                    break;
                }
            }
        }
        if (userText == null || userText.isBlank()) {
            return request;
        }

        boolean needRag = evaluateRagNeed(userText);
        if (!needRag) {
            log.info("[AgenticRAG] LLM decided no RAG needed for query, skipping retrieval");
            // 把决策写入 context，下游 RagAnswerAdvisor 读到 true 就跳过双库检索 + rerank
            java.util.Map<String, Object> mutable = new java.util.HashMap<>(request.context());
            mutable.put(CTX_SKIP_RAG, Boolean.TRUE);
            return request.mutate().context(mutable).build();
        }
        log.info("[AgenticRAG] LLM decided RAG needed, proceeding with retrieval");
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private boolean evaluateRagNeed(String query) {
        try {
            String prompt = String.format(RAG_DECISION_PROMPT, query);
            String resp = routerClient.prompt().user(prompt).call().content();
            Boolean decision = parseRagDecision(resp);
            if (decision != null) {
                return decision;
            }
            log.debug("[AgenticRAG] 判定输出无法识别，默认检索（安全侧）: {}", resp);
        } catch (Exception e) {
            log.debug("[AgenticRAG] decision call failed, defaulting to YES: {}", e.getMessage());
        }
        return true; // 失败 / 无法识别默认检索（安全侧）
    }

    /**
     * 解析"是否需要检索"的判定输出，兼容中英文：
     * <ul>
     *   <li>需要检索 → {@code YES} / {@code 是} / {@code 需要(检索)}</li>
     *   <li>跳过检索 → {@code NO} / {@code 否} / {@code 不需要} / {@code 无需}</li>
     * </ul>
     * 两者都识别不到 → 返回 {@code null}，由调用方走"安全侧默认检索"。
     * <p>关键：中文必须<b>先判否定再认肯定</b>——"不需要"里含"需要"，顺序反了会判反。
     */
    private Boolean parseRagDecision(String resp) {
        if (resp == null) return null;
        String s = resp.trim();
        if (s.isEmpty()) return null;
        // 英文优先：输出契约要求只回 YES/NO
        String upper = s.toUpperCase();
        if (upper.startsWith("YES")) return Boolean.TRUE;
        if (upper.startsWith("NO")) return Boolean.FALSE;
        // 中文兜底：先排否定（"不需要"包含"需要"，必须先于肯定判断）
        if (s.startsWith("否") || s.contains("不需要") || s.contains("无需")) return Boolean.FALSE;
        if (s.startsWith("是") || s.contains("需要")) return Boolean.TRUE;
        return null;
    }
}
