package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;

import java.util.List;
import java.util.Set;

/**
 * 工具语义向量库（PgVector 实现）。把每条工具的文本 embed 后持久化，查询时按 cosine 相似度取 top-N。
 *
 * <p>纯词法 BM25 在"同义词错位 + 多个'搜索X'工具"下分不开；embedding 语义相似度跨同义词、分领域。
 * 向量存 PgVector（而非内存）：刷新时 embed 一次持久化，之后只查，不必每次启动/每次测试都重算。
 * 复用系统已有的 pgVectorJdbcTemplate + EmbeddingModel + VECTOR(1024) 基建（RAG/LTM/semantic_cache 同款）。
 */
public interface IToolVectorStore {

    /** 刷新目录时调：把这批工具 embed 并全量同步进向量库（库里多余的删掉）。 */
    void syncAll(List<AiMcpToolCatalogVO> tools);

    /**
     * 语义检索 top-N。
     *
     * @return 命中工具（按相似度降序、已过 min-similarity、已排除 excludeNames）；
     *         <b>{@code null}</b> = 向量库出错/不可用（调用方应回退词法匹配）；空列表 = 查询正常但无命中。
     */
    List<AiMcpToolCatalogVO> search(String need, Set<String> excludeNames, int topN);

    /** 向量库是否可用且有数据——决定 matcher 是否走 embedding（否则回退 BM25）。 */
    boolean isAvailable();
}
