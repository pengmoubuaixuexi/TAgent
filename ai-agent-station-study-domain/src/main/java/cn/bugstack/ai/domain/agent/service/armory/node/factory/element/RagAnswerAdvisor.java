package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.domain.agent.model.valobj.RagRouterDecision;
import cn.bugstack.ai.domain.agent.service.execute.common.RagEvidenceEmitter;
import cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.IRerankService;
import cn.bugstack.ai.domain.agent.service.router.IQueryRewriter;
import cn.bugstack.ai.domain.agent.service.router.IRagRouter;
import cn.bugstack.ai.domain.agent.service.rag.HyDEService;
import cn.bugstack.ai.domain.agent.service.rag.IParentDocumentService;
import cn.bugstack.ai.domain.agent.service.rag.IQueryDecomposer;
import cn.bugstack.ai.domain.agent.service.rag.fusion.IRagFusionService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
@Slf4j
public class RagAnswerAdvisor implements BaseAdvisor {

    private final VectorStore vectorStore;
    private final SearchRequest searchRequest;
    private final String userTextAdvise;
    private final HybridRetriever hybridRetriever;
    private final IRerankService rerankService;
    private final int rerankTopN;
    private final String knowledgeTag;
    /** P0.1.4 RAG Router；为 null 时永远 retrieve（保持兼容） */
    private final IRagRouter ragRouter;
    /** P1.4 Query Rewriter；为 null 时不改写 */
    private final IQueryRewriter queryRewriter;
    /** P2.3 12.1 HyDE；为 null 时不生成假想答案 */
    private final HyDEService hydeService;
    /** P2.3 12.7 RAG Fusion；为 null 时走原检索路径 */
    private final IRagFusionService ragFusionService;
    /** P2.3 12.2 Query Decomposition；为 null 时不拆子查询 */
    private final IQueryDecomposer queryDecomposer;
    /** P2.3 12.4 Parent Document Retriever；为 null 时不换 parent */
    private final IParentDocumentService parentDocumentService;

    /**
     * H1-A：RAG 证据片段 emitter（可选）。setter 注入避免改构造链。
     * null → 不 emit 任何 SSE 事件，advisor 行为完全不变。装配点（AiClientAdvisorNode）在 new 实例后调
     * {@link #setRagEvidenceEmitter} 注入。
     */
    private volatile RagEvidenceEmitter ragEvidenceEmitter;

    /**
     * 第 61 轮新增：sessionId 维度全局引用计数器。null → 每次从 [1] 编号（旧行为）。
     */
    private volatile SessionRefCounter sessionRefCounter;

    public void setRagEvidenceEmitter(RagEvidenceEmitter emitter) {
        this.ragEvidenceEmitter = emitter;
    }

    public void setSessionRefCounter(SessionRefCounter sessionRefCounter) {
        this.sessionRefCounter = sessionRefCounter;
    }

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest) {
        this(vectorStore, searchRequest, null, null, 0, null, null, null, null, null, null);
    }

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag) {
        this(vectorStore, searchRequest, hybridRetriever, rerankService, rerankTopN, knowledgeTag, null, null, null, null, null, null);
    }

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag, IRagRouter ragRouter) {
        this(vectorStore, searchRequest, hybridRetriever, rerankService, rerankTopN, knowledgeTag, ragRouter, null, null, null, null, null);
    }

    /**
     * 混合检索模式 + RAG Router + Query Rewriter（P1.4）。
     */
    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag,
                            IRagRouter ragRouter, IQueryRewriter queryRewriter) {
        this(vectorStore, searchRequest, hybridRetriever, rerankService, rerankTopN, knowledgeTag, ragRouter, queryRewriter, null, null, null, null);
    }

    /**
     * 完整版 + HyDE（P2.3 12.1）。
     */
    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag,
                            IRagRouter ragRouter, IQueryRewriter queryRewriter,
                            HyDEService hydeService) {
        this(vectorStore, searchRequest, hybridRetriever, rerankService, rerankTopN, knowledgeTag, ragRouter, queryRewriter, hydeService, null, null, null);
    }

    /**
     * 完整版 + HyDE + RAG Fusion（P2.3 12.7）。
     */
    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag,
                            IRagRouter ragRouter, IQueryRewriter queryRewriter,
                            HyDEService hydeService, IRagFusionService ragFusionService) {
        this(vectorStore, searchRequest, hybridRetriever, rerankService, rerankTopN, knowledgeTag, ragRouter, queryRewriter, hydeService, ragFusionService, null, null);
    }

    /**
     * 完整版 + HyDE + RAG Fusion + Query Decomposition（P2.3 12.2/12.7）。
     */
    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag,
                            IRagRouter ragRouter, IQueryRewriter queryRewriter,
                            HyDEService hydeService, IRagFusionService ragFusionService,
                            IQueryDecomposer queryDecomposer) {
        this(vectorStore, searchRequest, hybridRetriever, rerankService, rerankTopN, knowledgeTag, ragRouter, queryRewriter, hydeService, ragFusionService, queryDecomposer, null);
    }

    /**
     * 完整版 + Parent Document Retriever（P2.3 12.4）。
     */
    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            HybridRetriever hybridRetriever, IRerankService rerankService,
                            int rerankTopN, String knowledgeTag,
                            IRagRouter ragRouter, IQueryRewriter queryRewriter,
                            HyDEService hydeService, IRagFusionService ragFusionService,
                            IQueryDecomposer queryDecomposer,
                            IParentDocumentService parentDocumentService) {
        this.vectorStore = vectorStore;
        this.searchRequest = searchRequest;
        this.hybridRetriever = hybridRetriever;
        this.rerankService = rerankService;
        this.rerankTopN = rerankTopN > 0 ? rerankTopN : (searchRequest != null ? searchRequest.getTopK() : 4);
        this.knowledgeTag = knowledgeTag;
        this.ragRouter = ragRouter;
        this.queryRewriter = queryRewriter;
        this.hydeService = hydeService;
        this.ragFusionService = ragFusionService;
        this.queryDecomposer = queryDecomposer;
        this.parentDocumentService = parentDocumentService;
        this.userTextAdvise = "\n下面是本次检索到的知识库上下文，位于分隔线之间：\n\n---------------------\n{question_answer_context}\n---------------------\n\n请优先依据这些上下文和已有对话历史回答用户问题，不要编造资料中没有的信息。\n如果资料不足以回答，请明确说明“当前知识库资料不足”，并列出你能确认的部分和缺失的信息。\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();

        // V041：flow/auto 节点会把工具目录 + 分析模板拼进 user message，污染 RAG 路由与检索。
        // 若节点传入了干净的用户问题(ltm_retrieval_query)，就用【记忆背景 + 干净问题】做路由/检索，剥掉工具脚手架。
        // 记忆背景 = LTM/EPI advisor 注入块（它们以 "--------" 分隔，取最后一个分隔符之前的内容）。
        // 【关键修复】只有 retrievalQuery（路由/检索/rerank）用这个干净版；发给 LLM 的 UserMessage 必须用原始 userText。
        // 旧实现把 userText 本身改写成干净问题，导致有检索命中时（下方 new UserMessage(userText)）把 "--------" 之后的
        // 整段工程化 prompt（工具清单/分析模板等）一并丢弃 —— Step1 这类步骤的模型因此根本看不到工具列表，只剩干净问题+RAG 结果。
        String retrievalQuery = userText;
        Object cleanQObj = context.get(LongTermMemoryAdvisor.RETRIEVAL_QUERY_CONTEXT_KEY);
        if (cleanQObj != null && StringUtils.hasText(cleanQObj.toString())) {
            String cleanQuestion = cleanQObj.toString().trim();
            int lastSep = userText.lastIndexOf("--------");
            String memoryBackground = lastSep > 0 ? userText.substring(0, lastSep).trim() : "";
            retrievalQuery = memoryBackground.isEmpty()
                    ? cleanQuestion
                    : memoryBackground + "\n\n--------\n" + cleanQuestion;
        }

        // A 重构：去掉无条件 queryRewriter + 无条件 HyDE，统一交给 RAG Router 一次决策出查询策略。
        // 构建 filter expression：DB 配置的 knowledge tag + MDC 里的 userId（如有）
        // 用 Filter.Expression 树直接 AND，不走 toString → parse（Spring AI Filter.Expression 是 record，
        // 默认 toString 输出 "Expression[type=EQ, left=...]"，不是 FilterExpressionTextParser 能识别的 DSL）
        Filter.Expression filterExpr = this.doGetFilterExpression(context);
        String userId = org.slf4j.MDC.get("userId");
        if (userId != null && !userId.isBlank()) {
            Filter.Expression userIdExpr = new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key("user_id"),
                    new Filter.Value(userId));
            filterExpr = (filterExpr == null)
                    ? userIdExpr
                    : new Filter.Expression(Filter.ExpressionType.AND, filterExpr, userIdExpr);
        }

        List<Document> documents;
        // P2.3 12.3 Agentic RAG：上游 AgenticRagAdvisor.before() 写入了 skip 标志 → 直接跳过检索
        Object agenticSkip = context.get(cn.bugstack.ai.domain.agent.service.rag.agentic.AgenticRagAdvisor.CTX_SKIP_RAG);
        if (Boolean.TRUE.equals(agenticSkip)) {
            documents = List.of();
        } else {
            // A 三层模型：① 要不要检索 → ② 查询策略（router 一次决策 4 选 1，载荷一并产出）→ ③ 引擎自适应。
            // HYBRID（旧值/兜底）与未知策略统一按 SIMPLE 单 query 检索；引擎层（hybrid/纯向量）在下游 helper 决定。
            RagRouterDecision decision = (ragRouter != null)
                    ? ragRouter.decide(retrievalQuery)
                    : RagRouterDecision.retrieve();
            String path = decision.getPath();

            if (!decision.isShouldRetrieve()) {
                documents = List.of();
                log.info("rag-router skip: reason={}", decision.getReason());
            }
            // ② DECOMPOSE：router 已给子查询 → 并行检索 + 合并 + rerank
            else if (RagRouterDecision.PATH_DECOMPOSE.equals(path) && !decision.getSubQueries().isEmpty()) {
                SearchRequest baseSr = SearchRequest.from(this.searchRequest).filterExpression(filterExpr).build();
                documents = retrieveForSubQueries(baseSr, decision.getSubQueries(), retrievalQuery);
            }
            // ② FUSION：router 已给变体 → 并行检索 + RRF + rerank
            else if (RagRouterDecision.PATH_FUSION.equals(path) && !decision.getVariants().isEmpty()
                    && ragFusionService != null && ragFusionService.isAvailable()) {
                SearchRequest baseSr = SearchRequest.from(this.searchRequest).query(retrievalQuery).filterExpression(filterExpr).build();
                List<Document> fused = ragFusionService.fusedRetrieveWithVariants(
                        retrievalQuery, decision.getVariants(), baseSr, rerankTopN);
                documents = (rerankService != null && !fused.isEmpty())
                        ? rerankService.rerank(retrievalQuery, fused, rerankTopN)
                        : fused;
            }
            // ② SIMPLE / HYDE / HYBRID(兜底)：单 query 检索，检索 query 取自 router 载荷
            else {
                String searchQuery;
                if (RagRouterDecision.PATH_HYDE.equals(path) && StringUtils.hasText(decision.getHypotheticalDocument())) {
                    searchQuery = decision.getHypotheticalDocument();
                } else if (StringUtils.hasText(decision.getRewrittenQuery())) {
                    searchQuery = decision.getRewrittenQuery();
                } else {
                    searchQuery = retrievalQuery;
                }
                documents = retrieveSingleQuery(searchQuery, filterExpr, retrievalQuery);
            }
        }
        context.put("qa_retrieved_documents", documents);

        // P2.3 12.4 Parent Document Retriever：用检索到的小块 child 换出大块 parent 喂 LLM
        // 第 61 轮修：原 resolveParents 返回 List<String> + new Document(text) 会丢光 source/knowledge metadata，
        // 导致前端引用依据卡片走 doc.getId() fallback 显示 UUID。改用 resolveParentDocuments 保留 metadata。
        if (parentDocumentService != null && !documents.isEmpty()) {
            List<Document> parentDocs = parentDocumentService.resolveParentDocuments(documents);
            if (!parentDocs.isEmpty()) {
                documents = parentDocs;
                context.put("qa_retrieved_documents", documents);
            }
        }

        // P2.3 12.6 Citation：给每个文档编号，模型回答时可引用 [N][N+1]
        // 第 61 轮：用 SessionRefCounter 做 sessionId-scoped 全局编号 —— Step2 多 client 各自触发 RAG
        // 时，编号跨 client 累加（client A 用 [1][2]、client B 用 [3][4]）；前端 append 渲染对应
        // 一张大卡片，模型答案中的 [N] 跟前端卡片中第 N 条 evidence 严格一一对应
        String sessionId = resolveSessionIdForEvidence(chatClientRequest.context());
        int startRef = (sessionRefCounter != null)
                ? sessionRefCounter.advance(sessionId, documents.size())
                : 1;
        StringBuilder docContextBuilder = new StringBuilder();
        Map<Integer, String> citationMap = new HashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            int ref = startRef + i;
            docContextBuilder.append("[").append(ref).append("] ").append(documents.get(i).getText()).append("\n\n");
            citationMap.put(ref, documents.get(i).getId() != null ? documents.get(i).getId() : "doc-" + ref);
        }
        String documentContext = docContextBuilder.toString();
        // 追加引用指令 —— 编号必须跟 documentContext 第一项对齐（不一定是 [1] 起）
        if (!documents.isEmpty()) {
            int endRef = startRef + documents.size() - 1;
            String citeHint = (documents.size() == 1)
                    ? "回答时请在使用到该资料的位置标注引用编号 [" + startRef + "]。"
                    : "回答时请在使用到对应资料的位置标注引用编号，引用范围为 [" + startRef + "] 到 [" + endRef + "]。";
            documentContext = citeHint + "\n\n---\n" + documentContext;
        }
        context.put("qa_citation_map", citationMap);

        // H1-A: emit rag_evidence SSE event 给前端展示检索依据。advisor 失败不能影响主回答
        // —— emitter 内部已 try/catch 兜底；此处再外层 try 保险，确保任何异常都不传播
        // 第 61 轮：传 startRef，前端基于这个 ref 渲染 [startRef]..[startRef+n-1]，跟模型答案一致
        try {
            if (ragEvidenceEmitter != null && !documents.isEmpty()) {
                List<Map<String, Object>> evidenceSnippets = ragEvidenceEmitter.buildEvidenceSnippets(documents, startRef);
                if (!evidenceSnippets.isEmpty()) {
                    context.put("qa_evidence_snippets", evidenceSnippets);
                }
                ragEvidenceEmitter.emitEvidence(sessionId, documents, startRef);
            }
        } catch (Exception emitEx) {
            log.debug("[RagAnswerAdvisor] evidence emit skipped: {}", emitEx.toString());
        }

        Map<String, Object> advisedUserParams = new HashMap(chatClientRequest.context());
        advisedUserParams.put("question_answer_context", documentContext);

        // 无文档 → 不做任何 prompt 改写，让模型自由作答（含记忆）
        if (documents.isEmpty()) {
            return ChatClientRequest.builder()
                    .prompt(chatClientRequest.prompt())
                    .context(advisedUserParams)
                    .build();
        }

        // 把 RAG context 通过 SystemMessage 注入，保持 UserMessage 原话不变
        // 修复：之前把 context 拼进 UserMessage + 添加假 AssistantMessage，导致 ChatMemoryAdvisor 写入脏数据
        List<Message> messages = new ArrayList<>();
        Prompt originalPrompt = chatClientRequest.prompt();
        if (originalPrompt != null) {
            SystemMessage sysMsg = originalPrompt.getSystemMessage();
            if (sysMsg != null) messages.add(sysMsg);
        }
        // RAG 上下文作为独立 SystemMessage
        String ragContextMsg = "下面是本次检索到的知识库上下文，位于分隔线之间：\n\n"
                + "---------------------\n" + documentContext + "---------------------\n\n"
                + "请优先依据这些上下文和已有对话历史回答用户问题，不要编造资料中没有的信息。"
                + "如果资料不足以回答，请明确说明“当前知识库资料不足”，并列出你能确认的部分和缺失的信息。\n";
        messages.add(new SystemMessage(ragContextMsg));
        // 保持原始 UserMessage 不变
        messages.add(new UserMessage(userText));

        return ChatClientRequest.builder()
                // 透传 options，否则 per-request 动态工具回调会丢（见 LongTermMemoryAdvisor 同样修复）。
                .prompt(Prompt.builder().messages(messages).chatOptions(originalPrompt != null ? originalPrompt.getOptions() : null).build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponseBuilder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        ChatResponse chatResponse = chatResponseBuilder.build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    /**
     * H1-A：从 advisor context / MDC 拿 sessionId 用于 SSE emit。
     * 优先级：MDC sessionId → context["chat_memory_conversation_id"] 的末段 → null。
     * chat_memory_conversation_id 可能是 tenant:user:session 的复合键，SSE registry 使用原始 sessionId。
     */
    private String resolveSessionIdForEvidence(Map<String, Object> context) {
        String mdcSid = org.slf4j.MDC.get("sessionId");
        if (mdcSid != null && !mdcSid.isBlank()) return mdcSid;
        if (context != null) {
            Object sid = context.get("chat_memory_conversation_id");
            String sessionId = extractSessionIdFromConversationId(sid == null ? null : String.valueOf(sid));
            if (sessionId != null && !sessionId.isBlank()) return sessionId;
        }
        return null;
    }

    private String extractSessionIdFromConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        String trimmed = conversationId.trim();
        int idx = trimmed.lastIndexOf(':');
        return idx >= 0 && idx + 1 < trimmed.length() ? trimmed.substring(idx + 1) : trimmed;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {
        return context.containsKey("qa_filter_expression") && StringUtils.hasText(context.get("qa_filter_expression").toString()) ? (new FilterExpressionTextParser()).parse(context.get("qa_filter_expression").toString()) : this.searchRequest.getFilterExpression();
    }

    /**
     * A 重构：单 query 检索的引擎层（③）——hybridRetriever 在则走 hybrid(BM25+向量+RRF)，否则纯向量；
     * 末尾统一 rerank。SIMPLE / HYDE / HYBRID(兜底) 三个查询策略共用。
     */
    private List<Document> retrieveSingleQuery(String query, Filter.Expression filterExpr, String userText) {
        SearchRequest sr = SearchRequest.from(this.searchRequest).query(query).filterExpression(filterExpr).build();
        if (hybridRetriever != null) {
            int candidatePool = Math.max(sr.getTopK() * 3, rerankTopN);
            List<Document> fused = hybridRetriever.retrieve(query, sr, knowledgeTag, candidatePool);
            return (rerankService != null)
                    ? rerankService.rerank(userText, fused, rerankTopN)
                    : fused.subList(0, Math.min(rerankTopN, fused.size()));
        }
        return this.vectorStore.similaritySearch(sr);
    }

    /**
     * P2.3 12.2 Query Decomposition：为每个子查询检索文档，合并去重后 re-rank。
     */
    private List<Document> retrieveForSubQueries(SearchRequest baseSr, List<String> subQueries, String userText) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<Document> allDocs = new java.util.ArrayList<>();
        for (String sq : subQueries) {
            SearchRequest sr = SearchRequest.from(baseSr).query(sq).build();
            List<Document> docs;
            if (hybridRetriever != null) {
                int pool = Math.max(baseSr.getTopK(), rerankTopN);
                docs = hybridRetriever.retrieve(sq, sr, knowledgeTag, pool);
            } else {
                docs = vectorStore.similaritySearch(sr);
            }
            for (Document d : docs) {
                String key = d.getId() != null ? d.getId() : d.getText();
                if (seen.add(key)) allDocs.add(d);
            }
        }
        if (allDocs.isEmpty()) return List.of();
        if (rerankService != null) {
            return rerankService.rerank(userText, allDocs, rerankTopN);
        }
        return allDocs.subList(0, Math.min(rerankTopN, allDocs.size()));
    }

}
