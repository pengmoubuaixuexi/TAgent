package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * P2.3 12.4 Parent Document Retriever：两段式文档存储与检索。
 * 入库时大块（parent）存 MySQL，小块（child）存 PgVector；
 * 检索时用 child 精确匹配，然后换出 parent 喂给 LLM。
 */
public interface IParentDocumentService {

    /**
     * 存储文档对：一个 parent 对应多个 child。
     */
    void store(String parentId, String parentText, List<Document> childDocs, String knowledgeTag, String source);

    /**
     * 存储文档对（带 userId 隔离）。
     */
    void store(String parentId, String parentText, List<Document> childDocs, String knowledgeTag, String source, String userId);

    /**
     * 第 61 轮 Phase 2：带 title 的存储方法。
     * <p>title 用于前端引用依据卡片显示精炼小标题（5-15 字）。null/blank → 不存 title，前端 fallback 到 source。</p>
     */
    void store(String parentId, String parentText, List<Document> childDocs,
               String knowledgeTag, String source, String userId, String title);

    /**
     * 根据子文档列表查找对应的父文档，去重后返回父文档文本列表。
     *
     * @param childDocs 向量检索返回的子文档（metadata 需含 parent_id）
     * @return 去重后的父文档文本列表
     * @deprecated 用 {@link #resolveParentDocuments(List)} 替代以保留 metadata（source/knowledge/title），
     *             否则前端引用依据卡片会显示 UUID 而不是可读标题
     */
    @Deprecated
    List<String> resolveParents(List<Document> childDocs);

    /**
     * P1 第 61 轮：保留 metadata 版的 parent 解析。
     * <p>跟 {@link #resolveParents(List)} 等价，但返回 {@link Document} 列表，
     * 每个 Document 的 metadata 至少包含：{@code source} / {@code knowledge} / {@code parent_id}；
     * 若 Phase 2 接入 parent title，会额外携带 {@code title}。</p>
     * <p>调用方在 RAG advisor 把 child → parent 后渲染引用依据，应该用本方法而不是丢失 metadata 的旧方法。</p>
     */
    List<Document> resolveParentDocuments(List<Document> childDocs);
}
