package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ToolResultReferenceService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.ToolEvidenceRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** In-memory evidence only; no Spring context, Redis or remote tools. */
public class ToolResultReferenceServiceTest {
    static final Map<String, Object> SCOPE = Map.of("agent.run_id", "run-a", "userId", "alice");
    static final ObjectMapper JSON = new ObjectMapper();

    static class Store {
        final RunSnapshot snapshot = RunSnapshot.builder().runId("run-a").userId("alice")
                .expiresAt(System.currentTimeMillis() + 60_000).build();
        final RunSnapshotService snapshots = mock(RunSnapshotService.class, CALLS_REAL_METHODS);
        final ToolResultReferenceService service = new ToolResultReferenceService(snapshots);
        Store() {
            when(snapshots.find(anyString())).thenAnswer(call -> "run-a".equals(call.getArgument(0))
                    ? Optional.of(snapshot) : Optional.empty());
            doAnswer(call -> { snapshot.getToolEvidences().add(call.getArgument(1)); return null; })
                    .when(snapshots).recordToolEvidence(anyString(), any(ToolEvidenceRecord.class));
        }
        String save(String raw) {
            return service.store(SCOPE, "call-a", "search", "{}", raw, "step", 1).resultRef();
        }
    }

    @After public void clearMdc() { MDC.clear(); }

    @Test public void ownedOriginalCanBeReadByJsonPointerAndPagedWithoutRepeatingTool() throws Exception {
        Store store = new Store();
        String raw = "{\"items\":[{\"title\":\"abcdef\",\"hidden\":42}]}";
        assertEquals("tool:call-a", store.save(raw));
        JsonNode first = JSON.readTree(store.service.inspect("{\"resultRef\":\"tool:call-a\",\"jsonPointer\":\"/items/0/title\",\"limit\":3}", SCOPE));
        assertEquals("abc", first.path("data").asText());
        assertTrue(first.path("hasMore").asBoolean());
        assertEquals(3, first.path("nextOffset").asInt());
        JsonNode second = JSON.readTree(store.service.inspect("{\"resultRef\":\"tool:call-a\",\"jsonPointer\":\"/items/0/title\",\"offset\":3,\"limit\":3}", SCOPE));
        assertEquals("def", second.path("data").asText());
        assertFalse(second.path("hasMore").asBoolean());
        assertEquals(raw, store.snapshot.getToolEvidences().get(0).getOutput());
    }

    @Test public void anotherOwnerAnotherRunAndExpiredSnapshotCannotRead() throws Exception {
        Store store = new Store(); store.save("private result");
        String args = "{\"resultRef\":\"tool:call-a\"}";
        for (Map<String,Object> scope : java.util.List.of(
                Map.<String,Object>of("agent.run_id", "run-a", "userId", "bob"),
                Map.<String,Object>of("agent.run_id", "run-b", "userId", "alice"))) {
            assertEquals("RESULT_NOT_AVAILABLE", JSON.readTree(store.service.inspect(args, scope)).path("error").asText());
        }
        store.snapshot.setExpiresAt(System.currentTimeMillis() - 1);
        assertEquals("RESULT_NOT_AVAILABLE", JSON.readTree(store.service.inspect(args, SCOPE)).path("error").asText());
        assertEquals("MISSING_RUN_SCOPE", JSON.readTree(store.service.inspect(args, Map.of())).path("error").asText());
    }

    @Test public void rejectsMalformedAndUnboundedRequests() throws Exception {
        Store store = new Store(); store.save("{\"a\":1}");
        for (String args : java.util.List.of("[1]", "{}", "{", "{\"resultRef\":\"tool:call-a\"} {}",
                "{\"resultRef\":\"tool:call-a\",\"runId\":\"run-b\"}",
                "{\"resultRef\":\"tool:call-a\",\"script\":\"return all\"}",
                "{\"resultRef\":\"tool:call-a\",\"limit\":4001}",
                "{\"resultRef\":\"tool:call-a\",\"offset\":-1}",
                "{\"resultRef\":\"tool:call-a\",\"offset\":2147483648}",
                "{\"resultRef\":\"tool:call-a\",\"limit\":1.5}",
                "{\"resultRef\":\"tool:call-a\",\"jsonPointer\":true}",
                "{\"resultRef\":\"tool:call-a\",\"jsonPointer\":\"/missing\"}")) {
            JsonNode response = JSON.readTree(store.service.inspect(args, SCOPE));
            assertFalse(args, response.path("ok").asBoolean(true));
            assertFalse(args, response.has("data"));
        }
    }

    @Test public void refusesUnreadableStorageAndNeverAdvertisesAFakeReference() throws Exception {
        Store store = new Store();
        doReturn(false).when(store.snapshots).saveToolEvidence(anyString(), anyString(), any());
        String raw = "x".repeat(7000);
        var stored = store.service.store(SCOPE, "call-a", "search", "{}", raw, "step", 1);
        assertFalse(stored.stored());
        JsonNode projection = JSON.readTree(store.service.project("search", raw, raw, stored));
        assertFalse(projection.has("resultRef"));
        assertFalse(projection.path("resultAvailable").asBoolean());
        assertTrue(projection.toString().length() <= 6000);
        assertTrue(projection.path("summary").asText().length() > 5000);
        assertEquals("RESULT_NOT_STORED", projection.path("reason").asText());
        // A reported write alone is insufficient: an owner-scoped read must also succeed.
        doReturn(true).when(store.snapshots).saveToolEvidence(anyString(), anyString(), any());
        assertFalse(store.service.store(SCOPE, "call-a", "search", "{}", raw, "step", 1).stored());
    }

    @Test public void oversizeOriginalIsNotWrittenAndCompactedResultRemainsUseful() throws Exception {
        Store store = new Store(); String raw = "x".repeat(262145);
        var stored = store.service.store(SCOPE, "call-a", "route", "{}", raw, "step", 1);
        assertEquals("RESULT_STORAGE_LIMIT", stored.reason());
        verify(store.snapshots, never()).recordToolEvidence(anyString(), any());
        JsonNode projection = JSON.readTree(store.service.project("route", raw, "route summary", stored));
        assertEquals("route summary", projection.path("summary").asText());
        assertFalse(projection.has("resultRef"));
    }

    @Test public void shortResultUnchangedAndUnicodePageBoundariesDoNotCorruptText() throws Exception {
        Store store = new Store(); store.save("a😀b");
        assertEquals("ok", store.service.project("search", "ok", "ok", null));
        JsonNode first = JSON.readTree(store.service.inspect("{\"resultRef\":\"tool:call-a\",\"limit\":2}", SCOPE));
        assertEquals("a", first.path("data").asText());
        assertEquals(1, first.path("nextOffset").asInt());
        JsonNode next = JSON.readTree(store.service.inspect("{\"resultRef\":\"tool:call-a\",\"offset\":1,\"limit\":2}", SCOPE));
        assertEquals("😀", next.path("data").asText());
        assertEquals("INVALID_RANGE", JSON.readTree(store.service.inspect("{\"resultRef\":\"tool:call-a\",\"offset\":2}", SCOPE)).path("error").asText());
    }

    @Test public void escapedProjectionAndPagesFitTheSerializedModelBudget() throws Exception {
        Store store = new Store(); String raw = "\u0001\"\\".repeat(4000);
        var stored = store.service.store(SCOPE, "call-a", "search", "{}", raw, "step", 1);
        String projection = store.service.project("search", raw, raw, stored);
        assertTrue(projection.length() <= 6000);
        assertEquals("tool:call-a", JSON.readTree(projection).path("resultRef").asText());
        String page = store.service.inspect("{\"resultRef\":\"tool:call-a\",\"limit\":4000}", SCOPE);
        assertTrue(page.length() <= 6000);
        JsonNode output = JSON.readTree(page);
        assertEquals(raw.substring(0, output.path("nextOffset").asInt()), output.path("data").asText());
    }
}
