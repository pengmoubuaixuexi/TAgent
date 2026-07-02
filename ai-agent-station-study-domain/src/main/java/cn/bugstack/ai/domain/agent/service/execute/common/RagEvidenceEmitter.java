package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H1 (F7) RAG 答案引用 — 后端证据片段 emitter。
 * <p>
 * <b>目的</b>：让用户在答案展开过程中看到"本轮检索到的依据片段"，从"看起来像编的"变成可信引用。
 * 复用 G1-A {@link ApprovalChannelRegistry} 通道（同 H3 套路），零侵入现有 SSE 流。
 * <p>
 * <b>SSE 事件契约</b>（按 Codex 第 56 轮约定，对齐 H3 / G1 同款 event/data 二行格式）：
 * <pre>
 * event: rag_evidence
 * data: {"sessionId":"...","items":[{"ref":1,"source":"...","snippet":"..."},...],"timestamp":...}
 * </pre>
 * <p>
 * <b>字段语义</b>：
 * <ul>
 *   <li>{@code ref}: 1-based，跟 {@code qa_citation_map} 编号对齐，模型答案里的 [1][2] 可对应</li>
 *   <li>{@code source}: 优先 {@code source}（原始文件名/URL）→ {@code file_name / title}
 *       → {@code knowledge}（业务知识库标签兜底）→ url/link/doc_id → Document.id → {@code doc-N}</li>
 *   <li>{@code snippet}: Document.text 全文（多空白归一化为单空格）。
 *       不再额外截断 —— ingest 端入向量库前已经按 chunkSize 切过，单 chunk 本就不长</li>
 * </ul>
 * <p>
 * <b>边界保证</b>（Codex 第 56 轮 4 条）：
 * <ol>
 *   <li>无检索文档（empty 或 null）→ 不 emit 事件，前端零噪音</li>
 *   <li>snippet 不再做二次截断（依赖 ingest 端 chunk 已经控长）</li>
 *   <li>不把大 metadata 原样透出，只挑 source 这一字段</li>
 *   <li>所有异常吞掉，advisor 失败 ≠ 主回答失败</li>
 * </ol>
 */
@Slf4j
@Component
public class RagEvidenceEmitter {

    /**
     * source 字段从 metadata 的这些 key 顺序找，第一个非空即用。
     * <p>第 59 轮调整：把 ingest 实际写入的 {@code source}（RagService.line 95 / ParentDocumentService.line 53）
     * 提到最前，并加 {@code knowledge} 作为业务兜底 —— 当 BM25 路径 metadata 丢失 source 时，
     * 至少能显示"标准深蹲动作要领"这种业务标签，而不是 UUID。</p>
     */
    private static final String[] SOURCE_METADATA_KEYS = {
            // Phase 2 给 parent 生成的 LLM 小标题最高优先级（"标准深蹲膝盖位置" 这种 5-15 字精炼描述）
            "title",
            // 业务实写：ingest 时塞的原始文件名
            "source", "file_name",
            // 业务兜底：知识库标签（"标准深蹲动作要领"），新数据没 title 时也能显示业务可读名
            "knowledge",
            // URL 类
            "url", "document_url", "page_url", "link",
            // 内部 id
            "doc_id", "source_id"
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private ApprovalChannelRegistry approvalChannelRegistry;

    /** Spring 注入路径；测试可用此构造手动装配。 */
    public RagEvidenceEmitter() {}

    public RagEvidenceEmitter(ApprovalChannelRegistry registry) {
        this.approvalChannelRegistry = registry;
    }

    /**
     * 派生 snippets 并 emit `rag_evidence` SSE。
     *
     * @param sessionId 当前会话 ID（从 MDC / advisor context 拿）；null/blank → 静默跳过
     * @param documents 检索到的最终文档列表（已经过 fusion/rerank/parent-doc 处理）；null/empty → 不 emit
     */
    public void emitEvidence(String sessionId, List<Document> documents) {
        emitEvidence(sessionId, documents, 1);
    }

    /**
     * 第 61 轮新增：带全局 startRef 的版本。
     * <p>多 step 场景下每次 advisor 触发 RAG 都用累加 ref（[1][2] → [3][4] → [5][6]），
     * 前端 append 渲染时直接按 ref 排序，模型答案里的 [N] 和卡片的第 N 条对齐。</p>
     */
    public void emitEvidence(String sessionId, List<Document> documents, int startRef) {
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            if (documents == null || documents.isEmpty()) return;
            ResponseBodyEmitter emitter = lookupEmitter(sessionId);
            if (emitter == null) return;

            List<Map<String, Object>> items = buildEvidenceSnippets(documents, startRef);
            if (items.isEmpty()) return;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("startRef", startRef);
            data.put("items", items);
            data.put("timestamp", System.currentTimeMillis());
            sendEvent(emitter, data, sessionId);
        } catch (Exception e) {
            // advisor 失败不能影响主回答 —— 顶层兜底
            log.debug("[RagEvidence] emit aborted sessionId={} err={}", sessionId, e.toString());
        }
    }

    public List<Map<String, Object>> buildEvidenceSnippets(List<Document> documents) {
        return buildEvidenceSnippets(documents, 1);
    }

    public List<Map<String, Object>> buildEvidenceSnippets(List<Document> documents, int startRef) {
        if (documents == null || documents.isEmpty()) return List.of();
        return buildItems(documents, startRef);
    }

    private List<Map<String, Object>> buildItems(List<Document> documents, int startRef) {
        List<Map<String, Object>> items = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            if (doc == null) continue;
            int ref = startRef + i;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", ref);
            item.put("source", extractSource(doc, ref));
            item.put("snippet", normalize(doc.getText()));
            putScore(item, doc);
            items.add(item);
        }
        return items;
    }

    /**
     * 2026-06-22 评估采集：给每条 evidence 附上检索分,供事后量化 RAG 检索质量。<b>只读 Document、零副作用、生产安全</b>
     * （score/distance 本就是数字相似度,不含 PII；前端忽略未知字段）。
     * <ul>
     *   <li>{@code score}: {@link Document#getScore()} 相似度（高=更相关），纯向量/部分混合路径有值；</li>
     *   <li>{@code distance}: PgVector 余弦距离（低=更相关），key 与 {@code LongTermMemoryService.extractDistance} 一致；</li>
     *   <li>{@code bm25_score}: 词法检索分（混合检索 BM25 路径写入,如有）。</li>
     * </ul>
     * 注意：rerank 只重排不写回分数,故这里拿到的是<b>检索阶段</b>分；最终 rerank 顺序已体现在 ref 排序里。
     */
    static void putScore(Map<String, Object> item, Document doc) {
        try {
            Double score = doc.getScore();
            if (score != null) item.put("score", score);
            Map<String, Object> meta = doc.getMetadata();
            if (meta != null) {
                Object dist = meta.get("distance");
                if (dist != null) item.put("distance", dist);
                Object bm25 = meta.get("bm25_score");
                if (bm25 != null) item.put("bm25_score", bm25);
            }
        } catch (Exception ignored) {
            // 取分失败绝不能影响 evidence 主体
        }
    }

    /** 从 metadata 按优先级找 source；都没有用 Document.id 或 doc-N fallback。 */
    static String extractSource(Document doc, int ref) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta != null) {
            for (String key : SOURCE_METADATA_KEYS) {
                Object v = meta.get(key);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty()) return s;
                }
            }
        }
        String id = doc.getId();
        return (id != null && !id.isBlank()) ? id : ("doc-" + ref);
    }

    /**
     * 多空白归一（换行/tab/连续空格 → 单空格），不再二次截断。
     * <p>用户第 59 轮反馈：ingest 端在入向量库前已按 chunkSize 切过，
     * 单 chunk 本就不长（一般 200-500 字），前端再截会丢上下文。</p>
     */
    static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private ResponseBodyEmitter lookupEmitter(String sessionId) {
        if (approvalChannelRegistry == null) return null;
        return approvalChannelRegistry.get(sessionId);
    }

    private void sendEvent(ResponseBodyEmitter emitter, Map<String, Object> data, String sessionId) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.debug("[RagEvidence] json serialize failed sessionId={} err={}", sessionId, e.toString());
            return;
        }
        try {
            emitter.send("event: rag_evidence\ndata: " + payload + "\n\n");
        } catch (Exception e) {
            log.debug("[RagEvidence] emit failed sessionId={} err={}", sessionId, e.toString());
        }
    }
}
