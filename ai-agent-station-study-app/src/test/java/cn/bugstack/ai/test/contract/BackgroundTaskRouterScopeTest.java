package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageContext;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.trigger.background.BackgroundTaskCommandRouter;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BackgroundTaskRouterScopeTest {
    private static final String MESSAGE = "请创建一次性定时任务，三分钟后提醒我学习Python";
    private static final String RESULT = "{\"matched\":true,\"operation\":\"CREATE\",\"confidence\":1,\"needs_clarification\":false,\"task_draft\":{\"task_type\":\"SCHEDULE_ONCE\",\"name\":\"learn\",\"trigger\":{\"trigger_at\":\"2026-10-29T12:00:00\"},\"action_prompt\":\"start learning\",\"run_once\":true}}";

    @After public void clearContext() { MDC.clear(); }

    @Test public void standaloneRequestHasOwnedMeteredBudgetAndRestoresContext() {
        var f = new Fixture();
        var ledger = mock(ModelUsageAccounting.class);
        var ticket = mock(ModelUsageAccounting.Ticket.class);
        when(ledger.begin(any())).thenReturn(ticket);
        ModelUsageLedger.install(ledger);
        var run = new AtomicReference<String>();
        MDC.put("userId", "alice"); MDC.put("traceId", "request-trace");
        try {
            when(f.content.content()).thenAnswer(invocation -> {
                var budget = RunBudgetRegistry.find(Map.of());
                assertNotNull(budget); budget.checkActive();
                assertEquals("alice", budget.ownerUserId());
                assertEquals("BACKGROUND_TASK_ROUTER", MDC.get("modelPurpose"));
                run.set(budget.runId());
                assertSame(ticket, ModelRequestGuard.account(budget,
                        new ModelUsageContext("test-platform", "CHAT_HELPER", "test.provider"), "helper", 100, 200));
                ticket.settle(150L, "SUCCESS");
                return RESULT;
            });
            assertEquals("CREATE", f.router.route(MESSAGE, "session").getOperation());
            var attempt = org.mockito.ArgumentCaptor.forClass(ModelUsageAccounting.Attempt.class);
            verify(ledger).begin(attempt.capture());
            assertEquals("alice", attempt.getValue().userId());
            assertTrue(attempt.getValue().runId().startsWith("model:BACKGROUND_TASK_ROUTER:"));
            assertEquals("test-platform", attempt.getValue().apiId());
            assertEquals("alice", MDC.get("userId")); assertEquals("request-trace", MDC.get("traceId"));
            assertNull(MDC.get("runId")); assertNull(MDC.get("modelPurpose"));
            assertThrows(RuntimeException.class, () -> RunBudgetRegistry.find(run.get()).checkActive());
            verify(ticket).settle(150L, "SUCCESS");
        } finally { ModelUsageLedger.uninstall(ledger); }
    }

    @Test public void providerFailureClosesOwnedBudgetAndKeepsFallback() {
        var f = new Fixture(); var run = new AtomicReference<String>(); MDC.put("userId", "alice");
        when(f.content.content()).thenAnswer(invocation -> {
            run.set(RunBudgetRegistry.find(Map.of()).runId());
            throw new IllegalStateException("synthetic provider failure");
        });
        assertTrue(f.router.route(MESSAGE, "session").isNeedsClarification());
        assertNotNull(run.get());
        assertThrows(RuntimeException.class, () -> RunBudgetRegistry.find(run.get()).checkActive());
        assertNull(MDC.get("runId")); assertEquals("alice", MDC.get("userId"));
    }

    @Test public void missingIdentityNeverCallsProvider() {
        var f = new Fixture();
        assertTrue(f.router.route(MESSAGE, "session").isNeedsClarification());
        verifyNoInteractions(f.context);
        assertNull(RunBudgetRegistry.find(Map.of()));
    }

    @Test public void ordinaryQuestionDoesNotAllocateOrCallModel() {
        var f = new Fixture(); MDC.put("userId", "alice");
        assertFalse(f.router.route("解释Java的接口", "session").isMatched());
        verifyNoInteractions(f.context); assertNull(RunBudgetRegistry.find(Map.of()));
    }

    @Test public void activeSameOwnerBudgetIsReusedWithoutFinishingIt() {
        var f = new Fixture(); String run = "test-existing-" + UUID.randomUUID();
        f.budgets.start(run, "alice"); MDC.put("runId", run); MDC.put("userId", "alice");
        try {
            when(f.content.content()).thenReturn(RESULT);
            assertEquals("CREATE", f.router.route(MESSAGE, "session").getOperation());
            assertEquals(run, MDC.get("runId")); RunBudgetRegistry.find(run).checkActive();
        } finally { RunBudgetRegistry.finish(run); }
    }

    @Test public void mismatchedAmbientOwnerNeverCallsProvider() {
        var f = new Fixture(); String run = "test-wrong-owner-" + UUID.randomUUID();
        f.budgets.start(run, "bob"); MDC.put("runId", run); MDC.put("userId", "alice");
        try {
            assertTrue(f.router.route(MESSAGE, "session").isNeedsClarification());
            verifyNoInteractions(f.context); assertEquals("alice", MDC.get("userId"));
        } finally { RunBudgetRegistry.finish(run); }
    }

    private static class Fixture {
        final RunBudgetRegistry budgets = new RunBudgetRegistry();
        final ApplicationContext context = mock(ApplicationContext.class);
        final ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        final ChatClient.CallResponseSpec content = client.prompt().user(anyString()).call();
        final BackgroundTaskCommandRouter router = new BackgroundTaskCommandRouter();
        Fixture() {
            when(context.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName("router-small"), ChatClient.class)).thenReturn(client);
            ReflectionTestUtils.setField(router, "applicationContext", context);
            ReflectionTestUtils.setField(router, "runBudgets", budgets);
            ReflectionTestUtils.setField(router, "routerClientId", "router-small");
        }
    }
}
