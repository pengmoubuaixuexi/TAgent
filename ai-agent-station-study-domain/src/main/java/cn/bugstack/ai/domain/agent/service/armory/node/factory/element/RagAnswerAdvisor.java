package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import cn.bugstack.ai.domain.agent.model.valobj.RagRouterDecision;
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
        this.userTextAdvise = "\nContext information is below, surrounded by ---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();

        // P1.4 Query Rewriting：仅替换检索 query，不动用户原话；rewriter 失败/未配置无声降级
        String searchQuery = (queryRewriter != null) ? queryRewriter.rewrite(userText) : userText;

        // P2.3 12.1 HyDE：生成假想答案用作检索 query（比口语 query 语义更丰富）
        if (hydeService != null) {
            String hydeQuery = hydeService.generateHypotheticalDocument(searchQuery);
            if (hydeQuery != null) searchQuery = hydeQuery;
        }

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

        SearchRequest searchRequestToUse = SearchRequest.from(this.searchRequest).query(searchQuery).filterExpression(filterExpr).build();

        List<Document> documents;
        // P2.3 12.3 Agentic RAG：上游 AgenticRagAdvisor.before() 写入了 skip 标志 → 直接跳过检索
        Object agenticSkip = context.get(cn.bugstack.ai.domain.agent.service.rag.agentic.AgenticRagAdvisor.CTX_SKIP_RAG);
        if (Boolean.TRUE.equals(agenticSkip)) {
            documents = List.of();
        } else {
            // P0.1.4 + P2.3：RAG Router 一次决策（LlmRagRouter 输出路径+子查询/变体，HeuristicRagRouter 仅布尔）
            RagRouterDecision decision = (ragRouter != null)
                    ? ragRouter.decide(userText)
                    : RagRouterDecision.retrieve();

            if (!decision.isShouldRetrieve()) {
                documents = List.of();
                log.info("rag-router skip: reason={}", decision.getReason());
            }
            // LlmRagRouter 已拆好子查询 → 直接用，跳过 queryDecomposer
            else if (RagRouterDecision.PATH_DECOMPOSE.equals(decision.getPath())
                    && !decision.getSubQueries().isEmpty()) {
                documents = retrieveForSubQueries(searchRequestToUse, decision.getSubQueries(), userText);
            }
            // LlmRagRouter 已生成变体 → 直接并行检索+RRF，跳过 RagFusionService.generateVariants()
            else if (RagRouterDecision.PATH_FUSION.equals(decision.getPath())
                    && !decision.getVariants().isEmpty()
                    && ragFusionService != null && ragFusionService.isAvailable()) {
                List<Document> fused = ragFusionService.fusedRetrieveWithVariants(
                        searchQuery, decision.getVariants(), searchRequestToUse, rerankTopN);
                documents = (rerankService != null && !fused.isEmpty())
                        ? rerankService.rerank(userText, fused, rerankTopN)
                        : fused;
            }
            // 以下为 HeuristicRagRouter 路径（无 LLM 预拆/预生成），走原分立调用
            else if (queryDecomposer != null && queryDecomposer.shouldDecompose(userText)) {
                List<String> subQueries = queryDecomposer.decompose(userText);
                documents = retrieveForSubQueries(searchRequestToUse, subQueries, userText);
            } else if (ragFusionService != null && ragFusionService.isAvailable()) {
                List<Document> fused = ragFusionService.fusedRetrieve(searchQuery, searchRequestToUse, rerankTopN);
                documents = (rerankService != null && !fused.isEmpty())
                        ? rerankService.rerank(userText, fused, rerankTopN)
                        : fused;
            } else if (hybridRetriever != null) {
                int candidatePool = Math.max(searchRequestToUse.getTopK() * 3, rerankTopN);
                List<Document> fused = hybridRetriever.retrieve(searchQuery, searchRequestToUse, knowledgeTag, candidatePool);
                documents = (rerankService != null)
                        ? rerankService.rerank(userText, fused, rerankTopN)
                        : fused.subList(0, Math.min(rerankTopN, fused.size()));
            } else {
                documents = this.vectorStore.similaritySearch(searchRequestToUse);
            }
        }
        context.put("qa_retrieved_documents", documents);

        // P2.3 12.4 Parent Document Retriever：用检索到的小块 child 换出大块 parent 喂 LLM
        if (parentDocumentService != null && !documents.isEmpty()) {
            List<String> parentTexts = parentDocumentService.resolveParents(documents);
            if (!parentTexts.isEmpty()) {
                documents = parentTexts.stream()
                        .map(text -> new Document(text))
                        .collect(Collectors.toList());
                context.put("qa_retrieved_documents", documents);
            }
        }

        // P2.3 12.6 Citation：给每个文档编号，模型回答时可引用 [1][2]
        StringBuilder docContextBuilder = new StringBuilder();
        Map<Integer, String> citationMap = new HashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            int ref = i + 1;
            docContextBuilder.append("[").append(ref).append("] ").append(documents.get(i).getText()).append("\n\n");
            citationMap.put(ref, documents.get(i).getId() != null ? documents.get(i).getId() : "doc-" + ref);
        }
        String documentContext = docContextBuilder.toString();
        // 追加引用指令
        if (!documents.isEmpty()) {
            documentContext = "When answering, cite relevant sources using [1], [2], etc.\n\n---\n" + documentContext;
        }
        context.put("qa_citation_map", citationMap);

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
        String ragContextMsg = "Context information is below, surrounded by ---------------------\n\n"
                + "---------------------\n" + documentContext + "---------------------\n\n"
                + "Given the context and provided history information and not prior knowledge, "
                + "reply to the user comment. If the answer is not in the context, "
                + "inform the user that you can't answer the question.\n";
        messages.add(new SystemMessage(ragContextMsg));
        // 保持原始 UserMessage 不变
        messages.add(new UserMessage(userText));

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(messages).build())
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
