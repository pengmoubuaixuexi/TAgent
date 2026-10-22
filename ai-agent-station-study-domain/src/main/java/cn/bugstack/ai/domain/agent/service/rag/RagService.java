package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiRagOrderVO;
import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.domain.agent.service.rag.splitter.SemanticTextSplitter;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Resource(name = "ragVectorStore")
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

    /** 每个 parent 一次模型调用：5-15 字小标题（写 title 列）+ 每个 child 的检索上下文 */
    @Autowired(required = false)
    private IParentEnrichmentGenerator enrichmentGenerator;

    /** 同时处理的 parent 数；每个 parent 依次做增强、ES、向量、parent 行写入。 */
    @Value("${agent.rag.enrichment.concurrency:4}")
    private int enrichmentConcurrency = 4;

    /** 索引写入格式变化时修改，未完成的旧任务重试时将不再复用已写入的 parent。 */
    private static final String INDEX_FORMAT_VERSION = "2026-10-21";

    private volatile ExecutorService parentPool;

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files) {
        storeRagFile(name, tag, files, null);
    }

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files, String userId) {
        storeRagFile(name, tag, files, userId, RagIngestionObserver.NONE);
    }

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files, String userId, RagIngestionObserver observer) {
        if (userId == null || userId.isBlank() || tag == null || tag.isBlank()) {
            throw new RagStorageException("上传知识库需要有效的用户和知识库标识。");
        }
        if (files == null || files.isEmpty()) throw new RagStorageException("请选择要上传的文件。");
        for (MultipartFile file : files) {
            observer.progress("VERIFYING", 5, 0);
            // P2.3 12.5 文件去重：计算 SHA-256，已存在则跳过
            String fileHash;
            long fileSize;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] bytes = file.getBytes();
                fileHash = HexFormat.of().formatHex(md.digest(bytes));
                fileSize = bytes.length;
            } catch (Exception e) {
                throw new RagStorageException("无法读取上传文件，未开始写入知识库。", e);
            }

            // Claude 修复：原版只算了 hash 没用，现在真正做幂等去重——
            // 命中已存在则跳过整个 ① 切片 ② Contextual prefix 生成（每 chunk 一次小模型）
            // ③ embedding ④ vectorStore 写入 ⑤ ES 索引 ⑥ MySQL 插入。重复上传同份文件不再烧钱
            if (fileHash != null && repository.existsRagFileByHashTagAndUser(fileHash, tag, userId)) {
                log.info("[RAG] 跳过重复文件 name='{}' size={} hash={}",
                        file.getOriginalFilename(), fileSize, fileHash);
                observer.progress("SUCCEEDED", 100, 0);
                continue;
            }

            // Stable across retry, scoped to owner and knowledge base; never merge two users' files.
            String fileId = RagFileScope.stableId(userId, tag, fileHash);

            observer.progress("PARSING", 10, 0);
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documentList = textSplitter.apply(documentReader.get());
            if (documentList.isEmpty() || documentList.stream().allMatch(d -> d.getText() == null || d.getText().isBlank())) {
                throw new RagStorageException("文件没有可提取的文本，请使用含文字的文件或先完成 OCR。");
            }

            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : name;

            // 添加知识库标签、原始文件名、userId
            documentList.forEach(doc -> {
                doc.getMetadata().put(RagFileScope.FILE_ID_KEY, fileId);
                doc.getMetadata().put("knowledge", tag);
                doc.getMetadata().put("source", originalFilename);
                if (userId != null && !userId.isBlank()) {
                    doc.getMetadata().put("user_id", userId);
                }
            });

            if (documentList.size() > observer.maxParents()) {
                throw new RagStorageException("文件切分后有 " + documentList.size() + " 个段落，超过单个文件上限 "
                        + observer.maxParents() + " 个，请拆分后再上传。");
            }

            // P2.3 12.4 Parent Document Retriever：两段式存储
            // parent（大块）存 MySQL，child（小块）存 PgVector；检索时换出 parent 喂 LLM
            if (parentDocumentService != null) {
                storeParents(fileId, tag, originalFilename, userId, documentList, observer);
            } else {
                // 非 parent-doc 模式：不支持断点续做，重试时由调用方清掉上次的片段
                observer.prepare(fingerprint("flat", documentList.stream()
                        .map(doc -> new ParentBlock("", doc.getText(), List.of())).toList()));
                // 保留原有"给 documentList 整体加 prefix"的行为
                if (contextualPrefixGenerator != null) {
                    contextualPrefixGenerator.generate(originalFilename, documentList);
                }

                // 存储知识库文件（PgVector 承担语义向量索引）
                List<Document> stableDocuments = new ArrayList<>(documentList.size());
                for (int i = 0; i < documentList.size(); i++) {
                    Document doc = documentList.get(i);
                    stableDocuments.add(new Document(stableChunkId(fileId + ":" + i), doc.getText(), doc.getMetadata()));
                }
                observer.progress("VECTORIZING", 40, stableDocuments.size());
                vectorStore.accept(stableDocuments);

                // 同步镜像到 ES，为 BM25 关键词检索提供一致的数据源（亮点 3 混合检索）
                if (bm25SearchService != null) {
                    observer.progress("INDEXING", 85, stableDocuments.size());
                    bm25SearchService.index(stableDocuments);
                }
            }

            // 存储到数据库
            observer.progress("PUBLISHING", 95, -1);
            AiRagOrderVO aiRagOrderVO = new AiRagOrderVO();
            aiRagOrderVO.setRagId(fileId);
            aiRagOrderVO.setRagName(originalFilename);
            aiRagOrderVO.setKnowledgeTag(tag);
            aiRagOrderVO.setFileHash(fileHash);
            aiRagOrderVO.setFileSize(fileSize);
            aiRagOrderVO.setUserId(userId);
            repository.createTagOrder(aiRagOrderVO);
            observer.progress("SUCCEEDED", 100, -1);
        }
    }

    private record ParentBlock(String parentId, String text, List<Document> children) { }

    /**
     * 切出全部 parent / child 后按 parent 并发处理：增强（一次模型调用）→ ES → PgVector → MySQL parent 行。
     * parent 行最后写入，作为该 parent 已完整入库的标记；重试且指纹一致时跳过这些 parent。
     */
    private void storeParents(String fileId, String tag, String source, String userId,
                              List<Document> parents, RagIngestionObserver observer) {
        List<ParentBlock> blocks = new ArrayList<>(parents.size());
        Map<String, String> texts = new LinkedHashMap<>();
        for (int i = 0; i < parents.size(); i++) {
            String parentId = fileId + "-p" + i;
            String text = parents.get(i).getText();
            List<Document> children = splitToChildren(text, parentId, tag, source);
            for (Document child : children) {
                child.getMetadata().put(RagFileScope.FILE_ID_KEY, fileId);
                child.getMetadata().put("user_id", userId);
            }
            blocks.add(new ParentBlock(parentId, text, children));
            texts.put(parentId, text);
        }
        String strategy = enrichmentGenerator == null ? "none" : enrichmentGenerator.strategy();
        boolean titleRequired = enrichmentGenerator != null && enrichmentGenerator.titleRequired();
        // 增强失败（无标题）的 parent 不算完成：修好模型后重试时会重新增强，而不是沿用降级结果。
        Set<String> done = observer.prepare(fingerprint(strategy, blocks))
                ? parentDocumentService.completedParents(texts, userId, tag).entrySet().stream()
                    .filter(parent -> !titleRequired || parent.getValue() != null)
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                : Set.of();
        List<ParentBlock> pending = blocks.stream().filter(block -> !done.contains(block.parentId())).toList();
        int finishedChildren = blocks.stream().filter(block -> done.contains(block.parentId()))
                .mapToInt(block -> block.children().size()).sum();
        if (!done.isEmpty()) log.info("[RAG] resume file={} skip {} completed parents of {}", fileId, done.size(), blocks.size());
        observer.progress("ENRICHING", progress(done.size(), blocks.size()), finishedChildren);
        if (pending.isEmpty()) return;

        Map<String, String> context = MDC.getCopyOfContextMap();
        EnrichmentOutcome outcome = new EnrichmentOutcome();
        AtomicBoolean abort = new AtomicBoolean();
        Set<Thread> running = ConcurrentHashMap.newKeySet();
        ExecutorCompletionService<ParentBlock> completion = new ExecutorCompletionService<>(parentPool());
        List<Future<ParentBlock>> futures = new ArrayList<>(pending.size());
        for (ParentBlock block : pending) {
            futures.add(completion.submit(() -> {
                if (abort.get()) return block;
                Map<String, String> previous = MDC.getCopyOfContextMap();
                running.add(Thread.currentThread());
                try {
                    // 预算、用量和归属都按 MDC 里的 runId / userId 查找，必须带到工作线程。
                    if (context == null) MDC.clear(); else MDC.setContextMap(context);
                    if (!abort.get()) storeParent(block, tag, source, userId, outcome);
                    return block;
                } finally {
                    running.remove(Thread.currentThread());
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            }));
        }

        Throwable failure = null;
        try {
            for (int finished = 1; finished <= pending.size(); finished++) {
                finishedChildren += completion.take().get().children().size();
                observer.progress("ENRICHING", progress(done.size() + finished, blocks.size()), finishedChildren);
            }
        } catch (InterruptedException interrupted) {
            failure = new CancellationException("Ingestion cancelled");
        } catch (ExecutionException error) {
            failure = error.getCause() == null ? error : error.getCause();
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        if (failure == null) {
            int degraded = outcome.degraded.get();
            if (degraded > 0) {
                String reason = outcome.unavailable.get();
                observer.warn("已入库，但有 " + degraded + "/" + blocks.size() + " 个段落未生成标题和上下文（"
                        + (reason == null ? "模型调用失败" : reason + "，请联系管理员检查入库增强模型配置")
                        + "）。检索仍可用，效果可能稍差；问题修复后可删除并重新上传。");
            }
            return;
        }

        // 先让其余 parent 停下，并等所有已开始的 parent 离开存储调用，避免与随后的删除或重试并发写入。
        abort.set(true);
        running.forEach(Thread::interrupt);
        boolean interrupted = failure instanceof CancellationException;
        for (Future<ParentBlock> future : futures) {
            while (true) {
                try { future.get(); break; }
                catch (InterruptedException again) { interrupted = true; running.forEach(Thread::interrupt); }
                catch (ExecutionException | CancellationException ignored) { break; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new RagStorageException("入库未完成，可能有部分片段已写入；原件已保留，请重试或删除。", failure);
    }

    /** 同一文件内各 parent 共享：增强模型整体不可用后，其余 parent 直接降级，不再发请求。 */
    private static final class EnrichmentOutcome {
        final java.util.concurrent.atomic.AtomicReference<String> unavailable = new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicInteger degraded = new AtomicInteger();
    }

    private void storeParent(ParentBlock block, String tag, String source, String userId, EnrichmentOutcome outcome) {
        List<Document> children = block.children();
        IParentEnrichmentGenerator.Enrichment enrichment;
        if (enrichmentGenerator == null) {
            enrichment = IParentEnrichmentGenerator.Enrichment.none(children.size());
        } else if (outcome.unavailable.get() != null) {
            enrichment = IParentEnrichmentGenerator.Enrichment.failed(children.size());
        } else {
            try {
                enrichment = enrichmentGenerator.enrich(source, children);
            } catch (IParentEnrichmentGenerator.UnavailableException unavailable) {
                if (outcome.unavailable.compareAndSet(null, unavailable.getMessage())) {
                    log.warn("[RAG] enrichment unavailable, remaining parents skip the model: {}", unavailable.getMessage(), unavailable);
                }
                enrichment = IParentEnrichmentGenerator.Enrichment.failed(children.size());
            }
        }
        if (enrichment.degraded()) outcome.degraded.incrementAndGet();
        String title = enrichment.title();
        List<Document> enriched = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            Document child = children.get(i);
            // title 注入 child metadata，让 BM25 路径回收的 child 也能直接显示精炼标题
            if (title != null) child.getMetadata().put("title", title);
            String prefix = i < enrichment.contexts().size() ? enrichment.contexts().get(i) : null;
            // Contextual Retrieval：前缀只拼到 child 参与向量化 / BM25；parent 原文保持干净，LLM 最终看到的是 parent
            enriched.add(prefix == null ? child
                    : child.mutate().text("【上下文】" + prefix + "\n\n【正文】" + child.getText()).build());
        }
        if (bm25SearchService != null) bm25SearchService.index(enriched);
        parentDocumentService.store(block.parentId(), block.text(), enriched, tag, source, userId, title);
    }

    private static int progress(int completedParents, int totalParents) {
        return 20 + (int) (75L * completedParents / Math.max(1, totalParents));
    }

    /** 同一份文件、同样的切分结果和增强策略才复用上次写入的 parent。 */
    private static String fingerprint(String strategy, List<ParentBlock> blocks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((INDEX_FORMAT_VERSION + "|" + strategy + "\u0000").getBytes(StandardCharsets.UTF_8));
            for (ParentBlock block : blocks) {
                digest.update((block.parentId() + "\u0000" + block.text() + "\u0000").getBytes(StandardCharsets.UTF_8));
                for (Document child : block.children()) {
                    digest.update((child.getId() + "\u0000" + child.getText() + "\u0000").getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ExecutorService parentPool() {
        ExecutorService pool = parentPool;
        if (pool != null) return pool;
        synchronized (this) {
            if (parentPool == null) {
                AtomicInteger seq = new AtomicInteger();
                parentPool = Executors.newFixedThreadPool(Math.max(1, Math.min(16, enrichmentConcurrency)), task -> {
                    Thread thread = new Thread(task, "rag-parent-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return parentPool;
        }
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService pool = parentPool;
        if (pool != null) pool.shutdownNow();
    }

    /**
     * P2.3 12.4 将 parent 文档切分成更小的 child chunks（目标 ~450 chars/overlap ~50 chars）。
     * 复用 SemanticTextSplitter，避免把句子、段落和 Markdown 代码块从中间切开。
     * 每个 child 标记 parent_id，供后续 Parent Document Retriever 解析。
     */
    private List<Document> splitToChildren(String text, String parentId, String tag, String source) {
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = CHILD_TEXT_SPLITTER.splitTextToChunks(text);
        List<Document> children = new ArrayList<>();
        int seq = 0;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            Document child = new Document(stableChunkId(parentId + ":" + seq), chunk, new java.util.HashMap<>());
            child.getMetadata().put("parent_id", parentId);
            child.getMetadata().put("knowledge", tag);
            child.getMetadata().put("source", source);
            child.getMetadata().put("child_seq", seq++);
            children.add(child);
        }
        return children;
    }

    private static String stableChunkId(String identity) {
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

}
