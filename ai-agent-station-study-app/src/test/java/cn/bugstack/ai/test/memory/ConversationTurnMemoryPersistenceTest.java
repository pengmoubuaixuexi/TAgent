package cn.bugstack.ai.test.memory;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.memory.IConversationSummarizer;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import org.slf4j.MDC;
import cn.bugstack.ai.infrastructure.adapter.repository.ConversationTurnMemoryService;
import cn.bugstack.ai.infrastructure.adapter.repository.MyBatisChatMemoryRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.AgentRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.MemoryCacheService;
import cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao;
import cn.bugstack.ai.infrastructure.dao.IAiChatMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemory;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Real mapper SQL and local transactions only; no Spring application, network, Redis or LLM. */
public class ConversationTurnMemoryPersistenceTest {
    private static final String C = "tenant:alice:session";
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IAiChatMemoryDao messages;
    private IAiChatMemorySummaryDao summaries;
    private MemoryCacheService cache;
    private IConversationSummarizer summarizer;
    private ConversationTurnMemoryService service;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);

    @Before
    public void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:turn_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ai_chat_memory (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "conversation_id VARCHAR(128) NOT NULL, user_id VARCHAR(64), agent_id VARCHAR(64), "
                + "run_id VARCHAR(128), message_type VARCHAR(16) NOT NULL, content LONGTEXT NOT NULL, "
                + "content_parts LONGTEXT, media_count INT, created_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE ai_chat_memory_summary (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "conversation_id VARCHAR(128) NOT NULL UNIQUE, user_id VARCHAR(64), summary LONGTEXT NOT NULL, "
                + "last_message_id BIGINT, version INT NOT NULL, summary_msg_count INT NOT NULL, "
                + "detail_msg_count INT NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/ai_chat_memory_mapper.xml"),
                new ClassPathResource("mybatis/mapper/ai_chat_memory_summary_mapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(IAiChatMemoryDao.class);
        summaries = session.getMapper(IAiChatMemorySummaryDao.class);
        transactions = new DataSourceTransactionManager(ds);
        cache = mock(MemoryCacheService.class);
        summarizer = mock(IConversationSummarizer.class);
        service = newService(summarizer);
    }

    @After
    public void teardown() {
        workers.shutdownNow();
        jdbc.execute("DROP ALL OBJECTS");
    }

    private ConversationTurnMemoryService newService(IConversationSummarizer engine) {
        ConversationTurnMemoryService result = new ConversationTurnMemoryService();
        ReflectionTestUtils.setField(result, "aiChatMemoryDao", messages);
        ReflectionTestUtils.setField(result, "summaryDao", summaries);
        ReflectionTestUtils.setField(result, "transactionManager", transactions);
        ReflectionTestUtils.setField(result, "memoryCache", cache);
        ReflectionTestUtils.setField(result, "summarizer", engine);
        ReflectionTestUtils.setField(result, "triggerThreshold", 1000);
        ReflectionTestUtils.setField(result, "keepRecent", 2);
        return result;
    }

    private ExecuteCommandEntity request(String user, String run) {
        return ExecuteCommandEntity.builder().tenantId("tenant").userId(user).sessionId("session")
                .runId(run).aiAgentId("agent").message("question").build();
    }

    private void seed(int turns) {
        for (int i = 0; i < turns; i++) service.saveFinalTurn(request("alice", "run" + i), "answer" + i);
        clearInvocations(cache);
    }

    private void summarize(ConversationTurnMemoryService target) {
        ReflectionTestUtils.setField(target, "triggerThreshold", 2);
        ReflectionTestUtils.invokeMethod(target, "doSummarize", C, "alice");
    }

    private MemoryCacheService validatingCache(StringRedisTemplate redis) {
        MemoryCacheService result = new MemoryCacheService();
        ReflectionTestUtils.setField(result, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(result, "aiChatMemoryDao", messages);
        ReflectionTestUtils.setField(result, "summaryDao", summaries);
        ReflectionTestUtils.setField(result, "enabled", true);
        ReflectionTestUtils.setField(result, "ttlSeconds", 3600L);
        return result;
    }

    @Test
    public void commitPrecedesCacheInvalidationAndEmptySummaryIsHarmless() {
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2, messages.findByConversationIdFull(C).size());
            return null;
        }).when(cache).evictChatList(C);

        service.saveFinalTurn(request("alice", "run"), "answer");

        verify(cache).evictChatList(C);
        verify(cache, never()).putChatList(anyString(), anyList());
        AiChatMemorySummary empty = summaries.findByConversationId(C);
        assertEquals("", empty.getSummary());
        assertEquals(Integer.valueOf(0), empty.getVersion());
        assertEquals(Integer.valueOf(0), empty.getSummaryMsgCount());
        verifyNoInteractions(summarizer);
    }

    @Test
    public void insertFailureRollsBackBothMessagesAndLockRowWithoutPublishingCache() {
        IAiChatMemoryDao failing = mock(IAiChatMemoryDao.class, delegatesTo(messages));
        doAnswer(invocation -> {
            messages.insertBatch(invocation.getArgument(0));
            throw new IllegalStateException("simulated database write failure");
        }).when(failing).insertBatch(anyList());
        ReflectionTestUtils.setField(service, "aiChatMemoryDao", failing);

        assertThrows(IllegalStateException.class, () -> service.saveFinalTurn(request("alice", "run"), "answer"));

        assertTrue(messages.findByConversationIdFull(C).isEmpty());
        assertNull(summaries.findByConversationId(C));
        verifyNoInteractions(cache, summarizer);
    }

    @Test
    public void retriesAreIdempotentAndRunIdentityIsScopedToFullConversation() {
        service.saveFinalTurn(request("alice", "run"), "first answer");
        service.saveFinalTurn(request("alice", "run"), "retry answer");
        service.saveFinalTurn(request("bob", "run"), "another user");
        assertEquals(2, messages.findByConversationIdFull(C).size());
        assertEquals("first answer", messages.findByConversationIdFull(C).get(1).getContent());
        assertEquals(2, messages.findByConversationIdFull("tenant:bob:session").size());
    }

    @Test
    public void retryRepairsMissingAssistantWithoutDuplicatingUser() {
        service.saveFinalTurn(request("alice", "run"), "first answer");
        jdbc.update("DELETE FROM ai_chat_memory WHERE conversation_id=? AND message_type='ASSISTANT'", C);
        service.saveFinalTurn(request("alice", "run"), "recovered answer");
        List<AiChatMemory> rows = messages.findByConversationIdFull(C);
        assertEquals(2, rows.size());
        assertEquals("USER", rows.get(0).getMessageType());
        assertEquals("recovered answer", rows.get(1).getContent());
    }

    @Test
    public void clockSkewCannotInsertNewTurnBeforeExistingWatermark() {
        service.saveFinalTurn(request("alice", "old"), "old answer");
        jdbc.update("UPDATE ai_chat_memory SET created_at='2099-01-01 00:00:00' WHERE conversation_id=?", C);
        service.saveFinalTurn(request("alice", "new"), "new answer");
        List<AiChatMemory> rows = messages.findByConversationIdFull(C);
        assertEquals("old", rows.get(0).getRunId());
        assertEquals("old", rows.get(1).getRunId());
        assertEquals("new", rows.get(2).getRunId());
        assertEquals("new", rows.get(3).getRunId());
    }

    @Test
    public void concurrentInstancesSerializeSameRunAndDistinctTurnsInDatabase() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> pending = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int run = i / 2;
            ConversationTurnMemoryService independent = newService(summarizer);
            pending.add(workers.submit(() -> {
                await(start);
                independent.saveFinalTurn(request("alice", "concurrent" + run), "answer");
            }));
        }
        start.countDown();
        for (Future<?> task : pending) task.get(20, TimeUnit.SECONDS);
        List<AiChatMemory> rows = messages.findByConversationIdFull(C);
        assertEquals(16, rows.size());
        for (int i = 0; i < rows.size(); i += 2) {
            assertEquals("USER", rows.get(i).getMessageType());
            assertEquals("ASSISTANT", rows.get(i + 1).getMessageType());
            assertEquals(rows.get(i).getRunId(), rows.get(i + 1).getRunId());
        }
    }

    @Test
    public void summaryCommitsBeforeEvictionAndDoesNotHoldTransactionDuringGeneration() {
        seed(3);
        when(summarizer.summarizeWithPrevious(anyString(), anyList())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "summary";
        });
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Integer.valueOf(1), summaries.findByConversationId(C).getVersion());
            return null;
        }).when(cache).evictSummary(C);

        summarize(service);

        AiChatMemorySummary saved = summaries.findByConversationId(C);
        assertEquals(Integer.valueOf(2), saved.getSummaryMsgCount());
        assertEquals(messages.findByConversationIdFull(C).get(1).getId(), saved.getLastMessageId());
        verify(cache).evictSummary(C);
        verify(cache, never()).putSummary(anyString(), any());
    }

    @Test
    public void failedSummaryWriteKeepsOldWatermarkAndDoesNotPublishCache() {
        seed(3);
        when(summarizer.summarizeWithPrevious(anyString(), anyList())).thenReturn("summary");
        IAiChatMemorySummaryDao failing = mock(IAiChatMemorySummaryDao.class, delegatesTo(summaries));
        doThrow(new IllegalStateException("simulated summary failure"))
                .when(failing).advanceSummary(any(), anyInt(), anyInt());
        ReflectionTestUtils.setField(service, "summaryDao", failing);

        assertThrows(IllegalStateException.class, () -> summarize(service));

        assertEquals(Integer.valueOf(0), summaries.findByConversationId(C).getSummaryMsgCount());
        assertEquals(Integer.valueOf(0), summaries.findByConversationId(C).getVersion());
        verifyNoInteractions(cache);
    }

    @Test
    public void slowSummaryCannotRegressNewerVersionOrWatermark() throws Exception {
        seed(4);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IConversationSummarizer slow = mock(IConversationSummarizer.class);
        when(slow.summarizeWithPrevious(anyString(), anyList())).thenAnswer(invocation -> {
            generating.countDown();
            await(release);
            return "outdated";
        });
        Future<?> pending = workers.submit(() -> summarize(newService(slow)));
        assertTrue(generating.await(10, TimeUnit.SECONDS));
        try {
            when(summarizer.summarizeWithPrevious(anyString(), anyList())).thenReturn("newer1", "newer2");
            summarize(service);
            summarize(service);
        } finally {
            release.countDown();
        }
        pending.get(10, TimeUnit.SECONDS);

        AiChatMemorySummary saved = summaries.findByConversationId(C);
        assertEquals("newer2", saved.getSummary());
        assertEquals(Integer.valueOf(2), saved.getVersion());
        assertEquals(Integer.valueOf(4), saved.getSummaryMsgCount());
        verify(cache, times(2)).evictSummary(C);
    }

    @Test
    public void deletedAndRecreatedConversationRejectsOldSummary() {
        seed(3);
        when(summarizer.summarizeWithPrevious(anyString(), anyList())).thenAnswer(invocation -> {
            messages.deleteByConversationId(C);
            summaries.deleteByConversationId(C);
            ConversationTurnMemoryService other = newService(summarizer);
            other.saveFinalTurn(request("alice", "newrun"), "new history");
            return "deleted history";
        });

        summarize(service);

        assertEquals("", summaries.findByConversationId(C).getSummary());
        assertEquals(Integer.valueOf(0), summaries.findByConversationId(C).getVersion());
        verify(cache, never()).evictSummary(C);
    }

    @Test
    public void rejectedSummaryTaskDoesNotUndoSavedTurnAndCanBeRetried() {
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
        ReflectionTestUtils.setField(service, "threadPoolExecutor", executor);
        ReflectionTestUtils.setField(service, "triggerThreshold", 0);

        service.saveFinalTurn(request("alice", "run"), "answer");
        service.summarizeAsync(C);

        assertEquals(2, messages.findByConversationIdFull(C).size());
        verify(executor, times(2)).execute(any());
        verifyNoInteractions(summarizer);
    }

    @Test
    public void redisFailureCannotLoseCommittedRowsAndEvictionIncludesDerivedCount() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MemoryCacheService realCache = new MemoryCacheService();
        ReflectionTestUtils.setField(realCache, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(realCache, "enabled", true);
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("redis unavailable"));
        ReflectionTestUtils.setField(service, "memoryCache", realCache);

        service.saveFinalTurn(request("alice", "run"), "answer");

        assertEquals(2, messages.findByConversationIdFull(C).size());
        verify(redis).delete(List.of("memory:chat:list:" + C, "episodic:msg_count:" + C));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedInvalidationAndLateRefillCannotHideCommittedTurn() {
        seed(1);
        List<AiChatMemory> old = messages.findByConversationIdFull(C);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("memory:chat:list:" + C)).thenReturn(JSON.toJSONString(old));
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("failed invalidation"));
        MemoryCacheService checked = validatingCache(redis);
        assertEquals(2, checked.getChatList(C).size());
        ReflectionTestUtils.setField(service, "memoryCache", checked);

        service.saveFinalTurn(request("alice", "newrun"), "new answer");

        // The old Redis value also models a DB reader refilling after the writer's invalidation.
        assertNull(checked.getChatList(C));
        MyBatisChatMemoryRepository reader = new MyBatisChatMemoryRepository();
        ReflectionTestUtils.setField(reader, "aiChatMemoryDao", messages);
        ReflectionTestUtils.setField(reader, "memoryCache", checked);
        assertEquals(4, reader.findByConversationId(C).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void staleSummaryCacheIsRejectedAfterAdvanceAndAfterRecreate() {
        seed(3);
        when(summarizer.summarizeWithPrevious(anyString(), anyList())).thenReturn("summary1", "summary2");
        summarize(service);
        AiChatMemorySummary old = summaries.findByConversationId(C);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("memory:chat:summary:" + C)).thenReturn(JSON.toJSONString(old));
        MemoryCacheService checked = validatingCache(redis);
        assertEquals("summary1", checked.getSummary(C).getSummary());

        summarize(service);
        assertNull(checked.getSummary(C));
        // Row identity rejects an ABA collision even when the replacement has the same revision values.
        summaries.deleteByConversationId(C);
        summaries.ensureConversation(C, "alice");
        assertEquals(1, summaries.advanceSummary(old, 0, 0));
        assertNull(checked.getSummary(C));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unavailableDatabaseCannotReturnUnvalidatedCachedHistory() {
        seed(1);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("memory:chat:list:" + C)).thenReturn(JSON.toJSONString(messages.findByConversationIdFull(C)));
        MemoryCacheService checked = validatingCache(redis);
        IAiChatMemoryDao unavailable = mock(IAiChatMemoryDao.class);
        when(unavailable.findRevision(C)).thenThrow(new IllegalStateException("db unavailable"));
        when(unavailable.findByConversationId(C)).thenThrow(new IllegalStateException("db unavailable"));
        ReflectionTestUtils.setField(checked, "aiChatMemoryDao", unavailable);
        MyBatisChatMemoryRepository reader = new MyBatisChatMemoryRepository();
        ReflectionTestUtils.setField(reader, "aiChatMemoryDao", unavailable);
        ReflectionTestUtils.setField(reader, "memoryCache", checked);

        assertThrows(IllegalStateException.class, () -> reader.findByConversationId(C));
    }

    @Test
    public void episodicCounterUsesCommittedRevisionInsteadOfStaleRedisCounter() {
        seed(2);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AgentRepository repository = new AgentRepository();
        ReflectionTestUtils.setField(repository, "aiChatMemoryDao", messages);
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redis);
        assertEquals(4, repository.countChatMemoryByConversationId(C));
        verifyNoInteractions(redis);
    }

    @Test
    public void queuedSummarySurvivesForegroundFinishWithOriginalSharedBudget() {
        seed(2);
        String runId = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(runId, "alice", new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 2, 2, 1000));
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        doAnswer(invocation -> { queued.set(invocation.getArgument(0)); return null; }).when(executor).execute(any());
        ReflectionTestUtils.setField(service, "threadPoolExecutor", executor);
        ReflectionTestUtils.setField(service, "triggerThreshold", 2);
        when(summarizer.summarizeWithPrevious(anyString(), anyList())).thenAnswer(invocation -> {
            assertSame(budget, RunBudgetRegistry.find(Map.of()));
            budget.reserveModel(100, 100).settle(150L);
            return "queued summary";
        });
        MDC.put("runId", runId);
        try { service.summarizeAsync(C); } finally { MDC.remove("runId"); }
        RunBudgetRegistry.finish(runId);

        queued.get().run();

        assertEquals("queued summary", summaries.findByConversationId(C).getSummary());
        assertEquals(150L, budget.snapshot().get("confirmedTokens"));
        assertNull("maintenance scope must not leak to next task", RunBudgetRegistry.find(Map.of()));
    }

    @Test
    public void cancelledQueuedSummaryReleasesGateForNextRun() {
        seed(2);
        String runId = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(runId, "alice", new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 2, 2, 1000));
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        doAnswer(invocation -> { queued.set(invocation.getArgument(0)); return null; }).when(executor).execute(any());
        ReflectionTestUtils.setField(service, "threadPoolExecutor", executor);
        MDC.put("runId", runId);
        try { service.summarizeAsync(C); } finally { MDC.remove("runId"); }
        budget.cancel();
        queued.get().run();
        verifyNoInteractions(summarizer);

        service.summarizeAsync(C);
        verify(executor, times(2)).execute(any());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("concurrent test timed out", latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
