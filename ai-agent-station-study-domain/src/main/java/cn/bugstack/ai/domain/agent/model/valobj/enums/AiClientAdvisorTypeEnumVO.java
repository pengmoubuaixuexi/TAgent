package cn.bugstack.ai.domain.agent.model.valobj.enums;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.IRerankService;
import cn.bugstack.ai.domain.agent.service.router.IQueryRewriter;
import cn.bugstack.ai.domain.agent.service.router.IRagRouter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.EpisodicMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.ReadOnlyChatMemoryAdvisor;

import java.util.HashMap;
import java.util.Map;

/**
 * 顾问类型枚举
 *
 * @author TAgent
 * 2025/7/19 09:02
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AiClientAdvisorTypeEnumVO {

    CHAT_MEMORY("ChatMemory", "上下文记忆（持久化 + 可选摘要）") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter) {
            return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter, null, null);
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository) {
            return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter, chatMemoryRepository, null);
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                     ChatMemory sharedChatMemory) {
            // P0.2.2：优先用 SummarizingChatMemory（共享 bean，配置开关启用时存在）；否则回退 P0.2.1
            if (sharedChatMemory != null) {
                return new ReadOnlyChatMemoryAdvisor(sharedChatMemory);
            }
            AiClientAdvisorVO.ChatMemory chatMemory = aiClientAdvisorVO.getChatMemory();
            MessageWindowChatMemory.Builder memBuilder = MessageWindowChatMemory.builder()
                    .maxMessages(chatMemory.getMaxMessages());
            if (chatMemoryRepository != null) {
                memBuilder.chatMemoryRepository(chatMemoryRepository);
            }
            return new ReadOnlyChatMemoryAdvisor(memBuilder.build());
        }
    },

    LONG_TERM_MEMORY("LongTermMemory", "用户级长期记忆（P1.7）") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter) {
            // 没传 ltmService 也能跑——advisor 直接 fallback no-op；保留这个分支做向下兼容
            int topK = 4;
            try {
                AiClientAdvisorVO.RagAnswer cfg = aiClientAdvisorVO.getRagAnswer();
                if (cfg != null && cfg.getTopK() > 0) topK = cfg.getTopK();
            } catch (Exception ignored) {}
            return new LongTermMemoryAdvisor(null, topK);
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                     ChatMemory sharedChatMemory, IQueryRewriter queryRewriter,
                                     ILongTermMemoryService ltmService) {
            int topK = 4;
            try {
                AiClientAdvisorVO.RagAnswer cfg = aiClientAdvisorVO.getRagAnswer();
                if (cfg != null && cfg.getTopK() > 0) topK = cfg.getTopK();
            } catch (Exception ignored) {}
            return new LongTermMemoryAdvisor(ltmService, topK);
        }
    },

    EPISODIC_MEMORY("EpisodicMemory", "跨会话摘要记忆（P2.1）") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter) {
            return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter, null, null, null, null);
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                     ChatMemory sharedChatMemory, IQueryRewriter queryRewriter,
                                     ILongTermMemoryService ltmService) {
            // 透传到底下 10 参版，episodic 从新参数走
            return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService,
                    ragRouter, chatMemoryRepository, sharedChatMemory, queryRewriter, ltmService, null);
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                     ChatMemory sharedChatMemory, IQueryRewriter queryRewriter,
                                     ILongTermMemoryService ltmService, IEpisodicMemoryService episodicService) {
            int topK = 4;
            try {
                AiClientAdvisorVO.RagAnswer cfg = aiClientAdvisorVO.getRagAnswer();
                if (cfg != null && cfg.getTopK() > 0) topK = cfg.getTopK();
            } catch (Exception ignored) {}
            return new EpisodicMemoryAdvisor(episodicService, topK);
        }
    },

    RAG_ANSWER("RagAnswer", "知识库") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter) {
            return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter, null, null, null);
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                     IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                     ChatMemory sharedChatMemory, IQueryRewriter queryRewriter) {
            AiClientAdvisorVO.RagAnswer ragAnswer = aiClientAdvisorVO.getRagAnswer();
            SearchRequest searchRequest = SearchRequest.builder()
                    .topK(ragAnswer.getTopK())
                    .filterExpression(ragAnswer.getFilterExpression())
                    .build();
            String knowledgeTag = extractKnowledgeTag(ragAnswer.getFilterExpression());
            return new RagAnswerAdvisor(vectorStore, searchRequest,
                    hybridRetriever, llmRerankService, ragAnswer.getTopK(), knowledgeTag,
                    ragRouter, queryRewriter);
        }
    }

    ;

    private String code;
    private String info;
    
    // 静态Map缓存，用于快速查找
    private static final Map<String, AiClientAdvisorTypeEnumVO> CODE_MAP = new HashMap<>();
    
    // 静态初始化块，在类加载时初始化Map
    static {
        for (AiClientAdvisorTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }
    
    /**
     * 策略方法：创建顾问对象
     * @param aiClientAdvisorVO 顾问配置对象
     * @param vectorStore 向量存储
     * @return 顾问对象
     */
    public abstract Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                          HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                          IRagRouter ragRouter);

    /**
     * 全参版（含 ChatMemoryRepository，P0.2.1 新增）。
     * 默认实现忽略 repo 参数，转发到 5 参版；只有 CHAT_MEMORY 枚举重载使用。
     */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                 HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                 IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter);
    }

    /**
     * 全参版 + ChatMemory（P0.2.2 新增）。
     * 默认实现忽略 sharedChatMemory，转发到 6 参版；只有 CHAT_MEMORY 枚举重载使用。
     */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                 HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                 IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                 ChatMemory sharedChatMemory) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter, chatMemoryRepository);
    }

    /**
     * 全参版 + QueryRewriter（P1.4 新增）。默认实现忽略 queryRewriter，转发到 7 参版；
     * 只有 RAG_ANSWER 枚举重载使用。
     */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                 HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                 IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                 ChatMemory sharedChatMemory, IQueryRewriter queryRewriter) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, ragRouter, chatMemoryRepository, sharedChatMemory);
    }

    /**
     * 全参版 + LongTermMemoryService（P1.7 新增）。默认实现忽略 ltmService，转发到 8 参版；
     * 只有 LONG_TERM_MEMORY 枚举重载使用。
     */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                 HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                 IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                 ChatMemory sharedChatMemory, IQueryRewriter queryRewriter,
                                 ILongTermMemoryService ltmService) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService,
                ragRouter, chatMemoryRepository, sharedChatMemory, queryRewriter);
    }

    /**
     * 全参版 + EpisodicMemoryService（P2.1 新增）。默认实现忽略 episodicService，转发到 9 参版；
     * 只有 EPISODIC_MEMORY 枚举重载使用。
     */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                 HybridRetriever hybridRetriever, IRerankService llmRerankService,
                                 IRagRouter ragRouter, ChatMemoryRepository chatMemoryRepository,
                                 ChatMemory sharedChatMemory, IQueryRewriter queryRewriter,
                                 ILongTermMemoryService ltmService, IEpisodicMemoryService episodicService) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService,
                ragRouter, chatMemoryRepository, sharedChatMemory, queryRewriter, ltmService);
    }

    /** 兼容老调用路径（测试类直接 new，不走装配） */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, null, null, null);
    }

    /** 兼容半新调用路径（hybrid + rerank 已接，但 router 还没用） */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                 HybridRetriever hybridRetriever, IRerankService llmRerankService) {
        return createAdvisor(aiClientAdvisorVO, vectorStore, hybridRetriever, llmRerankService, null);
    }

    /**
     * 从 SearchRequest 的 filterExpression 文本里尝试提取 knowledge 值。
     * 约定写法："knowledge == 'abc'"（项目里 RAG tag 的统一表达）。解析不到返回 null。
     */
    public static String extractKnowledgeTag(String filterExpression) {
        if (filterExpression == null) return null;
        String s = filterExpression.trim();
        int eq = s.indexOf("knowledge");
        if (eq < 0) return null;
        int quote1 = s.indexOf('\'', eq);
        if (quote1 < 0) return null;
        int quote2 = s.indexOf('\'', quote1 + 1);
        if (quote2 < 0) return null;
        return s.substring(quote1 + 1, quote2);
    }
    
    /**
     * 根据code获取枚举
     * @param code 编码
     * @return 枚举对象
     */
    public static AiClientAdvisorTypeEnumVO getByCode(String code) {
        AiClientAdvisorTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! advisorType " + code + " not exist!");
        }
        return enumVO;
    }

}
