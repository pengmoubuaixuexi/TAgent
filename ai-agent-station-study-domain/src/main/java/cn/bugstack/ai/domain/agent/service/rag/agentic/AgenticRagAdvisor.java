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
            Determine if the user's question requires searching a knowledge base (RAG) to answer accurately.
            Reply with ONLY "YES" or "NO".

            Need RAG (YES):
            - Questions about specific facts, data, documents, or technical details
            - Questions that reference uploaded files or documents
            - Questions requiring current or domain-specific information

            No RAG needed (NO):
            - Simple greetings or chit-chat
            - General knowledge questions the model can answer confidently
            - Questions about the conversation itself
            - Pure creative writing or brainstorming

            User question: %s
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
            if (resp != null) {
                return resp.trim().toUpperCase().startsWith("YES");
            }
        } catch (Exception e) {
            log.debug("[AgenticRAG] decision call failed, defaulting to YES: {}", e.getMessage());
        }
        return true; // 失败默认检索（安全侧）
    }
}
