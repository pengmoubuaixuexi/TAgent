package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiRagOrderVO;
import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.domain.agent.service.rag.splitter.SemanticTextSplitter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 知识库服务
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/10/4 09:12
 */
@Slf4j
@Service
public class RagService implements IRagService {

    /**
     * Parent 已由全局 TextSplitter 按 ~1500 chars 做语义切分；Child 用更小目标块做检索索引，
     * 但仍复用同一套 Markdown/段落/句子/代码块边界保护规则。
     */
    private static final SemanticTextSplitter CHILD_TEXT_SPLITTER =
            new SemanticTextSplitter(450, 50, 120, 2500);

    @Resource
    private TextSplitter textSplitter;

    @Resource
    private PgVectorStore vectorStore;

    @Resource
    private IAgentRepository repository;

    /** BM25 镜像索引：与 PgVector 双写，支撑混合检索（Advisor 层融合） */
    @Autowired(required = false)
    private BM25SearchService bm25SearchService;

    /** P1.4 7.3 Contextual Retrieval：入库前为每个 chunk 生成上下文前缀，拼到正文头部提升召回率 */
    @Autowired(required = false)
    private IContextualPrefixGenerator contextualPrefixGenerator;

    /** P2.3 12.4 Parent Document Retriever：两段式存储（大块 parent / 小块 child） */
    @Autowired(required = false)
    private IParentDocumentService parentDocumentService;

    /** 第 61 轮 Phase 2 (A 方案)：为每个 parent 生成 5-15 字小标题写入 title 列 */
    @Autowired(required = false)
    private IParentTitleGenerator parentTitleGenerator;

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files) {
        storeRagFile(name, tag, files, null);
    }

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files, String userId) {
        // 同一批上传共享毫秒时间戳，附加文件序号保证唯一；ai_client_rag_order.rag_id 是 NOT NULL UNIQUE
        long batch = System.currentTimeMillis();
        int seq = 0;
        for (MultipartFile file : files) {
            // P2.3 12.5 文件去重：计算 SHA-256，已存在则跳过
            String fileHash;
            long fileSize;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] bytes = file.getBytes();
                fileHash = HexFormat.of().formatHex(md.digest(bytes));
                fileSize = bytes.length;
            } catch (Exception e) {
                log.warn("SHA-256 compute failed for '{}', skip dedup check: {}", file.getOriginalFilename(), e.getMessage());
                fileHash = null;
                fileSize = -1L;
            }

            // Claude 修复：原版只算了 hash 没用，现在真正做幂等去重——
            // 命中已存在则跳过整个 ① 切片 ② Contextual prefix 生成（每 chunk 一次小模型）
            // ③ embedding ④ vectorStore 写入 ⑤ ES 索引 ⑥ MySQL 插入。重复上传同份文件不再烧钱
            if (fileHash != null && repository.existsRagFileByHashAndTag(fileHash, tag)) {
                log.info("[RAG] 跳过重复文件 name='{}' size={} hash={}",
                        file.getOriginalFilename(), fileSize, fileHash);
                seq++;
                continue;
            }

            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documentList = textSplitter.apply(documentReader.get());

            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : name;

            // 添加知识库标签、原始文件名、userId
            documentList.forEach(doc -> {
                doc.getMetadata().put("knowledge", tag);
                doc.getMetadata().put("source", originalFilename);
                if (userId != null && !userId.isBlank()) {
                    doc.getMetadata().put("user_id", userId);
                }
            });

            // P2.3 12.4 Parent Document Retriever：两段式存储
            // parent（大块）存 MySQL，child（小块）存 PgVector；检索时换出 parent 喂 LLM
            if (parentDocumentService != null) {
                // 第 61 轮 Phase 2 (A 方案)：在切 child 前先给每个 parent 生成精炼小标题
                // 跟 contextualPrefixGenerator 共用小模型 client，但落地字段不同：
                //   - title 写 ai_parent_document.title 列 + 透传到 child metadata（A）
                //   - contextual prefix 拼到 child.text 头部，仅用于向量化/BM25 检索（C，下面 Phase 3）
                List<String> parentTitles = (parentTitleGenerator != null)
                        ? parentTitleGenerator.generate(originalFilename, documentList)
                        : null;
                List<org.springframework.ai.document.Document> allChildren = new java.util.ArrayList<>();
                for (int i = 0; i < documentList.size(); i++) {
                    org.springframework.ai.document.Document parent = documentList.get(i);
                    String parentId = batch + "-" + seq + "-p" + i;
                    String title = (parentTitles != null && i < parentTitles.size()) ? parentTitles.get(i) : null;
                    List<org.springframework.ai.document.Document> children = splitToChildren(parent, parentId, tag, originalFilename, title);
                    // Phase 3 (C 方案 Contextual Retrieval)：给每个 child 添加上下文前缀拼到正文头部，
                    // 提升向量库 / BM25 召回率（论文 +49%）。注意：只改 child，parent 表里的内容保持原文干净，
                    // LLM 最终看到的是 resolveParents 拿到的 parent 原文，不被 prefix 污染。
                    if (contextualPrefixGenerator != null && !children.isEmpty()) {
                        // documentTitle 用 "原始文件名 + 第N段(title)" 给 LLM 当 anchor，比纯 fileName 信息量大
                        String anchor = (title != null && !title.isBlank())
                                ? originalFilename + " - " + title
                                : originalFilename;
                        contextualPrefixGenerator.generate(anchor, children);
                    }
                    allChildren.addAll(children);
                    parentDocumentService.store(parentId, parent.getText(), children, tag, originalFilename, userId, title);
                }
                // BM25 镜像索引用 child chunks（已带 contextual prefix）
                if (bm25SearchService != null) {
                    bm25SearchService.index(allChildren);
                }
            } else {
                // 非 parent-doc 模式：保留原有"给 documentList 整体加 prefix"的行为
                if (contextualPrefixGenerator != null) {
                    contextualPrefixGenerator.generate(originalFilename, documentList);
                }

                // 存储知识库文件（PgVector 承担语义向量索引）
                vectorStore.accept(documentList);

                // 同步镜像到 ES，为 BM25 关键词检索提供一致的数据源（亮点 3 混合检索）
                if (bm25SearchService != null) {
                    bm25SearchService.index(documentList);
                }
            }

            // 存储到数据库
            AiRagOrderVO aiRagOrderVO = new AiRagOrderVO();
            aiRagOrderVO.setRagId(batch + "-" + (seq++));
            aiRagOrderVO.setRagName(originalFilename);
            aiRagOrderVO.setKnowledgeTag(tag);
            aiRagOrderVO.setFileHash(fileHash);
            aiRagOrderVO.setFileSize(fileSize);
            aiRagOrderVO.setUserId(userId);
            repository.createTagOrder(aiRagOrderVO);
        }
    }

    /**
     * P2.3 12.4 将 parent 文档切分成更小的 child chunks（目标 ~450 chars/overlap ~50 chars）。
     * 复用 SemanticTextSplitter，避免把句子、段落和 Markdown 代码块从中间切开。
     * 每个 child 标记 parent_id，供后续 Parent Document Retriever 解析。
     */
    private List<Document> splitToChildren(Document parent, String parentId, String tag, String source) {
        return splitToChildren(parent, parentId, tag, source, null);
    }

    /**
     * 第 61 轮 Phase 2：带 title 的 child 切分。
     * title 注入 child metadata，让 BM25 路径回收的 Document 也能直接显示精炼标题，无需 join parent 表。
     */
    private List<Document> splitToChildren(Document parent, String parentId, String tag, String source, String title) {
        String text = parent.getText();
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = CHILD_TEXT_SPLITTER.splitTextToChunks(text);
        List<Document> children = new ArrayList<>();
        int seq = 0;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            Document child = new Document(chunk, new java.util.HashMap<>());
            child.getMetadata().put("parent_id", parentId);
            child.getMetadata().put("knowledge", tag);
            child.getMetadata().put("source", source);
            if (title != null && !title.isBlank()) child.getMetadata().put("title", title);
            child.getMetadata().put("child_seq", seq++);
            children.add(child);
        }
        return children;
    }

}
