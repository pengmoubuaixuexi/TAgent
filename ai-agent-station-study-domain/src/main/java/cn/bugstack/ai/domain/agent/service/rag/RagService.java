package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiRagOrderVO;
import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
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
 * @author TAgent
 * 2025/10/4 09:12
 */
@Slf4j
@Service
public class RagService implements IRagService {

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

            // P1.4 7.3 Contextual Retrieval：为每个 chunk 生成"在文档里讲什么"的前缀
            if (contextualPrefixGenerator != null) {
                contextualPrefixGenerator.generate(name, documentList);
            }

            // P2.3 12.4 Parent Document Retriever：两段式存储
            // parent（大块）存 MySQL，child（小块）存 PgVector；检索时换出 parent 喂 LLM
            if (parentDocumentService != null) {
                List<org.springframework.ai.document.Document> allChildren = new java.util.ArrayList<>();
                for (int i = 0; i < documentList.size(); i++) {
                    org.springframework.ai.document.Document parent = documentList.get(i);
                    String parentId = batch + "-" + seq + "-p" + i;
                    List<org.springframework.ai.document.Document> children = splitToChildren(parent, parentId, tag, originalFilename);
                    allChildren.addAll(children);
                    parentDocumentService.store(parentId, parent.getText(), children, tag, originalFilename, userId);
                }
                // BM25 镜像索引用 child chunks
                if (bm25SearchService != null) {
                    bm25SearchService.index(allChildren);
                }
            } else {
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
     * P2.3 12.4 将 parent 文档切分成更小的 child chunks（~400-500 chars/overlap ~50 chars）。
     * 每个 child 标记 parent_id，供后续 Parent Document Retriever 解析。
     */
    private List<Document> splitToChildren(Document parent, String parentId, String tag, String source) {
        String text = parent.getText();
        if (text == null || text.isBlank()) return List.of();
        int chunkSize = 450;
        int overlap = 50;
        List<Document> children = new ArrayList<>();
        int start = 0;
        int seq = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end);
            Document child = new Document(chunk, new java.util.HashMap<>());
            child.getMetadata().put("parent_id", parentId);
            child.getMetadata().put("knowledge", tag);
            child.getMetadata().put("source", source);
            child.getMetadata().put("child_seq", seq++);
            children.add(child);
            if (end >= text.length()) break;
            start = end - overlap;
        }
        return children;
    }

}
