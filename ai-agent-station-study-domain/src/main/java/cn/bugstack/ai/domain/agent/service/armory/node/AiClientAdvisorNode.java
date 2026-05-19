package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientAdvisorTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.CoVeAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.PiiMaskerAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.PromptInjectionDefenseAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;
import cn.bugstack.ai.domain.agent.service.rag.HyDEService;
import cn.bugstack.ai.domain.agent.service.rag.IQueryDecomposer;
import cn.bugstack.ai.domain.agent.service.rag.LlmQueryDecomposer;
import cn.bugstack.ai.domain.agent.service.rag.fusion.IRagFusionService;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.rag.IParentDocumentService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.IRerankService;
import cn.bugstack.ai.domain.agent.service.router.IQueryRewriter;
import cn.bugstack.ai.domain.agent.service.router.IRagRouter;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 顾问角色节点
 *
 * @author TAgent
 * 2025/7/19 08:51
 */
@Slf4j
@Service
public class AiClientAdvisorNode extends AbstractArmorySupport {

    @Resource
    private VectorStore vectorStore;

    @Resource
    private AiClientNode aiClientNode;

    /** 亮点 3 混合检索组件：BM25（ES） + RRF 融合；可选依赖，缺失则 RagAnswerAdvisor 回退纯向量路径 */
    @Autowired(required = false)
    private HybridRetriever hybridRetriever;

    @Autowired(required = false)
    private IRerankService rerankService;

    /** P0.1.4 RAG Router；闲聊跳过双库 + rerank 省一次调用 */
    @Autowired(required = false)
    private IRagRouter ragRouter;

    /** P0.2.1 持久化 ChatMemory；为空时枚举回退默认 InMemoryChatMemoryRepository */
    @Autowired(required = false)
    private ChatMemoryRepository chatMemoryRepository;

    /** P0.2.2 共享 SummarizingChatMemory；agent.chat-memory.summarize-enabled=true 时存在 */
    @Autowired(required = false)
    private ChatMemory sharedChatMemory;

    /** P1.4 Query Rewriter；agent.query-rewriter.client-id 未配置则不改写 */
    @Autowired(required = false)
    private IQueryRewriter queryRewriter;

    /** P1.7 长期记忆服务；为 null 时 LongTermMemoryAdvisor 静默 no-op */
    @Autowired(required = false)
    private ILongTermMemoryService longTermMemoryService;

    /** P2.1 跨会话摘要记忆服务；为 null 时 EpisodicMemoryAdvisor 静默 no-op */
    @Autowired(required = false)
    private IEpisodicMemoryService episodicMemoryService;

    /** P2.3 12.4 Parent Document Retriever；为 null 时不换 parent */
    @Autowired(required = false)
    private IParentDocumentService parentDocumentService;

    @Autowired
    private ApplicationContext applicationContext;

    /** V035 (2026-05-14)：装配 LlmQueryDecomposer 时注入指标，让 query 拆分的隐形流量也进 Prometheus */
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder llmObservationRecorder;

    /** RAG 辅助 LLM clientId；默认复用意图路由小模型，避免 HyDE/Decompose/Fusion 误用主对话 pro。 */
    @Value("${agent.rag-router.client-id:${agent.intent-router.client-id:router-small}}")
    private String ragHelperClientId;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Advisor 顾问角色{}", JSON.toJSONString(requestParameter));

        List<AiClientAdvisorVO> aiClientAdvisorList = dynamicContext.getValue(dataName());

        if (aiClientAdvisorList == null || aiClientAdvisorList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client advisor");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientAdvisorVO aiClientAdvisorVO : aiClientAdvisorList) {
            // 构建顾问访问对象
            Advisor advisor = createAdvisor(aiClientAdvisorVO);
            if (advisor == null) {
                log.warn("跳过未能创建的 advisor: advisorType={} advisorId={}",
                        aiClientAdvisorVO.getAdvisorType(), aiClientAdvisorVO.getAdvisorId());
                continue;
            }
            // 注册Bean对象
            registerBean(beanName(aiClientAdvisorVO.getAdvisorId()), Advisor.class, advisor);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientNode;
    }

    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName();
    }

    private Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO) {
        String advisorType = aiClientAdvisorVO.getAdvisorType();

        // RAG helper ChatClient：多个 advisor 类型共用（AgenticRag / CoVe / HyDE / LongTermMemory 提取等）
        ChatClient routerSmall = null;
        try {
            routerSmall = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(ragHelperClientId), ChatClient.class);
        } catch (Exception ignored) {}

        // P2.4 13.1 Semantic Cache：query embedding 近邻搜索，相似度 > 阈值直接返回历史答案
        if ("SemanticCache".equals(advisorType)) {
            try {
                cn.bugstack.ai.domain.agent.service.rag.cache.ISemanticCacheService cacheService =
                        applicationContext.getBean("semanticCacheService", cn.bugstack.ai.domain.agent.service.rag.cache.ISemanticCacheService.class);
                double minSimilarity = 0.95;
                try {
                    AiClientAdvisorVO.RagAnswer cfg = aiClientAdvisorVO.getRagAnswer();
                    if (cfg != null && cfg.getTopK() > 0) minSimilarity = cfg.getTopK() / 100.0;
                } catch (Exception ignored) {}
                // Claude 改进：注入 MeterRegistry 让 hit/miss 进 Prometheus
                io.micrometer.core.instrument.MeterRegistry meterRegistry = null;
                try {
                    meterRegistry = applicationContext.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                } catch (Exception ignored) {}
                // Claude 修复：order=-200 让缓存命中真正短路所有下游 advisor（原 100 太靠后失去 cost-saving）
                return new cn.bugstack.ai.domain.agent.service.rag.cache.SemanticCacheAdvisor(cacheService, minSimilarity, -200, meterRegistry);
            } catch (Exception e) {
                log.warn("[AdvisorNode] SemanticCache service not available: {}", e.getMessage(), e);
                return null;
            }
        }

        // P2.3 12.3 Agentic RAG：LLM 自主决定是否需要检索，router-small 做决策
        if ("AgenticRag".equals(advisorType)) {
            if (routerSmall != null) {
                return new cn.bugstack.ai.domain.agent.service.rag.agentic.AgenticRagAdvisor(routerSmall);
            }
        }

        // P2.2 11.1 CoVe：需要 router-small ChatClient 做事实抽取，从 Spring 容器直接取
        if ("CoVe".equals(advisorType)) {
            int topK = 4;
            try {
                AiClientAdvisorVO.RagAnswer cfg = aiClientAdvisorVO.getRagAnswer();
                if (cfg != null && cfg.getTopK() > 0) topK = cfg.getTopK();
            } catch (Exception ignored) {}
            return new CoVeAdvisor(vectorStore, routerSmall, topK);
        }

        // P2.5 14.2 PiiMask：输入侧 PII 脱敏，在 PromptInjection 之前执行
        if ("PiiMask".equals(advisorType)) {
            return new PiiMaskerAdvisor();
        }

        // P2.5 14.1 PromptInjection：简单 regex 防御，无需 ChatClient
        if ("PromptInjection".equals(advisorType)) {
            return new PromptInjectionDefenseAdvisor();
        }

        // P2.3 12.1 HyDE & 12.2 Query Decomposition & 12.7 RAG Fusion：共享 router-small ChatClient
        HyDEService hydeService = null;
        IRagFusionService ragFusionService = null;
        IQueryDecomposer queryDecomposer = null;
        try {
            hydeService = new HyDEService(routerSmall);
            // Claude 修复：把 hybridRetriever 传进 RagFusion，让每个变体也走 BM25+向量双引擎
            // 跟 Decomposition 路径行为对齐（之前 Fusion 写死纯向量，BM25 完全失效）
            String fusionKnowledgeTag = null;
            if ("RagAnswer".equals(advisorType)) {
                try {
                    AiClientAdvisorVO.RagAnswer cfg = aiClientAdvisorVO.getRagAnswer();
                    if (cfg != null) fusionKnowledgeTag =
                            AiClientAdvisorTypeEnumVO.extractKnowledgeTag(cfg.getFilterExpression());
                } catch (Exception ignored2) {}
            }
            ragFusionService = new cn.bugstack.ai.domain.agent.service.rag.fusion.RagFusionService(
                    routerSmall, vectorStore, hybridRetriever, fusionKnowledgeTag);
            LlmQueryDecomposer decomposer = new LlmQueryDecomposer(routerSmall);
            // V035: 注入指标采集，路由层 LLM 调用也进 Prometheus / event_log
            decomposer.setObservability(llmObservationRecorder);
            queryDecomposer = decomposer;
        } catch (Exception ignored) {}

        AiClientAdvisorTypeEnumVO advisorTypeEnum = AiClientAdvisorTypeEnumVO.getByCode(advisorType);

        // P2.3 12.1 HyDE + 12.2 Query Decomp + 12.7 RAG Fusion：为 RAG_ANSWER 注入全套 RAG 增强
        if ("RagAnswer".equals(advisorType) && hydeService != null) {
            AiClientAdvisorVO.RagAnswer ragAnswer = aiClientAdvisorVO.getRagAnswer();
            SearchRequest sr = SearchRequest.builder()
                    .topK(ragAnswer.getTopK())
                    .filterExpression(ragAnswer.getFilterExpression())
                    .build();
            String knowledgeTag = AiClientAdvisorTypeEnumVO.extractKnowledgeTag(ragAnswer.getFilterExpression());
            return new RagAnswerAdvisor(vectorStore, sr,
                    hybridRetriever, rerankService, ragAnswer.getTopK(), knowledgeTag,
                    ragRouter, queryRewriter, hydeService, ragFusionService, queryDecomposer, parentDocumentService);
        }

        log.info("[AdvisorNode] creating advisor type={} sharedChatMemory={} chatMemoryRepository={}",
                advisorType, sharedChatMemory != null, chatMemoryRepository != null);
        Advisor advisor = advisorTypeEnum.createAdvisor(
                aiClientAdvisorVO, vectorStore, hybridRetriever, rerankService,
                ragRouter, chatMemoryRepository, sharedChatMemory, queryRewriter, longTermMemoryService, episodicMemoryService);
        if (advisor instanceof LongTermMemoryAdvisor ltmAdvisor) {
            if (routerSmall != null) {
                ltmAdvisor.setExtractionClient(routerSmall);
            }
            ltmAdvisor.setApplicationContext(applicationContext);
        }
        return advisor;
    }

}
