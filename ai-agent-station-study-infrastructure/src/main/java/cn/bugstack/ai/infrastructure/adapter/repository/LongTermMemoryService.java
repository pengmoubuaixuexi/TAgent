package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.memory.conflict.IMemoryConflictResolver;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.infrastructure.dao.IAiLongTermMemoryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiLongTermMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Long-Term Memory 实现（P1.7）。
 * <p>
 * 元数据落 MySQL（{@code ai_long_term_memory}），向量数据复用现有 PgVectorStore（{@code vector_store_openai}），
 * 通过 {@code metadata.type='long_term_memory'} + {@code user_id} 隔离。
 * <p>
 * 选 type tag 而非独立向量表的理由：
 * <ul>
 *   <li>现有 PgVectorStore 已稳定运行，加索引/迁移工作量为 0</li>
 *   <li>Spring AI 的 filterExpression 原生支持 metadata 等值过滤</li>
 *   <li>万一后续要拆，加新 PgVectorStore bean + 数据迁移脚本即可，业务接口不变</li>
 * </ul>
 */
@Slf4j
@Service
public class LongTermMemoryService implements ILongTermMemoryService {

    /** LTM 专用向量表，与 RAG 物理隔离；延迟初始化避免循环依赖 */
    private volatile org.springframework.ai.vectorstore.pgvector.PgVectorStore ltmStore;

    @Resource
    private IAiLongTermMemoryDao dao;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Qualifier("pgVectorJdbcTemplate")
    private org.springframework.jdbc.core.JdbcTemplate pgJdbc;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Qualifier("embeddingModel")
    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    private org.springframework.ai.vectorstore.pgvector.PgVectorStore getVectorStore() {
        if (ltmStore == null) {
            synchronized (this) {
                if (ltmStore == null) {
                    this.ltmStore = org.springframework.ai.vectorstore.pgvector.PgVectorStore
                            .builder(pgJdbc, embeddingModel)
                            .vectorTableName("ltm_memory_store")
                            .build();
                    log.info("[LTM] initialized dedicated vector table: ltm_memory_store");
                }
            }
        }
        return ltmStore;
    }

    /** P2.1 10.3 冲突解决器；为 null 时跳过冲突检查直接保存 */
    @Autowired(required = false)
    private IMemoryConflictResolver conflictResolver;

    @Value("${agent.memory.profile-max:64}")
    private int profileMax;

    /**
     * Embedding 服务对单条 input 有 token 上限。长期记忆检索只需要语义 query，
     * 不应该把 Auto/Flow 最终汇总阶段的大 prompt、工具结果全文都送去向量化。
     */
    @Value("${agent.memory.embedding-query-max-chars:6000}")
    private int embeddingQueryMaxChars;

    /**
     * 全局语义去重总开关。打开后，save() 在写入前用新 content 在向量库 top1 找最近邻，
     * 距离低于阈值视为同一事实，archive 旧记录后写入新记录（PG 删旧 + MySQL archived=1 + 新 insert）。
     * 关闭后退回原 LLM 冲突解决路径。
     */
    @Value("${agent.memory.long-term.semantic-dedupe-enabled:true}")
    private boolean semanticDedupeEnabled;

    /** 唯一性 topic（fact/preference/interest）的覆盖阈值：余弦距离 &lt; 0.15 即视为同事实，覆盖 */
    @Value("${agent.memory.long-term.semantic-dedupe-threshold-unique:0.15}")
    private double semanticDedupeThresholdUnique;

    /**
     * 累加性 topic（skill/decision）的覆盖阈值：更严的 0.10。
     * skill:Java vs skill:Python 这种同前缀不同实体距离常在 0.25-0.40，不会被误覆盖；
     * skill:Java vs skill:Java 不同表达通常 &lt; 0.08 会被覆盖。
     */
    @Value("${agent.memory.long-term.semantic-dedupe-threshold-cumulative:0.10}")
    private double semanticDedupeThresholdCumulative;

    /** 写入向量库时统一打的 type tag，filterExpression 走它 */
    static final String META_TYPE = "long_term_memory";

    @Override
    public String save(String userId, String content, String topic, String source, String sessionId) {
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) {
            log.warn("ltm.save skip: empty userId or content");
            return null;
        }
        if (topic != null) topic = topic.toLowerCase();
        String memoryId = UUID.randomUUID().toString();

        // 1a. P2.1 10.3 冲突解决：检查同 topic 旧记忆
        if (conflictResolver != null && topic != null && !topic.isBlank()) {
            List<AiLongTermMemory> existing = dao.findByUserIdAndTopic(userId, topic, 3);
            if (existing != null && !existing.isEmpty()) {
                List<String> existingContents = existing.stream()
                        .map(AiLongTermMemory::getContent)
                        .collect(Collectors.toList());

                // exact-match 短路：内容完全相同时直接 SKIP，不走 LLM 冲突解决
                for (AiLongTermMemory em : existing) {
                    if (em.getContent() != null && em.getContent().trim().equalsIgnoreCase(content.trim())) {
                        log.info("ltm.conflict topic={} decision=SKIP (exact-match, no LLM call) existingMemoryId={}",
                                topic, em.getMemoryId());
                        return em.getMemoryId();
                    }
                }

                // 相似度预检：仅对累加性主题（skill/decision）做短路，唯一性主题（role/location/preference）必须走 LLM
                boolean anySimilar = false;
                if (isCumulativeTopic(topic)) {
                    for (String ec : existingContents) {
                        if (ec != null && jaccardSimilarity(content, ec) >= 0.25) {
                            anySimilar = true;
                            break;
                        }
                    }
                } else {
                    // 唯一性主题始终走 LLM 判定
                    anySimilar = true;
                }
                IMemoryConflictResolver.Decision decision;
                if (!anySimilar) {
                    decision = IMemoryConflictResolver.Decision.KEEP_BOTH;
                    log.info("ltm.conflict topic={} existingCount={} decision={} (similarity pre-gate, no LLM call)",
                            topic, existing.size(), decision);
                } else {
                    decision = conflictResolver.resolve(topic, content, existingContents);
                    log.info("ltm.conflict topic={} existingCount={} decision={}", topic, existing.size(), decision);
                }

                switch (decision) {
                    case OVERWRITE -> {
                        // 归档所有旧记忆
                        for (AiLongTermMemory old : existing) {
                            archive(old.getMemoryId());
                        }
                    }
                    case MERGE -> {
                        // 保留最近一条，把新旧内容合并
                        AiLongTermMemory newest = existing.get(0);
                        String merged = newest.getContent() + "\n" + content;
                        dao.updateContent(newest.getMemoryId(), merged);
                        // 归档其他旧记忆
                        for (int i = 1; i < existing.size(); i++) {
                            archive(existing.get(i).getMemoryId());
                        }
                        // 合并后跳过新 save（已合并到旧记录）
                        return memoryId;
                    }
                    case SKIP -> {
                        // 新内容已覆盖，不保存
                        return null;
                    }
                    case KEEP_BOTH -> { /* fall through - 保存新记录 */ }
                }
            }
        }

        // 1b. 全局语义去重（跨 topic）：用新 content 在 PgVector 找 top1 最近邻，距离低于阈值 → 覆盖。
        //     - 唯一性 topic 阈值 0.15（"月薪15000元" vs "用户月薪15000元" distance≈0.05 → 覆盖）
        //     - 累加性 topic 阈值 0.10（避免 skill:Java vs skill:Python distance≈0.30 被误覆盖）
        //     1a 已经处理同 topic 内的冲突；这一步主要捕获跨 topic 同事实
        //     （如 fact:monthly_savings_goal vs decision:monthly_investment 都是"每月存5000投基金"）。
        //     用 retrieveTopKInternal(touchOnHit=false) 避免污染 access_count 热度统计。
        if (semanticDedupeEnabled) {
            try {
                List<Document> nearest = retrieveTopKInternal(userId, content, 1, false);
                if (nearest != null && !nearest.isEmpty()) {
                    Document top = nearest.get(0);
                    Double distance = extractDistance(top);
                    double threshold = getDedupeThreshold(topic);
                    if (distance != null && distance < threshold) {
                        String oldMemoryId = (String) top.getMetadata().get("memory_id");
                        String oldTopic = (String) top.getMetadata().get("topic");
                        log.info("ltm.semantic-dedupe userId={} oldTopic={} newTopic={} distance={} threshold={} → OVERWRITE oldMemoryId={}",
                                userId, oldTopic, topic, String.format("%.4f", distance), threshold, oldMemoryId);
                        if (oldMemoryId != null && !oldMemoryId.isBlank()) {
                            archive(oldMemoryId);  // PG delete + MySQL archived=1
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("ltm.semantic-dedupe failed, continuing to insert: {}", e.getMessage());
            }
        }

        // 1c. 元数据落 MySQL（即使后面 getVectorStore().accept 失败，行内也有原始记录可补救）
        String tenantId = MDC.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        AiLongTermMemory po = AiLongTermMemory.builder()
                .memoryId(memoryId)
                .userId(userId)
                .tenantId(tenantId)
                .topic(topic)
                .content(content)
                .source(source == null ? "auto" : source)
                .sourceSession(sessionId)
                .accessCount(0)
                .lastAccessed(null)
                .archived(0)
                .build();
        dao.insert(po);

        // 2. 向量库写入；metadata 写全 type / user_id / memory_id / topic / archived 让 filterExpression 能精准过滤
        Map<String, Object> meta = new HashMap<>();
        meta.put("type", META_TYPE);
        meta.put("user_id", userId);
        meta.put("memory_id", memoryId);
        meta.put("archived", 0);
        if (topic != null) meta.put("topic", topic);

        Document doc = new Document(memoryId, content, meta);
        try {
            getVectorStore().accept(List.of(doc));
        } catch (Exception e) {
            log.error("[LTM] PgVector write FAILED memoryId={} table=ltm_memory_store: {}", memoryId, e.getMessage());
            // MySQL 已写入，PgVector 失败不阻断（后续可通过 MySQL 修复）
        }

        log.info("ltm.save OK memoryId={} userId={} topic={} contentLen={}",
                memoryId, userId, topic, content.length());
        return memoryId;
    }

    @Override
    public List<Document> retrieveTopK(String userId, String query, int topK) {
        return retrieveTopKInternal(userId, query, topK, true);
    }

    /**
     * @param touchOnHit 是否给命中文档累加 access_count。
     *                   读路径（advisor 注入）应该 true，
     *                   save() 内部去重检查应该 false 避免污染热度统计。
     */
    private List<Document> retrieveTopKInternal(String userId, String query, int topK, boolean touchOnHit) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        if (topK <= 0) topK = 4;
        String embeddingQuery = limitEmbeddingQuery(query);

        // PgVector 的 filter parser 接受字符串表达式；用单引号包字面量避免注入
        // 注意：不加 archived 过滤，因为旧记录没有该 metadata 字段会被误排除
        // 归档记录的过滤由下方 MySQL 交叉校验兜底
        String filterExpr = "type == 'long_term_memory' && user_id == '"
                + userId.replace("'", "") + "'";

        SearchRequest req;
        try {
            Filter.Expression parsed = new FilterExpressionTextParser().parse(filterExpr);
            req = SearchRequest.builder()
                    .query(embeddingQuery)
                    .topK(topK)
                    .filterExpression(parsed)
                    .build();
        } catch (Exception e) {
            log.warn("ltm.retrieve filter parse failed, fallback to no-filter: {}", e.getMessage());
            req = SearchRequest.builder().query(embeddingQuery).topK(topK).build();
        }

        List<Document> docs = getVectorStore().similaritySearch(req);
        if (docs == null) return Collections.emptyList();

        // 二次校验：交叉参考 MySQL，过滤掉 archived=1 的记录（防御 PgVector 未同步）
        java.util.Set<String> activeMemIds = new java.util.HashSet<>(dao.findActiveMemoryIds(userId));
        docs = docs.stream()
                .filter(d -> {
                    Object mid = d.getMetadata().get("memory_id");
                    return mid != null && activeMemIds.contains(mid.toString());
                })
                .collect(Collectors.toList());

        // 命中即累计访问统计；批量更新而非循环查询，避免 N+1
        // 当前没有 batch update SQL，循环 dao.touchAccess（对常见 topK <= 8 可接受）
        if (touchOnHit) {
            for (Document d : docs) {
                Object mid = d.getMetadata().get("memory_id");
                if (mid != null) {
                    try { dao.touchAccess(mid.toString()); } catch (Exception ignored) {}
                }
            }
        }
        log.info("ltm.retrieve userId={} query='{}' queryChars={} embeddingQueryChars={} hits={} touchOnHit={}",
                userId, abbreviate(embeddingQuery, 40), query.length(), embeddingQuery.length(), docs.size(), touchOnHit);
        return docs;
    }

    /**
     * 拿 PgVector RowMapper 写到 Document.metadata 的 distance（spring-ai 1.0.0：
     * {@code DocumentMetadata.DISTANCE.value() = "distance"}，Float 类型，余弦距离 0~1）。
     * Fallback：如果 metadata 没有 distance（自定义版本），用 {@code 1.0 - getScore()} 反推。
     */
    private static Double extractDistance(Document doc) {
        if (doc == null) return null;
        Object v = doc.getMetadata() == null ? null : doc.getMetadata().get("distance");
        if (v instanceof Number n) return n.doubleValue();
        Double score = doc.getScore();
        if (score != null) return 1.0 - score;
        return null;
    }

    private double getDedupeThreshold(String topic) {
        return isCumulativeTopic(topic)
                ? semanticDedupeThresholdCumulative
                : semanticDedupeThresholdUnique;
    }

    @Override
    public void archive(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) return;
        // 先删 PgVector，再标 MySQL；PgVector 失败则 MySQL 不动，避免孤立向量
        try {
            getVectorStore().delete(List.of(memoryId));
        } catch (Exception e) {
            log.error("ltm.archive vector delete FAILED memoryId={}: {}", memoryId, e.getMessage());
            return; // 不标 MySQL，下次 archive 重试
        }
        dao.archive(memoryId);
    }

    @Override
    public List<String> retrieveProfile(String userId) {
        if (userId == null || userId.isBlank()) return Collections.emptyList();
        List<AiLongTermMemory> rows = dao.findByUserId(userId, profileMax);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        return rows.stream()
                .map(AiLongTermMemory::getContent)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toList());
    }

    @Override
    public List<String> retrieveForInjection(String userId, String query, int coreN, int relevantK) {
        if (userId == null || userId.isBlank()) return Collections.emptyList();
        if (coreN <= 0) coreN = 5;
        if (relevantK <= 0) relevantK = 5;

        LinkedHashMap<String, String> dedup = new LinkedHashMap<>();
        int coreCount = 0;

        // 1. 核心记忆：按 access_count * 10 + recency_bonus 排序
        List<AiLongTermMemory> core = dao.findCoreByUser(userId, coreN);
        if (core != null) {
            for (AiLongTermMemory m : core) {
                if (m.getContent() != null && !m.getContent().isBlank()) {
                    dedup.putIfAbsent(m.getContent(), m.getTopic());
                    coreCount++;
                }
            }
        }

        // 2. 语义相关记忆：向量相似度检索（失败不阻断，核心记忆已够用）
        if (query != null && !query.isBlank()) {
            try {
                List<Document> relevant = retrieveTopK(userId, query, relevantK);
                if (relevant != null) {
                    for (Document d : relevant) {
                        String content = d.getText();
                        if (content != null && !content.isBlank()) {
                            dedup.putIfAbsent(content, (String) d.getMetadata().get("topic"));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("ltm.retrieveForInjection vector search failed: {}", e.getMessage());
            }
        }

        // 3. 格式化为 "[topic] content" 便于 advisor 展示
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : dedup.entrySet()) {
            String topic = e.getValue();
            if (topic != null && !topic.isBlank()) {
                result.add("[" + topic + "] " + e.getKey());
            } else {
                result.add(e.getKey());
            }
        }

        int relevantCount = dedup.size() - coreCount;
        log.info("ltm.retrieveForInjection userId={} core={} relevant={} total={}",
                userId, coreCount, Math.max(0, relevantCount), result.size());
        return result;
    }

    @Override
    public int runDecay(int staleDays, int minAccess, int limit) {
        if (staleDays <= 0) staleDays = 30;
        if (minAccess <= 0) minAccess = 3;
        if (limit <= 0 || limit > 500) limit = 100;

        List<AiLongTermMemory> candidates = dao.findStaleCandidates(staleDays, minAccess, limit);
        if (candidates == null || candidates.isEmpty()) {
            log.debug("ltm.decay: no stale candidates found");
            return 0;
        }

        List<Long> ids = new java.util.ArrayList<>();
        List<String> memIds = new java.util.ArrayList<>();
        for (AiLongTermMemory m : candidates) {
            ids.add(m.getId());
            memIds.add(m.getMemoryId());
        }

        // 批量归档
        dao.archiveByIds(ids);

        // 向量库删除：逐个删，失败不阻断
        for (String memId : memIds) {
            try { getVectorStore().delete(List.of(memId)); } catch (Exception ignored) {}
        }

        log.info("ltm.decay archived staleDays={} minAccess={} archived={}/{}",
                staleDays, minAccess, ids.size(), candidates.size());
        return ids.size();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String limitEmbeddingQuery(String query) {
        if (query == null) return null;
        int max = embeddingQueryMaxChars <= 0 ? 6000 : embeddingQueryMaxChars;
        if (query.length() <= max) {
            return query;
        }
        int head = Math.max(1000, max * 2 / 3);
        int tail = Math.max(500, max - head);
        if (head + tail >= query.length()) {
            return query.substring(0, max);
        }
        String limited = query.substring(0, head)
                + "\n\n...（长期记忆检索 query 过长，已省略中间内容）...\n\n"
                + query.substring(query.length() - tail);
        log.info("ltm.embeddingQuery truncated chars {} -> {}", query.length(), limited.length());
        return limited;
    }

    /** 累加性主题（skill/decision + 常见技术名）：同一主题下可以有多个不同记忆，如 skill:Java + skill:Python */
    static boolean isCumulativeTopic(String topic) {
        if (topic == null) return true;
        String t = topic.toLowerCase();
        if (t.startsWith("skill") || t.startsWith("decision")) return true;
        // 无前缀但明显是技术/工具名 → 按累加处理
        return COMMON_SKILL_TOPICS.contains(t);
    }

    /** 常见的技能/技术 topic 名称（LLM 可能不按 category:subject 格式产出） */
    private static final java.util.Set<String> COMMON_SKILL_TOPICS = java.util.Set.of(
            "python", "java", "javascript", "typescript", "go", "rust", "c++", "c#",
            "ruby", "php", "swift", "kotlin", "scala", "r", "matlab", "dart",
            "django", "fastapi", "flask", "spring", "spring_boot", "springboot",
            "react", "vue", "angular", "node.js", "nodejs", "express",
            "postgresql", "mysql", "redis", "mongodb", "elasticsearch", "kafka",
            "docker", "kubernetes", "aws", "azure", "gcp",
            "microservice_development", "microservices", "backend_development",
            "frontend_development", "devops", "machine_learning", "data_science"
    );

    /** Jaccard 相似度：词集合的交集大小 / 并集大小，用于预检两段内容是否"说的是同一件事" */
    static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        java.util.Set<String> setA = tokenize(a);
        java.util.Set<String> setB = tokenize(b);
        if (setA.isEmpty() && setB.isEmpty()) return 0.0;
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static java.util.Set<String> tokenize(String s) {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (String word : s.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fff]+")) {
            if (!word.isBlank() && word.length() > 1) {
                tokens.add(word);
            }
        }
        return tokens;
    }
}
