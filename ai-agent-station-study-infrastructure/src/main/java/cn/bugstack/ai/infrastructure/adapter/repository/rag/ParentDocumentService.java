package cn.bugstack.ai.infrastructure.adapter.repository.rag;

import cn.bugstack.ai.domain.agent.service.rag.IParentDocumentService;
import cn.bugstack.ai.infrastructure.dao.IAiParentDocumentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiParentDocument;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P2.3 12.4 Parent Document Retriever 实现。
 * Parent 存 MySQL（ai_parent_document），child 存 PgVector（metadata.parent_id 关联）。
 */
@Slf4j
@Service
public class ParentDocumentService implements IParentDocumentService {

    @Resource
    private IAiParentDocumentDao parentDocumentDao;

    @Resource
    private PgVectorStore vectorStore;

    @Override
    public void store(String parentId, String parentText, List<Document> childDocs, String knowledgeTag, String source) {
        store(parentId, parentText, childDocs, knowledgeTag, source, null);
    }

    @Override
    public void store(String parentId, String parentText, List<Document> childDocs, String knowledgeTag, String source, String userId) {
        // 1. 存 parent 到 MySQL
        AiParentDocument po = AiParentDocument.builder()
                .parentId(parentId)
                .content(parentText)
                .knowledgeTag(knowledgeTag)
                .source(source)
                .userId(userId)
                .build();
        parentDocumentDao.insert(po);

        // 2. 给每个 child 标记 parent_id / knowledge / source / user_id，写入 PgVector
        for (Document child : childDocs) {
            child.getMetadata().put("parent_id", parentId);
            child.getMetadata().put("knowledge", knowledgeTag);
            child.getMetadata().put("source", source);
            if (userId != null && !userId.isBlank()) {
                child.getMetadata().put("user_id", userId);
            }
        }
        vectorStore.accept(childDocs);

        log.info("[ParentDoc] stored parent={} children={} tag={} userId={}", parentId, childDocs.size(), knowledgeTag, userId);
    }

    @Override
    public List<String> resolveParents(List<Document> childDocs) {
        if (childDocs == null || childDocs.isEmpty()) return List.of();

        // 提取去重 parent_id
        Set<String> parentIds = new LinkedHashSet<>();
        for (Document doc : childDocs) {
            Object pid = doc.getMetadata().get("parent_id");
            if (pid != null) parentIds.add(pid.toString());
        }

        if (parentIds.isEmpty()) {
            // 没有 parent_id 元数据 → 回退，返回原始 child 文本
            return childDocs.stream().map(Document::getText).collect(Collectors.toList());
        }

        List<String> idList = new ArrayList<>(parentIds);
        List<AiParentDocument> parents = parentDocumentDao.findByParentIds(idList);
        log.debug("[ParentDoc] resolved {} children → {} unique parents", childDocs.size(), parents.size());

        return parents.stream().map(AiParentDocument::getContent).collect(Collectors.toList());
    }
}
