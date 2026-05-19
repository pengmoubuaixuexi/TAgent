package cn.bugstack.ai.domain.agent.service.rag.fusion;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;

/**
 * P2.3 12.7 RAG Fusion：单 query 生成 N 个变体并行检索，RRF 融合。
 * <p>
 * 原理：同一问题换 N 种说法分别检索，RRF（Reciprocal Rank Fusion）融合，
 * 比单次检索召回更全——尤其主 query 和文档措辞不完全匹配时。
 */
public interface IRagFusionService {

    /**
     * 生成 N 个查询变体，分别检索，RRF 融合后返回 topK。
     *
     * @param originalQuery 原始用户查询
     * @param topK          最终返回的文档数
     * @return RRF 融合后的文档列表（去重、按 RRF score 降序）
     */
    List<Document> fusedRetrieve(String originalQuery, int topK);

    /**
     * 与 {@link #fusedRetrieve(String, int)} 相同，但携带外部 SearchRequest 模板（含 filterExpression）。
     * 实现需用 {@code SearchRequest.from(baseSr).query(...)} 继承外层 filter（knowledge / user_id 等）。
     */
    default List<Document> fusedRetrieve(String originalQuery, SearchRequest baseSearchRequest, int topK) {
        return fusedRetrieve(originalQuery, topK);
    }

    /**
     * 使用预生成的变体做并行检索 + RRF 融合（跳过 LLM 变体生成）。
     * LlmRagRouter 已一次调用产出变体时走此路径，省一次 LLM 调用。
     *
     * @param originalQuery 原始用户查询
     * @param variants      预生成的检索变体
     * @param topK          最终返回的文档数
     */
    default List<Document> fusedRetrieveWithVariants(String originalQuery, List<String> variants, int topK) {
        return fusedRetrieve(originalQuery, topK);
    }

    /**
     * 与上面同名重载一致，但携带 SearchRequest 模板（含 filter）。
     */
    default List<Document> fusedRetrieveWithVariants(String originalQuery, List<String> variants,
                                                     SearchRequest baseSearchRequest, int topK) {
        return fusedRetrieveWithVariants(originalQuery, variants, topK);
    }

    /**
     * 检查当前是否可用（ChatClient 已配置且健康）。
     */
    default boolean isAvailable() { return true; }
}
