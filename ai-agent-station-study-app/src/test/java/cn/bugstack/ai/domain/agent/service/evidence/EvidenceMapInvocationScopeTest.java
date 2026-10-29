package cn.bugstack.ai.domain.agent.service.evidence;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageContext;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A post-chat map must have an authenticated, metered model operation of its own. */
public class EvidenceMapInvocationScopeTest {
    @After public void cleanup() { MDC.clear(); }

    @Test public void wireGuardStillRejectsMissingScopeAndMetersTheNewOwnedOperation() {
        var ledger = mock(ModelUsageAccounting.class);
        var ticket = mock(ModelUsageAccounting.Ticket.class);
        when(ledger.begin(any())).thenReturn(ticket);
        ModelUsageLedger.install(ledger);
        try {
            var provider = new ModelUsageContext("platform-api", "CHAT_HELPER", "provider.test");
            assertThrows(SecurityException.class,
                    () -> ModelRequestGuard.account(null, provider, "helper", 100, 200));
            verify(ledger, never()).begin(any());
            Fixture f = new Fixture(); MDC.put("userId", "alice");
            when(f.gateway.call(any())).thenAnswer(invocation -> {
                var budget = RunBudgetRegistry.find(Map.of());
                assertSame(ticket, ModelRequestGuard.account(budget, provider, "helper", 100, 200));
                ticket.settle(150L, "SUCCESS");
                return response();
            });
            f.service.generate("source", "session", "answer");
            var attempt = org.mockito.ArgumentCaptor.forClass(ModelUsageAccounting.Attempt.class);
            verify(ledger).begin(attempt.capture());
            assertEquals("alice", attempt.getValue().userId());
            assertTrue(attempt.getValue().runId().startsWith("model:EVIDENCE_MAP:"));
            assertEquals("CHAT_HELPER", attempt.getValue().purpose());
            assertEquals("platform-api", attempt.getValue().apiId());
            verify(ticket).settle(150L, "SUCCESS");
            assertNull(MDC.get("runId"));
        } finally { ModelUsageLedger.uninstall(ledger); }
    }

    @Test public void completedSourceGetsSeparateBudgetAndRestoresRequestContext() {
        Fixture f = new Fixture();
        String source = "finished-source-" + UUID.randomUUID();
        f.budgets.start(source, "alice"); RunBudgetRegistry.finish(source);
        MDC.put("userId", "alice"); MDC.put("traceId", "request-trace");
        AtomicReference<String> operation = new AtomicReference<>();
        when(f.gateway.call(any())).thenAnswer(invocation -> {
            var budget = RunBudgetRegistry.find(Map.of());
            assertNotNull(budget); budget.checkActive();
            assertEquals("alice", budget.ownerUserId());
            assertNotEquals(source, budget.runId());
            assertEquals("EVIDENCE_MAP", MDC.get("modelPurpose"));
            operation.set(budget.runId());
            return response();
        });
        var map = f.service.generate("source", "session", "## Result\nA claim.");
        assertNotNull(map); assertNotNull(operation.get());
        assertNull(MDC.get("runId")); assertNull(MDC.get("modelPurpose"));
        assertEquals("alice", MDC.get("userId")); assertEquals("request-trace", MDC.get("traceId"));
        try { RunBudgetRegistry.find(operation.get()).checkActive(); fail("standalone budget must close"); }
        catch (RuntimeException expected) { assertTrue(expected.getMessage().contains("结束")); }
        verify(f.snapshots).saveEvidenceMap(eq("source"), anyString(), anyMap());
    }

    @Test public void missingIdentityNeverInvokesProviderOrCachesAResult() {
        Fixture f = new Fixture();
        try { f.service.generate("source", "session", "answer"); fail("identity required"); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().contains("身份")); }
        verifyNoInteractions(f.gateway);
        verify(f.snapshots, never()).saveEvidenceMap(anyString(), anyString(), anyMap());
    }

    @Test public void providerFailureClosesBudgetAndDoesNotReplacePriorMap() {
        Fixture f = new Fixture(); MDC.put("userId", "alice");
        AtomicReference<String> operation = new AtomicReference<>();
        when(f.gateway.call(any())).thenAnswer(invocation -> {
            operation.set(RunBudgetRegistry.find(Map.of()).runId());
            throw new IllegalStateException("synthetic provider failure");
        });
        try { f.service.generate("source", "session", "answer", true); fail("provider must fail"); }
        catch (IllegalStateException expected) { assertEquals("synthetic provider failure", expected.getMessage()); }
        assertNull(MDC.get("runId")); assertNull(MDC.get("modelPurpose"));
        try { RunBudgetRegistry.find(operation.get()).checkActive(); fail("budget must close"); }
        catch (RuntimeException expected) { assertTrue(expected.getMessage().contains("结束")); }
        verify(f.snapshots, never()).saveEvidenceMap(anyString(), anyString(), anyMap());
    }

    @Test public void cacheHitNeverAllocatesBudgetOrCallsModel() {
        Fixture f = new Fixture(); MDC.put("userId", "alice");
        when(f.snapshots.findEvidenceMap(eq("source"), anyString()))
            .thenReturn(Optional.of(new LinkedHashMap<>(Map.of("runId", "source", "claims", List.of()))));
        assertEquals(true, f.service.generate("source", "session", "answer").get("cached"));
        assertNull(RunBudgetRegistry.find(Map.of())); verifyNoInteractions(f.gateway);
    }

    @Test public void explicitUnsupportedCheckIsDifferentFromAnOmittedBlock() {
        Fixture f = new Fixture(); MDC.put("userId", "alice");
        when(f.gateway.call(any())).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"claims\":[{\"claimId\":\"B1\",\"checks\":[{\"text\":\"Claim A.\",\"links\":[]}]}]}")))));
        var map = f.service.generate("source", "session", "## First\nClaim A.\n\n## Second\nClaim B.");
        var claims = (List<?>) map.get("claims");
        assertEquals("unsupported", ((Map<?, ?>) claims.get(0)).get("status"));
        assertEquals("unverified", ((Map<?, ?>) claims.get(1)).get("status"));
        var stats = (Map<?, ?>) map.get("stats");
        assertEquals(1, stats.get("unsupportedClaims")); assertEquals(1, stats.get("unverifiedClaims"));
        var diagnostics = (Map<?, ?>) map.get("diagnostics");
        assertEquals("MODEL_INCOMPLETE", diagnostics.get("stage"));
        assertEquals(1, diagnostics.get("acceptedClaims")); assertEquals(2, diagnostics.get("displayedClaims"));
    }

    @Test public void oldCachedPlaceholderIsReclassifiedWithoutASecondModelCall() {
        Fixture f = new Fixture(); MDC.put("userId", "alice");
        var old = new LinkedHashMap<String, Object>();
        old.put("generatedAt", "original-timestamp");
        old.put("claims", List.of(Map.of("claimId", "B1", "checks", List.of(), "links", List.of(), "status", "unsupported")));
        old.put("diagnostics", Map.of("stage", "COMPLETED", "modelClaims", 1));
        when(f.snapshots.findEvidenceMap(eq("source"), anyString())).thenReturn(Optional.of(old));
        var map = f.service.generate("source", "session", "answer");
        assertEquals("original-timestamp", map.get("generatedAt")); assertEquals(true, map.get("cached"));
        assertEquals("MODEL_INCOMPLETE", ((Map<?, ?>) map.get("diagnostics")).get("stage"));
        assertEquals(1, ((Map<?, ?>) map.get("stats")).get("unverifiedClaims"));
        assertEquals(0, ((Map<?, ?>) map.get("stats")).get("unsupportedClaims"));
        verifyNoInteractions(f.gateway);
        verify(f.snapshots, never()).saveEvidenceMap(anyString(), anyString(), anyMap());
    }

    @Test public void oldCacheEmptyArrayReferencesRemainArraysAndAreNotCountedAsChecked() {
        Fixture f = new Fixture(); MDC.put("userId", "alice");
        var old = new LinkedHashMap<String, Object>();
        old.put("claims", List.of(
                Map.of("claimId", "B1", "checks", List.of(), "links", Map.of("$ref", "$.data.claims[0].checks"), "status", "unsupported"),
                Map.of("claimId", "B2", "checks", Map.of("$ref", "$.data.claims[0].checks"), "links", Map.of("$ref", "$.data.claims[0].checks"), "status", "unsupported")));
        old.put("diagnostics", Map.of("stage", "COMPLETED", "modelClaims", 1));
        when(f.snapshots.findEvidenceMap(eq("source"), anyString())).thenReturn(Optional.of(old));
        var map = f.service.generate("source", "session", "answer");
        var stats = (Map<?, ?>) map.get("stats");
        assertEquals(2, stats.get("unverifiedClaims")); assertEquals(0, stats.get("unsupportedClaims"));
        for (Object item : (List<?>) map.get("claims")) {
            assertTrue(((Map<?, ?>) item).get("checks") instanceof List<?>);
            assertTrue(((Map<?, ?>) item).get("links") instanceof List<?>);
        }
        assertFalse(com.alibaba.fastjson.JSON.toJSONString(map).contains("\"$ref\""));
        verifyNoInteractions(f.gateway);
    }

    @Test public void finishedAmbientBudgetIsRejectedRatherThanReopened() {
        Fixture f = new Fixture(); String old = "finished-ambient-" + UUID.randomUUID();
        f.budgets.start(old, "alice"); RunBudgetRegistry.finish(old);
        MDC.put("userId", "alice"); MDC.put("runId", old);
        try { f.service.generate("source", "session", "answer"); fail("closed context must fail"); }
        catch (RuntimeException expected) { assertTrue(expected.getMessage().contains("结束")); }
        verifyNoInteractions(f.gateway);
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"claims\":[]}"))));
    }
    private static final class Fixture {
        final RunBudgetRegistry budgets = new RunBudgetRegistry();
        final RunSnapshotService snapshots = mock(RunSnapshotService.class);
        final LlmCallGateway gateway = mock(LlmCallGateway.class);
        final EvidenceMapService service = new EvidenceMapService();
        Fixture() {
            var context = mock(ApplicationContext.class);
            when(context.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName("router-small"), ChatClient.class))
                .thenReturn(mock(ChatClient.class));
            when(snapshots.find("source")).thenReturn(Optional.of(RunSnapshot.builder()
                .runId("source").sessionId("session").userId("alice").originalMessage("demo request")
                .status("COMPLETED").build()));
            ReflectionTestUtils.setField(service, "applicationContext", context);
            ReflectionTestUtils.setField(service, "runSnapshotService", snapshots);
            ReflectionTestUtils.setField(service, "llmCallGateway", gateway);
            ReflectionTestUtils.setField(service, "runBudgets", budgets);
            ReflectionTestUtils.setField(service, "clientId", "router-small");
        }
    }
}
