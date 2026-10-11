package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.rag.RagDocumentVisibility;
import cn.bugstack.ai.domain.agent.service.router.LlmRagRouter;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.*;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RagPublicationGuardTest {
    private Document candidate() { return new Document("private", Map.of("user_id", "alice", "knowledge", "kb", "rag_file_id", "rf_fixture")); }

    @Test public void cancelledReadNeverStartsVectorOrPublicationSql() {
        var raw = mock(VectorStore.class); var visible = mock(RagDocumentVisibility.class); var db = mock(JdbcTemplate.class);
        var activity = StreamingActivityTracker.start("cancelled"); activity.cancel();
        try (var ignored = StreamingActivityTracker.scope(activity)) {
            assertThrows(CancellationException.class, () -> new PublishedRagVectorStore(raw, visible).similaritySearch(SearchRequest.builder().query("x").build()));
            assertThrows(CancellationException.class, () -> new PublishedRagDocuments(db).published(List.of(candidate())));
        }
        verifyNoInteractions(raw, visible, db);
    }

    @Test public void cancellationAfterVectorReturnCannotPublishItsResults() {
        var activity = StreamingActivityTracker.start("returned-after-cancel"); var raw = mock(VectorStore.class); var visible = mock(RagDocumentVisibility.class);
        when(raw.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> { activity.cancel(); return List.of(candidate()); });
        try (var ignored = StreamingActivityTracker.scope(activity)) {
            assertThrows(CancellationException.class, () -> new PublishedRagVectorStore(raw, visible).similaritySearch(SearchRequest.builder().query("x").build()));
        }
        verifyNoInteractions(visible);
    }

    @Test public void budgetAndTrustedOwnerAreRevalidatedBeforePublication() {
        String run = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 2, 2, 100));
        var db = mock(JdbcTemplate.class); var visibility = new PublishedRagDocuments(db);
        MDC.put("runId", run); MDC.put("userId", "bob");
        try {
            assertThrows(SecurityException.class, () -> visibility.published(List.of(candidate())));
            MDC.put("userId", "alice"); budget.stop("test budget exhausted");
            assertThrows(RunBudgetExceededException.class, () -> visibility.published(List.of(candidate())));
        } finally { MDC.remove("runId"); MDC.remove("userId"); }
        verifyNoInteractions(db);
    }

    @Test public void cancellingBlockedPublicationCancelsStatementAndKeepsTerminalException() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(statement);
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        doAnswer(call -> { cancelled.countDown(); return null; }).when(statement).cancel();
        when(statement.executeQuery()).thenAnswer(call -> {
            entered.countDown(); assertTrue(cancelled.await(3, TimeUnit.SECONDS)); throw new SQLException("cancelled fixture", "57014");
        });
        var activity = StreamingActivityTracker.start("blocked-publication"); var worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = worker.submit(() -> {
                try (var ignored = StreamingActivityTracker.scope(activity)) {
                    new PublishedRagDocuments(new JdbcTemplate(source)).published(List.of(candidate()));
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS)); activity.cancel();
            var error = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof CancellationException);
            verify(statement).setQueryTimeout(5); verify(statement, atLeastOnce()).cancel(); verify(statement).close();
        } finally { activity.cancel(); worker.shutdownNow(); }
    }

    @Test public void routerDoesNotTurnExhaustionOrCancellationIntoFallbackRetrieval() {
        var router = new LlmRagRouter(); var context = mock(ApplicationContext.class); var gateway = mock(LlmCallGateway.class);
        ReflectionTestUtils.setField(router, "applicationContext", context); ReflectionTestUtils.setField(router, "llmCallGateway", gateway);
        ReflectionTestUtils.setField(router, "enabled", true); ReflectionTestUtils.setField(router, "routerEnabled", true);
        when(context.getBean(anyString(), eq(ChatClient.class))).thenReturn(mock(ChatClient.class));
        when(gateway.call(any())).thenThrow(new RunBudgetExceededException("stop"));
        assertThrows(RunBudgetExceededException.class, () -> router.decide("find private knowledge"));
        doThrow(new CancellationException("stop")).when(gateway).call(any());
        assertThrows(CancellationException.class, () -> router.decide("find private knowledge"));
    }
}
