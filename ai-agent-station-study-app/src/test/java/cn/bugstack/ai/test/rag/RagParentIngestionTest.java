package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.service.rag.IParentDocumentService;
import cn.bugstack.ai.domain.agent.service.rag.IParentEnrichmentGenerator;
import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagIngestionObserver;
import cn.bugstack.ai.domain.agent.service.rag.RagService;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.domain.agent.service.rag.splitter.SemanticTextSplitter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Parent-mode ingestion without Spring, models or databases: enrichment and stores are in-memory fakes. */
public class RagParentIngestionTest {
    private RagService rag;
    private BM25SearchService keywords;
    private IAgentRepository repository;
    private FakeParents parents;
    private FakeEnrichment enrichment;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @Before public void setup() {
        rag = new RagService();
        keywords = mock(BM25SearchService.class);
        doAnswer(call -> {
            List<Document> docs = call.getArgument(0);
            if (!docs.isEmpty()) events.add("es:" + docs.get(0).getMetadata().get("parent_id"));
            return null;
        }).when(keywords).index(anyList());
        repository = mock(IAgentRepository.class);
        parents = new FakeParents();
        enrichment = new FakeEnrichment();
        ReflectionTestUtils.setField(rag, "textSplitter", new SemanticTextSplitter(1000, 0, 80, 2000));
        ReflectionTestUtils.setField(rag, "vectorStore", mock(PgVectorStore.class));
        ReflectionTestUtils.setField(rag, "repository", repository);
        ReflectionTestUtils.setField(rag, "bm25SearchService", keywords);
        ReflectionTestUtils.setField(rag, "parentDocumentService", parents);
        ReflectionTestUtils.setField(rag, "enrichmentGenerator", enrichment);
        ReflectionTestUtils.setField(rag, "enrichmentConcurrency", 4);
        MDC.put("userId", "alice");
        MDC.put("runId", "run-1");
    }

    @After public void cleanup() { rag.shutdown(); MDC.clear(); }

    private static MockMultipartFile file() {
        StringBuilder text = new StringBuilder();
        for (int section = 1; section <= 6; section++) {
            text.append("# 第").append(section).append("节\n\n");
            for (int sentence = 1; sentence <= 12; sentence++) {
                text.append("第").append(section).append("节第").append(sentence)
                        .append("句用于构造足够长的段落，确保每个父块会被切成两个以上的子块，以便验证上下文与子块一一对应。");
            }
            text.append("\n\n");
        }
        return new MockMultipartFile("file", "notes.md", "text/markdown", text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static class Observer implements RagIngestionObserver {
        String fingerprint; String previous; String warning; int maxParents = Integer.MAX_VALUE; int lastChunks;
        @Override public void progress(String stage, int percent, int chunks) { if (chunks >= 0) lastChunks = chunks; }
        @Override public void warn(String message) { warning = message; }
        @Override public boolean prepare(String value) { fingerprint = value; return value.equals(previous); }
        @Override public int maxParents() { return maxParents; }
    }

    @Test public void everyParentUsesOneEnrichmentCallAndIsMarkedAfterItsIndexes() {
        Observer observer = new Observer();
        rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", observer);

        assertTrue("应切出多个父块", parents.rows.size() > 1);
        assertEquals("每个父块只调用一次增强", parents.rows.size(), enrichment.calls.get());
        assertTrue("父块应并发处理", enrichment.maxConcurrent.get() > 1);
        assertEquals("工作线程应带上 runId 以查找预算", Set.of("run-1"), enrichment.runIds);
        for (String parentId : parents.rows.keySet()) {
            assertTrue(parentId + " 的关键词索引必须先于父块行写入", events.indexOf("es:" + parentId) < events.indexOf("row:" + parentId));
        }
        int children = 0;
        for (List<Document> stored : parents.children.values()) {
            assertTrue(stored.size() >= 2);
            for (int i = 0; i < stored.size(); i++) {
                Document child = stored.get(i);
                assertTrue(child.getText().startsWith("【上下文】上下文" + (i + 1) + "\n\n【正文】"));
                assertEquals("小标题", child.getMetadata().get("title"));
                assertEquals("alice", child.getMetadata().get("user_id"));
                assertTrue(String.valueOf(child.getMetadata().get(RagFileScope.FILE_ID_KEY)).startsWith(RagFileScope.FILE_ID_PREFIX));
            }
            children += stored.size();
        }
        assertEquals(children, observer.lastChunks);
        verify(repository).createTagOrder(any());
    }

    @Test public void retryWithSameFingerprintSkipsCompletedParentsAndFailureWaitsForWorkers() throws Exception {
        parents.failOnce = "-p2";
        Observer first = new Observer();
        assertThrows(RagStorageException.class, () -> rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", first));
        int written = events.size();
        Thread.sleep(200);
        assertEquals("失败返回后不能再有工作线程写入", written, events.size());
        verify(repository, never()).createTagOrder(any());
        int completed = parents.rows.size();
        assertTrue(completed < 6);

        int callsBefore = enrichment.calls.get();
        Observer retry = new Observer();
        retry.previous = first.fingerprint;
        rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", retry);
        assertEquals(first.fingerprint, retry.fingerprint);
        assertEquals("已完成的父块不再调用模型", 6 - completed, enrichment.calls.get() - callsBefore);
        assertEquals(6, parents.rows.size());
        verify(repository).createTagOrder(any());
    }

    @Test public void unavailableModelStopsFurtherCallsAndWarnsWhileStillPublishing() {
        enrichment.unavailable = true;
        Observer observer = new Observer();
        rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", observer);

        assertEquals(6, parents.rows.size());
        assertTrue("模型不可用后其余父块不再发请求（最多只有并发中的几个）", enrichment.calls.get() <= 4);
        assertTrue(observer.warning.contains("6/6 个段落未生成标题和上下文"));
        assertTrue(observer.warning.contains("HTTP 400"));
        parents.children.values().forEach(stored -> stored.forEach(child -> assertFalse(child.getText().startsWith("【上下文】"))));
        verify(repository).createTagOrder(any());
    }

    @Test public void degradedParentsAreEnrichedAgainOnRetryInsteadOfBeingResumed() {
        enrichment.unavailable = true;
        parents.failOnce = "-p5";
        Observer first = new Observer();
        assertThrows(RagStorageException.class, () -> rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", first));
        assertFalse(parents.rows.isEmpty());

        enrichment.unavailable = false;
        int callsBefore = enrichment.calls.get();
        Observer retry = new Observer();
        retry.previous = first.fingerprint;
        rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", retry);
        assertEquals("降级写入的父块没有标题，重试时应全部重新增强", 6, enrichment.calls.get() - callsBefore);
        assertNull(retry.warning);
        assertEquals(6, parents.titles.size());
    }

    @Test public void tooManyParentsFailsBeforeAnyModelOrStorageWork() {
        Observer observer = new Observer();
        observer.maxParents = 2;
        RagStorageException error = assertThrows(RagStorageException.class,
                () -> rag.storeRagFile("notes.md", "kb", List.of(file()), "alice", observer));
        assertTrue(error.getMessage().contains("超过单个文件上限 2 个"));
        assertNull("超限时不能进入清理或续做流程", observer.fingerprint);
        assertEquals(0, enrichment.calls.get());
        assertTrue(parents.rows.isEmpty());
    }

    private static class FakeEnrichment implements IParentEnrichmentGenerator {
        final AtomicInteger calls = new AtomicInteger(), running = new AtomicInteger(), maxConcurrent = new AtomicInteger();
        final Set<String> runIds = ConcurrentHashMap.newKeySet();
        volatile boolean unavailable;
        @Override public boolean titleRequired() { return true; }
        @Override public Enrichment enrich(String documentTitle, List<Document> children) {
            calls.incrementAndGet();
            if (unavailable) throw new UnavailableException("增强模型不可用（HTTP 400）", null);
            runIds.add(String.valueOf(MDC.get("runId")));
            maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { running.decrementAndGet(); }
            List<String> contexts = new ArrayList<>();
            for (int i = 1; i <= children.size(); i++) contexts.add("上下文" + i);
            return new Enrichment("小标题", contexts);
        }
        @Override public String strategy() { return "fake"; }
    }

    private class FakeParents implements IParentDocumentService {
        final Map<String, String> rows = new ConcurrentHashMap<>();
        final Map<String, String> titles = new ConcurrentHashMap<>();
        final Map<String, List<Document>> children = new ConcurrentHashMap<>();
        volatile String failOnce;
        @Override public void store(String parentId, String parentText, List<Document> childDocs, String tag, String source) { throw new UnsupportedOperationException(); }
        @Override public void store(String parentId, String parentText, List<Document> childDocs, String tag, String source, String userId) { throw new UnsupportedOperationException(); }
        @Override public void store(String parentId, String parentText, List<Document> childDocs, String tag, String source, String userId, String title) {
            if (failOnce != null && parentId.endsWith(failOnce)) { failOnce = null; throw new RagStorageException("向量库暂时不可用"); }
            children.put(parentId, childDocs);
            if (title == null) titles.remove(parentId); else titles.put(parentId, title);
            rows.put(parentId, parentText);
            events.add("row:" + parentId);
        }
        @Override public List<String> resolveParents(List<Document> childDocs) { return List.of(); }
        @Override public List<Document> resolveParentDocuments(List<Document> childDocs) { return List.of(); }
        @Override public Map<String, String> completedParents(Map<String, String> parentTexts, String userId, String tag) {
            Map<String, String> done = new HashMap<>();
            parentTexts.forEach((id, text) -> { if (text.equals(rows.get(id))) done.put(id, titles.get(id)); });
            return done;
        }
    }
}
