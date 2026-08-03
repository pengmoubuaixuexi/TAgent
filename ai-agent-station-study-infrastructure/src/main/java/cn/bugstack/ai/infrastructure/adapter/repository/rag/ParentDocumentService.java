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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        store(parentId, parentText, childDocs, knowledgeTag, source, null, null);
    }

    @Override
    public void store(String parentId, String parentText, List<Document> childDocs, String knowledgeTag, String source, String userId) {
        store(parentId, parentText, childDocs, knowledgeTag, source, userId, null);
    }

    @Override
    public void store(String parentId, String parentText, List<Document> childDocs,
                      String knowledgeTag, String source, String userId, String title) {
        // 1. 存 parent 到 MySQL（含 title）
        AiParentDocument po = AiParentDocument.builder()
                .parentId(parentId)
                .content(parentText)
                .knowledgeTag(knowledgeTag)
                .source(source)
                .title(title != null && !title.isBlank() ? title : null)
                .userId(userId)
                .build();
        parentDocumentDao.insert(po);

        // 2. 给每个 child 标记 parent_id / knowledge / source / user_id / title，写入 PgVector
        // title 也注入 child metadata，让 BM25 路径回收的 child Document 也能直接展示精炼标题
        for (Document child : childDocs) {
            child.getMetadata().put("parent_id", parentId);
            child.getMetadata().put("knowledge", knowledgeTag);
            child.getMetadata().put("source", source);
            if (title != null && !title.isBlank()) {
                child.getMetadata().put("title", title);
            }
            if (userId != null && !userId.isBlank()) {
                child.getMetadata().put("user_id", userId);
            }
        }
        vectorStore.accept(childDocs);

        log.info("[ParentDoc] stored parent={} children={} tag={} userId={} title='{}'",
                parentId, childDocs.size(), knowledgeTag, userId, title);
    }

    @Override
    public List<String> resolveParents(List<Document> childDocs) {
        return resolveParentDocuments(childDocs).stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }

    @Override
    public List<Document> resolveParentDocuments(List<Document> childDocs) {
        if (childDocs == null || childDocs.isEmpty()) return List.of();

        // 提取去重 parent_id
        Set<String> parentIds = new LinkedHashSet<>();
        Map<String, Map<String, Object>> relevanceByParent = new HashMap<>();
        for (Document doc : childDocs) {
            Object pid = doc.getMetadata().get("parent_id");
            if (pid != null) {
                String parentId = pid.toString();
                parentIds.add(parentId);
                Map<String, Object> aggregate = relevanceByParent.computeIfAbsent(parentId, ignored -> new HashMap<>());
                mergeMax(aggregate, "rerank_score", number(doc.getMetadata().get("rerank_score")));
                mergeMax(aggregate, "semantic_similarity", semanticSimilarity(doc));
                if (!aggregate.containsKey("matched_child_snippet") && doc.getText() != null) {
                    aggregate.put("matched_child_snippet", doc.getText());
                    aggregate.put("matched_child_id", doc.getId());
                }
            }
        }

        if (parentIds.isEmpty()) {
            // 没有 parent_id 元数据 → 回退，返回原始 child（保留 child metadata）
            return new ArrayList<>(childDocs);
        }

        List<String> idList = new ArrayList<>(parentIds);
        List<AiParentDocument> parents = parentDocumentDao.findByParentIds(idList);
        log.debug("[ParentDoc] resolved {} children → {} unique parents", childDocs.size(), parents.size());

        // 把 PO → Spring AI Document，metadata 携带 source/knowledge/title，
        // 这样下游 RagAnswerAdvisor 包装 prompt 时不丢字段，evidence 卡片才能显示可读标题
        return parents.stream()
                .map(po -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("parent_id", po.getParentId());
                    if (po.getKnowledgeTag() != null) metadata.put("knowledge", po.getKnowledgeTag());
                    if (po.getSource() != null) metadata.put("source", po.getSource());
                    if (po.getTitle() != null && !po.getTitle().isBlank()) metadata.put("title", po.getTitle());
                    if (po.getUserId() != null) metadata.put("user_id", po.getUserId());
                    Map<String, Object> relevance = relevanceByParent.get(po.getParentId());
                    if (relevance != null) metadata.putAll(relevance);
                    return new Document(po.getParentId(), po.getContent(), metadata);
                })
                .collect(Collectors.toList());
    }

    private static void mergeMax(Map<String, Object> target, String key, Double value) {
        if (value == null || !Double.isFinite(value)) return;
        Double current = number(target.get(key));
        if (current == null || value > current) target.put(key, value);
    }

    private static Double semanticSimilarity(Document document) {
        if (document == null) return null;
        Double score = document.getScore();
        if (score != null && Double.isFinite(score)) return clamp01(score);
        Double distance = number(document.getMetadata().get("distance"));
        return distance == null ? null : clamp01(1.0d - distance);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private static double clamp01(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
