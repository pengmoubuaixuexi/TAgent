package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.rag.*;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2 task SQL + temporary source files; storage/model calls are mocked. */
public class RagIngestionLifecycleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private JdbcTemplate db;
    private RagIngestionRepository records;
    private RagUploadStorage sources;
    private RagIngestionService service;
    private IRagService engine;
    private IRagFileDeletionService deletion;
    private ModelProviderAccess policy;
    private Path root;
    private final AdjustableClock clock = new AdjustableClock();

    @Before public void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:ingest_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        db = new JdbcTemplate(source);
        Path migration = Path.of("docs/dev-ops/sql-migrations/V066__knowledge_ingestion.sql");
        if (!Files.exists(migration)) migration = Path.of("../docs/dev-ops/sql-migrations/V066__knowledge_ingestion.sql");
        String sql = Files.readString(migration).replaceAll("(?m)^--.*$", "").replace("SET NAMES utf8mb4;", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        db.execute(sql);
        db.execute("CREATE TABLE ai_client_rag_order(id BIGINT AUTO_INCREMENT PRIMARY KEY,rag_id VARCHAR(64),rag_name VARCHAR(255),"
                + "knowledge_tag VARCHAR(128),file_hash VARCHAR(64),file_size BIGINT,user_id VARCHAR(64),status INT,create_time DATETIME)");
        records = new RagIngestionRepository(db, clock);
        root = temp.newFolder("private").toPath();
        sources = new RagUploadStorage(root.toString(), 1024);
        engine = mock(IRagService.class); deletion = mock(IRagFileDeletionService.class); policy = mock(ModelProviderAccess.class);
        service = new RagIngestionService(records, sources, engine, deletion, new RunBudgetRegistry(), policy);
    }
    @After public void cleanup() { service.close(); }
    private MockMultipartFile file() { return new MockMultipartFile("file", "notes.txt", "text/plain", "fixture text".getBytes()); }
    private RagIngestionTask submit(String owner) { return service.submit(owner, "kb", file()); }
    private RagIngestionTask await(String owner, String id, String status) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RagIngestionTask task;
        do {
            service.tick();
            task = records.owned(owner, id);
            if (status.equals(task.status())) return task;
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        fail("Expected " + status + " but was " + task.status()); return null;
    }

    @Test public void dedupIsScopedAndSourcesNeverLeakThroughPublicView() throws Exception {
        var alice = submit("alice"); var duplicate = submit("alice"); var bob = submit("bob");
        assertEquals(alice.taskId(), duplicate.taskId()); assertNotEquals(alice.ragId(), bob.ragId());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM ai_rag_ingestion_task", Integer.class).intValue());
        try (var paths = Files.list(root)) { assertEquals(2, paths.count()); }
        assertFalse(service.view(alice).containsKey("sourcePath")); assertFalse(service.view(alice).containsKey("leaseToken"));
        assertThrows(RagStorageException.class, () -> service.requireTask("bob", alice.taskId()));
        assertThrows(RagStorageException.class, () -> service.deleteTask("bob", alice.taskId()));
        verifyNoInteractions(engine, deletion);
    }

    @Test public void platformPolicyIsCheckedBeforePersistingAnyFile() {
        doThrow(new SecurityException("请先允许平台辅助模型")).when(policy).requirePlatformAuxiliary("alice", "EMBEDDING");
        assertThrows(SecurityException.class, () -> submit("alice"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ai_rag_ingestion_task", Integer.class).intValue());
        verifyNoInteractions(engine);
    }

    @Test public void persistedStagesAreBoundToTrustedOwnerAndSucceededOnlyAfterEngineReturns() throws Exception {
        doAnswer(call -> {
            assertEquals("alice", call.getArgument(3));
            assertEquals("alice", RunBudgetRegistry.find(Map.of()).ownerUserId());
            RagIngestionObserver observer = call.getArgument(4); observer.progress("INDEXING", 85, 7);
            var current = records.list("alice").get(0);
            assertEquals("PROCESSING", current.status()); assertEquals(85, current.progress());
            return null;
        }).when(engine).storeRagFile(anyString(), anyString(), anyList(), anyString(), any());
        var task = submit("alice"); service.tick();
        var done = await("alice", task.taskId(), "SUCCEEDED");
        assertEquals(100, done.progress()); assertEquals(7, done.chunkCount()); assertEquals(1, done.attempt());
        assertNull(done.leaseToken()); assertNotNull(sources.open(done));
    }

    @Test public void explicitRetryClearsPartialStableScopeBeforeNewModelWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("SYNTHETIC_BACKEND_SECRET");
            verify(deletion).deleteIngestionFile(argThat(scope -> "alice".equals(scope.userId()) && "kb".equals(scope.knowledgeTag())));
            return null;
        }).when(engine).storeRagFile(anyString(), anyString(), anyList(), anyString(), any());
        var task = submit("alice"); service.tick();
        var failed = await("alice", task.taskId(), "FAILED"); assertFalse(failed.error().contains("BACKEND_SECRET"));
        assertNotNull(sources.open(failed));
        service.retry("alice", task.taskId()); service.tick();
        var done = await("alice", task.taskId(), "SUCCEEDED"); assertEquals(2, done.attempt()); assertEquals(2, calls.get());
    }

    @Test public void failedCleanupBlocksRetryWithoutStartingModels() throws Exception {
        var task = submit("alice"); var claimed = records.claimNext(); records.fail(claimed, "test failure");
        doThrow(new RagStorageException("片段清理失败，请恢复存储后重试")).when(deletion).deleteIngestionFile(any());
        service.retry("alice", task.taskId()); service.tick();
        var failed = await("alice", task.taskId(), "FAILED");
        assertTrue(failed.error().contains("清理失败")); verifyNoInteractions(engine); assertNotNull(sources.open(failed));
    }

    @Test public void corruptedSourceCannotAdmitRetryOrModelCalls() throws Exception {
        var task = submit("alice"); var claimed = records.claimNext(); records.fail(claimed, "test failure");
        Files.writeString(root.resolve(task.sourcePath()), "modified");
        assertThrows(RagStorageException.class, () -> service.retry("alice", task.taskId()));
        assertEquals("FAILED", records.owned("alice", task.taskId()).status()); verifyNoInteractions(engine);
    }

    @Test public void queuedDeletionDoesNotTouchIndexesAndAllowsFreshUpload() throws Exception {
        var task = submit("alice"); service.deleteTask("alice", task.taskId());
        assertEquals("DELETED", records.owned("alice", task.taskId()).status());
        assertFalse(Files.exists(root.resolve(task.sourcePath()))); verifyNoInteractions(deletion);
        assertNotEquals(task.taskId(), submit("alice").taskId());
    }

    @Test public void activeDeletionWaitsForActualWorkerExitBeforeCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            // Simulate a driver that does not immediately respond to interrupt.
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            ((RagIngestionObserver) call.getArgument(4)).progress("INDEXING", 85, 1);
            return null;
        }).when(engine).storeRagFile(anyString(), anyString(), anyList(), anyString(), any());
        var task = submit("alice"); service.tick(); assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            assertEquals("CANCEL_REQUESTED", service.deleteTask("alice", task.taskId()).status());
            assertEquals("CANCEL_REQUESTED", service.deleteTask("alice", task.taskId()).status());
            verifyNoInteractions(deletion); assertTrue(Files.exists(root.resolve(task.sourcePath())));
        } finally { release.countDown(); }
        await("alice", task.taskId(), "DELETE_FAILED");
        service.deleteTask("alice", task.taskId());
        verify(deletion).deleteIngestionFile(argThat(scope -> scope.fileId().equals(task.ragId()) && scope.userId().equals("alice")));
        assertEquals("DELETED", records.owned("alice", task.taskId()).status());
    }

    @Test public void partialDeletionFailurePreservesTaskAndSourceForIdempotentCleanup() throws Exception {
        var task = submit("alice"); var claimed = records.claimNext(); records.fail(claimed, "test failure");
        doThrow(new RagStorageException("ES 不可用，目录已保留")).doNothing().when(deletion).deleteIngestionFile(any());
        assertThrows(RagStorageException.class, () -> service.deleteTask("alice", task.taskId()));
        assertEquals("DELETE_FAILED", records.owned("alice", task.taskId()).status());
        assertTrue(Files.exists(root.resolve(task.sourcePath())));
        service.deleteTask("alice", task.taskId());
        assertEquals("DELETED", records.owned("alice", task.taskId()).status()); verify(deletion, times(2)).deleteIngestionFile(any());
    }

    @Test public void leasesFenceOldProgressAndRestartNeverAutomaticallyConsumesModels() {
        var task = submit("alice"); var old = records.claimNext();
        clock.advance(Duration.ofMinutes(3)); records.recoverExpired();
        assertEquals("FAILED", records.owned("alice", task.taskId()).status());
        assertFalse(records.progress(old, "INDEXING", 99, 10)); assertFalse(records.complete(old));
        assertNull(records.claimNext());
        assertTrue(records.retry("alice", task.taskId())); assertFalse(records.retry("alice", task.taskId()));
        var next = records.claimNext(); assertEquals(2, next.attempt()); assertNotEquals(old.leaseToken(), next.leaseToken());
        assertFalse(records.complete(old)); verifyNoInteractions(engine);
    }

    @Test public void workerClaimAndAllMutationsRespectOwnerAndState() throws Exception {
        var task = submit("alice");
        assertFalse(records.retry("bob", task.taskId())); assertFalse(records.beginDelete("bob", task.taskId()));
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            var a = racers.submit(records::claimNext); var b = racers.submit(records::claimNext);
            assertEquals(1, (a.get() == null ? 0 : 1) + (b.get() == null ? 0 : 1));
        } finally { racers.shutdownNow(); }
        assertFalse(records.requestCancel("bob", task.taskId())); assertFalse(records.beginDelete("alice", task.taskId()));
    }

    @Test public void pathTraversalAndUnsupportedFilesNeverCreateSourceContent() {
        assertThrows(RagStorageException.class, () -> sources.save(UUID.randomUUID().toString(), new MockMultipartFile("file", "../outside.txt", "text/plain", new byte[]{1})));
        assertThrows(RagStorageException.class, () -> sources.save("../outside", file()));
        assertThrows(RagStorageException.class, () -> sources.save(UUID.randomUUID().toString(), new MockMultipartFile("file", "code.exe", "application/octet-stream", new byte[]{1})));
        assertThrows(RagStorageException.class, () -> sources.save(UUID.randomUUID().toString(), new MockMultipartFile("file", "large.txt", "text/plain", new byte[2048])));
    }

    @Test public void partialNewChunksStayHiddenUntilTaskAndDirectoryPublicationAgree() {
        var task = submit("alice"); var claim = records.claimNext();
        var visibility = new PublishedRagDocuments(db);
        var document = new org.springframework.ai.document.Document("partial", Map.of("user_id", "alice", "knowledge", "kb", "rag_file_id", task.ragId(), "source", "notes.txt"));
        assertTrue(visibility.published(List.of(document)).isEmpty());
        directory(task.ragId(), "notes.txt", "alice", "kb");
        assertTrue("directory insertion alone is not publication", visibility.published(List.of(document)).isEmpty());
        records.fail(claim, "partial ES failure");
        assertTrue("failed index never becomes visible", visibility.published(List.of(document)).isEmpty());
        records.retry("alice", task.taskId()); var retry = records.claimNext(); records.complete(retry);
        assertEquals(List.of(document), visibility.published(List.of(document)));
        assertTrue(records.beginDelete("alice", task.taskId()));
        assertTrue("deleting documents are hidden before cross-store cleanup", visibility.published(List.of(document)).isEmpty());
        records.deleteFailed("alice", task.taskId(), "partial delete");
        assertTrue(visibility.published(List.of(document)).isEmpty());
    }

    @Test public void legacyDocumentsWithoutTaskRemainUsableButOwnerAndTagMustMatchDirectory() {
        var visibility = new PublishedRagDocuments(db);
        directory("legacy-rag", "original.pdf", "alice", "kb");
        var original = new org.springframework.ai.document.Document("original", Map.of("user_id", "alice", "knowledge", "kb", "source", "original.pdf"));
        var foreign = new org.springframework.ai.document.Document("foreign", Map.of("user_id", "bob", "knowledge", "kb", "source", "original.pdf"));
        var wrongTag = new org.springframework.ai.document.Document("tag", Map.of("user_id", "alice", "knowledge", "another", "source", "original.pdf"));
        assertEquals(List.of(original), visibility.published(List.of(original, foreign, wrongTag)));
        db.update("UPDATE ai_client_rag_order SET status=0 WHERE rag_id='legacy-rag'");
        assertTrue(visibility.published(List.of(original)).isEmpty());
    }

    @Test public void metadataOutageCannotBypassPublicationGateAndWrapperChecksDirectSearches() {
        var task = submit("alice");
        var document = new org.springframework.ai.document.Document("partial", Map.of("user_id", "alice", "knowledge", "kb", "rag_file_id", task.ragId()));
        var raw = mock(org.springframework.ai.vectorstore.VectorStore.class);
        when(raw.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(document));
        var wrapped = new PublishedRagVectorStore(raw, new PublishedRagDocuments(db));
        assertTrue(wrapped.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder().query("x").topK(3).build()).isEmpty());
        verify(raw).similaritySearch(argThat((org.springframework.ai.vectorstore.SearchRequest request) -> request.getTopK() == 12));
        directory(task.ragId(), "notes.txt", "alice", "kb");
        db.execute("DROP TABLE ai_rag_ingestion_task");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> wrapped.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder().query("x").build()));
    }

    @Test public void reusedCandidatesRevalidatePublicationAndCurrentOwnerOnEveryRead() {
        directory("old-a", "a.txt", "alice", "kb"); directory("old-b", "b.txt", "bob", "kb");
        var a = new org.springframework.ai.document.Document("a", Map.of("user_id", "alice", "knowledge", "kb", "source", "a.txt"));
        var b = new org.springframework.ai.document.Document("b", Map.of("user_id", "bob", "knowledge", "kb", "source", "b.txt"));
        var raw = mock(org.springframework.ai.vectorstore.VectorStore.class);
        when(raw.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(a, b));
        var store = new PublishedRagVectorStore(raw, new PublishedRagDocuments(db));
        var request = org.springframework.ai.vectorstore.SearchRequest.builder().query("same cached query").build();
        org.slf4j.MDC.put("userId", "alice");
        try {
            assertEquals(List.of(a), store.similaritySearch(request));
            db.update("UPDATE ai_client_rag_order SET status=0 WHERE rag_id='old-a'");
            assertTrue("old candidate objects do not authorize a disabled directory", store.similaritySearch(request).isEmpty());
            org.slf4j.MDC.put("userId", "bob");
            assertEquals(List.of(b), store.similaritySearch(request));
        } finally { org.slf4j.MDC.remove("userId"); }
    }

    private void directory(String id, String name, String owner, String tag) {
        db.update("INSERT INTO ai_client_rag_order(rag_id,rag_name,user_id,knowledge_tag,status,create_time) VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)", id, name, owner, tag);
    }

    private static final class AdjustableClock extends Clock {
        private Instant value = Instant.parse("2026-10-09T00:00:00Z");
        void advance(Duration duration) { value = value.plus(duration); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
}
