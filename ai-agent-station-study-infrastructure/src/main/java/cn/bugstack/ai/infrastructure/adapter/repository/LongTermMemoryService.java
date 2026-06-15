package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.memory.MemoryTopics;
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

    /**
     * 累加 topic 的「重述 vs 互补」判定器（mem0/MemGPT 思路）。单值 topic 不用它（走确定性覆盖）。
     * 不走 Jaccard 预检（整句中文=单 token 恒失效）；LLM 不可达时 resolver 内部 fallback=KEEP_BOTH（不丢数据）。
     */
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

    @Value("${agent.memory.long-term.retrieve-similarity-threshold:0.0}")
    private double retrieveSimilarityThreshold;

    /**
     * 遗忘衰减基线（2026-06-07 重设计）：归档阈值 = baseDays + k(topic) × min(access_count, cap)。
     * 从未被召回的记忆（last_accessed IS NULL）从 created_at 起算，count=0 无热度加成，过 baseDays 即归档。
     */
    @Value("${agent.memory.long-term.decay-base-days:30}")
    private int decayBaseDays;

    /**
     * 热度宽限系数 k：access_count 经 touchAccess 的 1 天节流后≈"被召回的不同天数"，越热宽限越久。
     * 技能/偏好（耐久）给大 k 活得久，计划/情况（会过期）给小 k 早归档，其它/未知 topic 取中间值。
     */
    @Value("${agent.memory.long-term.decay-k-durable:20}")
    private int decayKDurable;
    @Value("${agent.memory.long-term.decay-k-ephemeral:5}")
    private int decayKEphemeral;
    @Value("${agent.memory.long-term.decay-k-default:10}")
    private int decayKDefault;

    /** 热度宽限上界：min(access_count, cap)，给宽限期一个有限天花板，杜绝旧 count 硬阈值的"永久免疫"。 */
    @Value("${agent.memory.long-term.decay-access-count-cap:50}")
    private int decayCap;

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
        InheritedAccessStats inheritedAccessStats = new InheritedAccessStats();

        // 1a. 同 topic 冲突解决，按 topic 类型分两路：
        //     单值 topic（画像/计划/情况）→ 确定性「保留最新」覆盖（封闭槽位只有一个真值，不调 LLM，抗降智）。
        //     累加 topic（技能/偏好）→ 逐条 LLM「重述 vs 互补」判定：新事实只替换"它重述的那一条"，
        //        其余互补事实保留（技能:Java「精通并发」与「精通SpringAI」共存）。LLM 不可达 → fallback=KEEP_BOTH。
        //        为何要 LLM：区分"换说法的同一条"与"同主题的不同事实"是语义任务，距离阈值/Jaccard 都做不到
        //        （两类的向量距离范围重叠；整句中文在 Jaccard 里=单 token 恒判不相似）。
        if (topic != null && !topic.isBlank()) {
            List<AiLongTermMemory> existing = dao.findByUserIdAndTopic(userId, topic, 3);
            if (existing != null && !existing.isEmpty()) {
                // exact-match 短路：内容完全相同时直接 SKIP（任何 topic 都适用，逐字相同不留两份）
                for (AiLongTermMemory em : existing) {
                    if (em.getContent() != null && em.getContent().trim().equalsIgnoreCase(content.trim())) {
                        log.info("ltm.conflict topic={} decision=SKIP (exact-match) existingMemoryId={}",
                                topic, em.getMemoryId());
                        return em.getMemoryId();
                    }
                }
                if (MemoryTopics.isSingular(topic)) {
                    // 非降级护栏（2026-05-31）：画像是稳定身份，latest-wins 分不清"真实变更（体重80→78/搬家）"和
                    // "抽取变水的降级（Java后端开发工程师→程序员）"。仅对画像类、且新值不长于旧值（疑似泛化）时，
                    // 用 LLM 判一次：判为真实变更(OVERWRITE)才覆盖；判为降级/重述则保留旧值、丢弃新值。
                    // LLM 不可达时 resolver 对单值 fallback=OVERWRITE → 退回原 latest-wins 行为，不回归。
                    if (topic.startsWith("画像") && conflictResolver != null) {
                        AiLongTermMemory latestOld = existing.get(0); // findByUserIdAndTopic 按 created_at DESC
                        String oldContent = latestOld.getContent() == null ? "" : latestOld.getContent().trim();
                        if (!oldContent.isBlank() && content.trim().length() <= oldContent.length()) {
                            IMemoryConflictResolver.Decision d =
                                    conflictResolver.resolve(topic, content, java.util.List.of(oldContent));
                            if (d != IMemoryConflictResolver.Decision.OVERWRITE) {
                                log.info("ltm.conflict topic={} decision=KEEP_OLD (singular anti-degrade) kept='{}' rejected='{}'",
                                        topic, abbr(oldContent), abbr(content));
                                return latestOld.getMemoryId();
                            }
                        }
                    }
                    // 单值槽位：归档全部同 topic 旧记忆、继承最高 access，保留最新一条（确定性，不调 LLM）
                    for (AiLongTermMemory old : existing) {
                        inheritedAccessStats.accept(old);
                        archive(old.getMemoryId());
                    }
                    log.info("ltm.conflict topic={} existingCount={} decision=OVERWRITE (singular latest-wins, deterministic)",
                            topic, existing.size());
                } else if (conflictResolver != null) {
                    // 累加槽位：逐条判定新事实是否重述了某条已有记忆。命中即只替换那一条，其余互补事实保留。
                    for (AiLongTermMemory ex : existing) {
                        if (ex.getContent() == null || ex.getContent().isBlank()) continue;
                        IMemoryConflictResolver.Decision d =
                                conflictResolver.resolve(topic, content, java.util.List.of(ex.getContent()));
                        if (d == IMemoryConflictResolver.Decision.SKIP) {
                            // 新事实已被这条已有事实覆盖，不另存
                            log.info("ltm.conflict topic={} decision=SKIP (cumulative, subsumed by {})",
                                    topic, ex.getMemoryId());
                            return ex.getMemoryId();
                        }
                        if (d == IMemoryConflictResolver.Decision.MERGE) {
                            // 合并进这条已有事实后仍落新记录：先归档旧向量/旧行，再用合并文本写入 MySQL + PgVector。
                            // 只 updateContent 会让 MySQL 是新文本、PgVector 仍是旧 embedding，后续语义召回会漂移。
                            inheritedAccessStats.accept(ex);
                            archive(ex.getMemoryId());
                            content = ex.getContent() + "\n" + content;
                            log.info("ltm.conflict topic={} decision=MERGE (cumulative, replace merged {})",
                                    topic, ex.getMemoryId());
                            break;
                        }
                        if (d == IMemoryConflictResolver.Decision.OVERWRITE) {
                            // 新事实是这条的重述/更新 → 归档它、继承 access，落新记录；其余条目不动
                            inheritedAccessStats.accept(ex);
                            archive(ex.getMemoryId());
                            log.info("ltm.conflict topic={} decision=OVERWRITE (cumulative, restated {})",
                                    topic, ex.getMemoryId());
                            break;
                        }
                        // KEEP_BOTH → ex 是互补事实，保留，继续检查下一条
                    }
                }
                // 累加且 conflictResolver==null：直接落新记录共存（KEEP_BOTH 语义）
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
                            inheritedAccessStats.accept(dao.findByMemoryId(oldMemoryId));
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
                .accessCount(inheritedAccessStats.accessCount)
                .lastAccessed(inheritedAccessStats.lastAccessed)
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
            req = retrieveSimilarityThreshold > 0
                    ? SearchRequest.builder()
                            .query(embeddingQuery)
                            .topK(topK)
                            .similarityThreshold(retrieveSimilarityThreshold)
                            .filterExpression(parsed)
                            .build()
                    : SearchRequest.builder()
                            .query(embeddingQuery)
                            .topK(topK)
                            .filterExpression(parsed)
                            .build();
        } catch (Exception e) {
            log.warn("ltm.retrieve filter parse failed, fallback to no-filter: {}", e.getMessage());
            req = retrieveSimilarityThreshold > 0
                    ? SearchRequest.builder().query(embeddingQuery).topK(topK)
                            .similarityThreshold(retrieveSimilarityThreshold).build()
                    : SearchRequest.builder().query(embeddingQuery).topK(topK).build();
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

    /** 日志用：截断 + 去换行，避免长内容刷屏。 */
    private static String abbr(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
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
        // 核心记忆 = 全部画像槽位（封闭清单约13个），给足上限确保全取
        if (coreN <= 0) coreN = 20;
        if (relevantK <= 0) relevantK = 5;

        LinkedHashMap<String, String> dedup = new LinkedHashMap<>();
        int coreCount = 0;

        // 1. 核心记忆 = 全部画像槽位（mapper 已按 topic, access_count DESC 排）。
        //    同一画像 topic 只注入一条（防御历史重复数据，留最热的那条），避免画像被冗余撑爆。
        List<AiLongTermMemory> core = dao.findCoreByUser(userId, coreN);
        if (core != null) {
            java.util.Set<String> seenCoreTopics = new java.util.HashSet<>();
            for (AiLongTermMemory m : core) {
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                if (m.getTopic() != null && !seenCoreTopics.add(m.getTopic())) continue;
                dedup.putIfAbsent(m.getContent(), m.getTopic());
                coreCount++;
            }
        }

        // 2. 语义相关记忆：向量相似度检索（失败不阻断，核心记忆已够用）
        if (query != null && !query.isBlank()) {
            try {
                List<Document> relevant = retrieveTopK(userId, query, relevantK);
                if (relevant != null) {
                    for (Document d : relevant) {
                        String content = d.getText();
                        if (content == null || content.isBlank()) continue;
                        String topic = (String) d.getMetadata().get("topic");
                        // 画像槽位已由核心记忆全量注入，语义路跳过，不占用相关记忆名额
                        if (topic != null && topic.startsWith("画像")) continue;
                        dedup.putIfAbsent(content, topic);
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
    public int runDecay(int limit) {
        if (limit <= 0 || limit > 500) limit = 100;
        int baseDays = decayBaseDays <= 0 ? 30 : decayBaseDays;
        int cap = decayCap <= 0 ? 50 : decayCap;

        List<AiLongTermMemory> candidates = dao.findStaleCandidates(
                baseDays, decayKDurable, decayKEphemeral, decayKDefault, cap, limit);
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

        log.info("ltm.decay archived baseDays={} kDurable={} kEphemeral={} kDefault={} cap={} archived={}/{}",
                baseDays, decayKDurable, decayKEphemeral, decayKDefault, cap, ids.size(), candidates.size());
        return ids.size();
    }

    private static class InheritedAccessStats {
        private int accessCount;
        private LocalDateTime lastAccessed;

        private void accept(AiLongTermMemory memory) {
            if (memory == null) return;
            int candidateCount = memory.getAccessCount() == null ? 0 : memory.getAccessCount();
            LocalDateTime candidateLastAccessed = memory.getLastAccessed();
            if (candidateCount > accessCount) {
                accessCount = candidateCount;
                lastAccessed = candidateLastAccessed;
                return;
            }
            if (candidateCount == accessCount && isAfter(candidateLastAccessed, lastAccessed)) {
                lastAccessed = candidateLastAccessed;
            }
        }

        private static boolean isAfter(LocalDateTime candidate, LocalDateTime current) {
            if (candidate == null) return false;
            return current == null || candidate.isAfter(current);
        }
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

    /**
     * 累加性主题：同一主题下可以有多个不同记忆共存（如 技能:Java + 技能:Python）。
     * 受控词表（V041）：
     *   累加（共存，不覆盖）= 技能:/偏好:（兼容旧 skill:/preference:）
     *   单值（新值覆盖旧值）= 画像:/计划:/情况:（兼容旧 fact:/decision:）
     *   未知主题默认按累加处理，避免误覆盖删除数据。
     */
    static boolean isCumulativeTopic(String topic) {
        // 统一委托共享分类法，避免与 LlmMemoryConflictResolver 各写一份导致漂移
        return MemoryTopics.isCumulative(topic);
    }

}
