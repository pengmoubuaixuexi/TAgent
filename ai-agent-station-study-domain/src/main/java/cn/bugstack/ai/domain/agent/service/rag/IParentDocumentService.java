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
     * 根据子文档列表查找对应的父文档，去重后返回父文档文本列表。
     *
     * @param childDocs 向量检索返回的子文档（metadata 需含 parent_id）
     * @return 去重后的父文档文本列表
     */
    List<String> resolveParents(List<Document> childDocs);
}
