package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.service.rag.cache.ISemanticCacheService;
import cn.bugstack.ai.domain.agent.service.rag.splitter.SemanticTextSplitter;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.SemanticCacheService;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AiAgentConfig {

    /**
     * -- 删除旧的表（如果存在）
     * DROP TABLE IF EXISTS public.vector_store_openai;
     * <p>
     * -- 创建新的表，使用UUID作为主键
     * CREATE TABLE public.vector_store_openai (
     * id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     * content TEXT NOT NULL,
     * metadata JSONB,
     * embedding VECTOR(1024)
     * );
     * <p>
     * SELECT * FROM vector_store_openai
     */
    /**
     * 共享 EmbeddingModel（BAAI/bge-m3）。
     * PgVectorStore、SemanticCache 等组件共用同一个实例，避免重复建 API client。
     */
    @Bean("embeddingModel")
    public EmbeddingModel embeddingModel(@Value("${agent.embedding.base-url}") String baseUrl,
                                         @Value("${agent.embedding.api-key}") String apiKey,
                                         @Value("${agent.embedding.model:BAAI/bge-m3}") String model,
                                         @Value("${agent.embedding.embeddings-path:}") String embeddingsPath) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .embeddingsPath(OpenAiCompatibleApiSupport.embeddingsPath(baseUrl, embeddingsPath))
                .build();
        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, embeddingOptions);
    }

    /** RAG 知识库专用向量表 */
    @Bean("vectorStore")
    @ConditionalOnBean(name = "pgVectorJdbcTemplate")
    public PgVectorStore pgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       @Qualifier("embeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("vector_store_openai")
                .build();
    }

    /**
     * P2.4 13.1 Semantic Cache：query embedding 近邻搜索，相似度 > 阈值直接返回历史答案。
     * enabled=false 时不注册该 bean，SemanticCacheAdvisor 不会被创建。
     */
    @Bean("semanticCacheService")
    @ConditionalOnBean(name = "pgVectorJdbcTemplate")
    @ConditionalOnProperty(name = "agent.semantic-cache.enabled", havingValue = "true")
    public ISemanticCacheService semanticCacheService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("embeddingModel") EmbeddingModel embeddingModel) {
        return new SemanticCacheService(jdbcTemplate, embeddingModel);
    }

    /**
     * RAG 切片器。
     * <p>
     * 配置说明（单位：字符数，中英混合场景 1500 char ≈ 500 token）：
     * <ul>
     *   <li><b>targetChunkChars = 1500</b>：每片目标大小。比 Spring AI 默认 800 token 略小，
     *       让向量语义更聚焦、减少 prompt 拥挤。</li>
     *   <li><b>overlapChars = 200</b>：相邻片 overlap ~13%。行业最佳实践 10-20% 区间。
     *       避免两片之间把一句话腰斩导致语义断裂。</li>
     *   <li><b>minChunkChars = 300</b>：小于此的片合并到相邻片，避免出现"只有一个标题行"的碎片。</li>
     *   <li><b>maxChunkChars = 2500</b>：单片硬上限。超过此阈值（长代码块、整段 URL）强制按字符切。</li>
     * </ul>
     * <p>
     * 切分优先级：<b>Markdown 标题（章节）→ 空行（段落）→ 句末标点（句子）→ 字符硬切</b>。
     * 尽可能让片的边界落在自然语义断点，embedding 向量能保留完整的一个想法/一段说明。
     */
    @Bean
    public TextSplitter textSplitter() {
        return new SemanticTextSplitter(1500, 200, 300, 2500);
    }

}
