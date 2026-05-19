package cn.bugstack.ai.domain.agent.service.rag.cache;

/**
 * P2.4 13.1 Semantic Cache：query embedding 近邻搜索，相似度 > 阈值直接返回历史答案。
 * 基于 PgVector cosine distance 实现。
 */
public interface ISemanticCacheService {

    /**
     * 查询缓存：返回相似度 > minSimilarity 且创建时间在 ttlMinutes 内的缓存响应。
     *
     * @param query         用户原始问题
     * @param minSimilarity 最低余弦相似度（0-1），推荐 0.95
     * @param ttlMinutes    缓存有效期（分钟），超过此时间的记录视为过期
     * @return 缓存的响应文本，无命中返回 null
     */
    String lookup(String query, double minSimilarity, int ttlMinutes);

    /**
     * 存入缓存：query 的 embedding + LLM 响应文本。
     *
     * @param query    用户原始问题
     * @param response LLM 响应文本
     * @param model    使用的模型名
     */
    void store(String query, String response, String model);

    /**
     * 清理超过 ttlMinutes 的旧缓存记录。
     *
     * @param ttlMinutes 缓存有效期（分钟）
     * @return 删除的记录数
     */
    int deleteOlderThan(int ttlMinutes);
}
