package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.*;
import cn.bugstack.ai.trigger.knowledge.KnowledgeController;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class KnowledgeAccessTest {
    private RagIngestionRepository records;
    private RagIngestionService ingestion;
    private RagFilePreviewService preview;
    private HybridRetriever hybrid;
    private ModelProviderAccess policy;
    private KnowledgeController controller;
    private Authentication alice;
    @Before public void setup() {
        records = mock(RagIngestionRepository.class); ingestion = mock(RagIngestionService.class);
        preview = mock(RagFilePreviewService.class); hybrid = mock(HybridRetriever.class); policy = mock(ModelProviderAccess.class);
        controller = new KnowledgeController(ingestion, records, preview, hybrid, new RunBudgetRegistry(), policy);
        alice = new UsernamePasswordAuthenticationToken("alice", "unused", List.of());
    }

    @Test public void anonymousCannotAccessListOrTaskAndPrincipalIsOnlyOwnerInput() {
        assertThrows(ResponseStatusException.class, () -> controller.list(null));
        assertThrows(ResponseStatusException.class, () -> controller.task(null, "known-id"));
        verifyNoInteractions(ingestion, records, preview, hybrid);
        when(ingestion.list("alice")).thenReturn(Map.of("tasks", List.of()));
        controller.list(alice); verify(ingestion).list("alice");
    }

    @Test public void foreignKnowledgeIsRejectedBeforeAnyModelWork() {
        assertThrows(RagStorageException.class, () -> controller.search(alice, new KnowledgeController.SearchTest("bobs-kb", "question")));
        verify(records).ownsKnowledge("alice", "bobs-kb"); verifyNoInteractions(policy, hybrid);
    }

    @Test public void retrievalTestAlwaysCarriesOwnerAndTagAndShowsBranchDegradation() {
        when(records.ownsKnowledge("alice", "kb")).thenReturn(true);
        when(hybrid.retrieveDetailed(anyString(), any(), anyString(), anyInt())).thenAnswer(call -> {
            assertEquals("alice", RunBudgetRegistry.find(Map.of()).ownerUserId());
            SearchRequest request = call.getArgument(1);
            String filter = request.getFilterExpression().toString();
            assertTrue(filter.contains("user_id")); assertTrue(filter.contains("alice")); assertTrue(filter.contains("knowledge")); assertTrue(filter.contains("kb"));
            return new HybridRetriever.RetrievalResult(List.of(), Map.of("vector", "timeout", "bm25", "success"), 12);
        });
        String result = controller.search(alice, new KnowledgeController.SearchTest("kb", "question")).toString();
        assertTrue(result.contains("degraded=true")); assertTrue(result.contains("timeout"));
        verify(policy).requirePlatformAuxiliary("alice", "EMBEDDING"); assertNull(RunBudgetRegistry.find(Map.of()));
    }

    @Test public void explicitProviderDenialAndAllBranchFailuresAreNeverSuccessfulEmptyResults() {
        when(records.ownsKnowledge("alice", "kb")).thenReturn(true);
        doThrow(new SecurityException("平台辅助模型未授权")).when(policy).requirePlatformAuxiliary("alice", "EMBEDDING");
        assertThrows(SecurityException.class, () -> controller.search(alice, new KnowledgeController.SearchTest("kb", "question")));
        verifyNoInteractions(hybrid);
        doNothing().when(policy).requirePlatformAuxiliary("alice", "EMBEDDING");
        when(hybrid.retrieveDetailed(anyString(), any(), anyString(), anyInt())).thenReturn(
                new HybridRetriever.RetrievalResult(List.of(), Map.of("vector", "failed", "bm25", "timeout"), 15));
        assertThrows(RagStorageException.class, () -> controller.search(alice, new KnowledgeController.SearchTest("kb", "question")));
    }

    @Test public void previewDoesNotReachVectorDatabaseWithoutOwnedFileOrTask() {
        JdbcTemplate mysql = mock(JdbcTemplate.class), vector = mock(JdbcTemplate.class);
        when(records.ownedFile("alice", "bobs-file")).thenReturn(null);
        var reader = new RagFilePreviewService(records, mysql, vector, "vector_store");
        assertThrows(RagStorageException.class, () -> reader.preview("alice", "bobs-file", 0));
        verifyNoInteractions(mysql, vector);
    }

    @Test public void previewBoundsContentAndUsesParameterizedOwnerTagAndFileIdentity() {
        JdbcTemplate mysql = mock(JdbcTemplate.class), vector = mock(JdbcTemplate.class);
        when(records.ownedFile("alice", "rf_file")).thenReturn(Map.of("knowledgeTag", "kb", "fileName", "notes.txt"));
        when(vector.queryForObject(anyString(), eq(Long.class), eq("alice"), eq("kb"), eq("rf_file"))).thenReturn(11L);
        when(vector.queryForList(anyString(), eq("alice"), eq("kb"), eq("rf_file"), eq(10))).thenReturn(List.of(Map.of("content", "fixture")));
        var result = new RagFilePreviewService(records, mysql, vector, "vector_store").preview("alice", "rf_file", 1);
        assertEquals(11L, result.get("total"));
        verify(vector).queryForList(argThat(sql -> sql.contains("LEFT(content,6000)") && sql.contains("metadata->>'user_id'=?")
                && sql.contains("metadata->>'knowledge'=?") && sql.contains("metadata->>'rag_file_id'=?") && sql.contains("LIMIT 10 OFFSET ?")), eq("alice"), eq("kb"), eq("rf_file"), eq(10));
        verifyNoInteractions(mysql, policy, hybrid);
    }

    @Test public void ambiguousLegacyPreviewCannotShowAnotherFilesChunks() {
        JdbcTemplate mysql = mock(JdbcTemplate.class), vector = mock(JdbcTemplate.class);
        when(records.ownedFile("alice", "legacy")).thenReturn(Map.of("knowledgeTag", "kb", "fileName", "same.txt"));
        when(mysql.queryForObject(anyString(), eq(Integer.class), eq("alice"), eq("kb"), eq("same.txt"))).thenReturn(2);
        assertThrows(RagStorageException.class, () -> new RagFilePreviewService(records, mysql, vector, "vector_store").preview("alice", "legacy", 0));
        verifyNoInteractions(vector);
    }
}
