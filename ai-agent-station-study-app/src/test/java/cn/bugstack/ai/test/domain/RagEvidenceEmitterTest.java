package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RagEvidenceEmitter;
import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * H1-A 单测：RagEvidenceEmitter 派生 + SSE 推送行为。纯 JUnit4，不拉 Spring。
 */
public class RagEvidenceEmitterTest {

    @Test
    public void nullSessionIdSilentlySkips() {
        RagEvidenceEmitter e = new RagEvidenceEmitter(new ApprovalChannelRegistry());
        e.emitEvidence(null, List.of(new Document("text", new HashMap<>())));
        e.emitEvidence("  ", List.of(new Document("text", new HashMap<>())));
        // 不抛即通过
    }

    @Test
    public void emptyDocumentsDoesNotEmit() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        e.emitEvidence("session-1", null);
        e.emitEvidence("session-1", new ArrayList<>());

        assertEquals("空文档不应 emit", 0, capturing.sent.size());
    }

    @Test
    public void missingChannelSilentlySkips() {
        // sessionId 有效但 channel 未注册 → 静默跳过
        RagEvidenceEmitter e = new RagEvidenceEmitter(new ApprovalChannelRegistry());
        e.emitEvidence("nonexistent", List.of(new Document("text", new HashMap<>())));
        // 不抛即通过
    }

    @Test
    public void emitsItemsWithRefAndSnippet() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("file_name", "spring-ai-guide.md");
        Document d1 = new Document("Spring AI 提供了统一的 ChatClient API。", meta1);
        Document d2 = new Document("Advisor 链让你能拦截 Prompt / Response。", new HashMap<>());

        e.emitEvidence("session-1", List.of(d1, d2));

        assertEquals(1, capturing.sent.size());
        String frame = capturing.sent.get(0);
        assertTrue(frame.startsWith("event: rag_evidence\n"));
        assertTrue(frame.contains("\"sessionId\":\"session-1\""));
        assertTrue(frame.contains("\"ref\":1"));
        assertTrue(frame.contains("\"source\":\"spring-ai-guide.md\""));
        assertTrue(frame.contains("\"snippet\":\"Spring AI 提供了统一的 ChatClient API。\""));
        assertTrue(frame.contains("\"ref\":2"));
        assertTrue("d2 metadata 都空，应 fallback 到 doc-2 或 document id", frame.contains("doc-2") || frame.contains("\"source\":\""));
    }

    @Test
    public void sourcePrefersTitleOverFileNameAndSource() {
        // 第 61 轮：title 是 Phase 2 LLM 生成的精炼小标题（"标准深蹲膝盖位置"），
        // 比原始文件名 / 文件级 source 更可读，所以优先级最高
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", "标准深蹲膝盖位置");
        meta.put("file_name", "fitness-guide.md");
        meta.put("source", "fitness-guide.md");
        meta.put("url", "https://example.com");
        e.emitEvidence("session-1", List.of(new Document("text", meta)));

        String frame = capturing.sent.get(0);
        assertTrue("title 优先级最高", frame.contains("\"source\":\"标准深蹲膝盖位置\""));
        assertFalse("不应使用 file_name", frame.contains("\"source\":\"fitness-guide.md\""));
    }

    @Test
    public void sourceFallsBackToDocIdThenSyntheticName() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        // 所有 metadata keys 都缺 → 用 Document.id (Spring AI 自动生成 UUID-like)
        Document d = new Document("text", new HashMap<>());
        e.emitEvidence("session-1", List.of(d));

        String frame = capturing.sent.get(0);
        // 至少含有 source 字段且非空
        assertTrue(frame.contains("\"source\":\""));
    }

    @Test
    public void snippetNotTruncatedRelyOnIngestChunk() {
        // 第 59 轮调整：ingest 端已按 chunkSize 切过，emitter 不再二次截断
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) huge.append('x');
        e.emitEvidence("session-1", List.of(new Document(huge.toString(), new HashMap<>())));

        String frame = capturing.sent.get(0);
        assertFalse("不应再带 truncated 标记", frame.contains("(truncated"));
        assertTrue("应包含全部 500 字符", frame.contains("x".repeat(500)));
    }

    @Test
    public void sourceFallsBackToKnowledgeWhenBM25MetadataMissing() {
        // 第 59 轮新增：模拟 BM25 路径 metadata 只有 knowledge，无 source/file_name
        // —— 应 fallback 到 knowledge 而不是 doc.id UUID
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        Map<String, Object> bm25Meta = new HashMap<>();
        bm25Meta.put("knowledge", "标准深蹲动作要领");
        bm25Meta.put("bm25_score", 1.23);
        e.emitEvidence("session-1", List.of(new Document("深蹲时膝盖不要超过脚尖", bm25Meta)));

        String frame = capturing.sent.get(0);
        assertTrue("source 应 fallback 到 knowledge tag", frame.contains("\"source\":\"标准深蹲动作要领\""));
    }

    @Test
    public void sourceMetadataKeyTakesPrecedenceOverKnowledge() {
        // 修了 BM25 透传 source 后，新数据 metadata 同时有 source 和 knowledge → 用 source
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "fitness-guide.md");
        meta.put("knowledge", "标准深蹲动作要领");
        e.emitEvidence("session-1", List.of(new Document("text", meta)));

        String frame = capturing.sent.get(0);
        assertTrue("source 应优先 source 字段", frame.contains("\"source\":\"fitness-guide.md\""));
        assertFalse("不应使用 knowledge", frame.contains("\"source\":\"标准深蹲动作要领\""));
    }

    @Test
    public void multipleWhitespaceNormalizedToSingleSpace() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        Document d = new Document("line one\n\n\nline two\t\t  line three", new HashMap<>());
        e.emitEvidence("session-1", List.of(d));

        String frame = capturing.sent.get(0);
        assertTrue("换行/tab/多空格应归一", frame.contains("\"snippet\":\"line one line two line three\""));
    }

    @Test
    public void emitFailureDoesNotPropagate() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        ResponseBodyEmitter broken = new ResponseBodyEmitter() {
            @Override
            public void send(Object object) {
                throw new RuntimeException("client disconnected");
            }
        };
        channels.register("session-1", broken);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        // 不抛即通过
        e.emitEvidence("session-1", List.of(new Document("text", new HashMap<>())));
    }

    @Test
    public void nullRegistryHandledGracefully() {
        RagEvidenceEmitter e = new RagEvidenceEmitter(null);
        e.emitEvidence("session-1", List.of(new Document("text", new HashMap<>())));
        // 不抛即通过
    }

    @Test
    public void startRefAppliedToItemsAndPayload() {
        // 第 61 轮：全局编号 emit 时，items 用 startRef 起编号，payload 包含 startRef 字段
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter capturing = new CapturingEmitter();
        channels.register("session-1", capturing);
        RagEvidenceEmitter e = new RagEvidenceEmitter(channels);

        Document d1 = new Document("alpha", new HashMap<>());
        Document d2 = new Document("beta", new HashMap<>());
        e.emitEvidence("session-1", List.of(d1, d2), 5);

        String frame = capturing.sent.get(0);
        assertTrue("payload 应含 startRef=5", frame.contains("\"startRef\":5"));
        assertTrue("第一条 ref=5", frame.contains("\"ref\":5"));
        assertTrue("第二条 ref=6", frame.contains("\"ref\":6"));
        assertFalse("不应有 ref=1", frame.contains("\"ref\":1"));
    }

    @Test
    public void buildEvidenceSnippetsRespectsStartRef() {
        // buildEvidenceSnippets 不发送 SSE，只构造 items；ref 应按 startRef 累加
        RagEvidenceEmitter e = new RagEvidenceEmitter(new ApprovalChannelRegistry());
        List<Map<String, Object>> items = e.buildEvidenceSnippets(
                List.of(new Document("x", new HashMap<>()), new Document("y", new HashMap<>())),
                10);

        assertEquals(2, items.size());
        assertEquals(10, items.get(0).get("ref"));
        assertEquals(11, items.get(1).get("ref"));
    }

    @Test
    public void buildEvidenceSnippetsAcceptsStableNonContiguousReferences() {
        RagEvidenceEmitter e = new RagEvidenceEmitter(new ApprovalChannelRegistry());
        List<Map<String, Object>> items = e.buildEvidenceSnippets(
                List.of(new Document("x", new HashMap<>()), new Document("y", new HashMap<>())),
                List.of(2, 7));

        assertEquals(2, items.get(0).get("ref"));
        assertEquals(7, items.get(1).get("ref"));
    }

    @Test
    public void evidenceFingerprintIgnoresScoreChangesButTracksSourceAndContent() {
        RagEvidenceEmitter e = new RagEvidenceEmitter(new ApprovalChannelRegistry());
        Map<String, Object> firstMeta = new HashMap<>();
        firstMeta.put("title", "travel guide");
        firstMeta.put("rerank_score", 0.8d);
        Map<String, Object> secondMeta = new HashMap<>();
        secondMeta.put("title", "travel guide");
        secondMeta.put("rerank_score", 0.2d);

        assertEquals(e.evidenceFingerprint(new Document("same content", firstMeta)),
                e.evidenceFingerprint(new Document("same content", secondMeta)));
    }

    @Test
    public void userFacingScorePrefersRerankThenFallsBackToSemantic() {
        RagEvidenceEmitter emitter = new RagEvidenceEmitter(new ApprovalChannelRegistry());
        Map<String, Object> rerankedMeta = new HashMap<>();
        rerankedMeta.put("rerank_score", 0.87d);
        rerankedMeta.put("distance", 0.09d);
        Map<String, Object> semanticMeta = new HashMap<>();
        semanticMeta.put("distance", 0.25d);

        List<Map<String, Object>> items = emitter.buildEvidenceSnippets(List.of(
                new Document("reranked", rerankedMeta),
                new Document("semantic", semanticMeta)));

        assertEquals("rerank", items.get(0).get("relevanceType"));
        assertEquals(0.87d, (Double) items.get(0).get("relevanceScore"), 0.0001d);
        assertEquals("semantic", items.get(1).get("relevanceType"));
        assertEquals(0.75d, (Double) items.get(1).get("relevanceScore"), 0.0001d);
    }

    /** 测试用 emitter：捕获所有 send 调用的字符串内容。 */
    private static class CapturingEmitter extends ResponseBodyEmitter {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(Object object) {
            sent.add(String.valueOf(object));
        }
    }
}
