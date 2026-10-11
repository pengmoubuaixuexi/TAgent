package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageContext;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** request_tool may run on a worker with no MDC; embedding still belongs to its real run. */
public class RequestToolAccountingScopeTest {
    @Test
    public void explicitToolContextSuppliesEmbeddingBudgetAndRestoresWorkerMdc() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        var budget = run("owner");
        var accounting = mock(ModelUsageAccounting.class);
        ModelUsageLedger.install(accounting);
        try {
            MDC.clear();
            MDC.put("userId", "worker-before");
            MDC.put("agentId", "worker-agent");
            var catalog = mock(McpToolCatalogService.class);
            when(catalog.resolveDynamicToolCallbacks(any(), any(), any(), any(), any(), anyList())).thenAnswer(call -> {
                assertSame(budget, RunBudgetRegistry.find(Map.of()));
                assertEquals("owner", MDC.get("userId"));
                assertEquals("agent", MDC.get("agentId"));
                ModelRequestGuard.account(RunBudgetRegistry.find(Map.of()),
                        ModelUsageContext.api(null, "EMBEDDING", "https://example.test"), "embedding", 20, 0);
                return List.of();
            });
            manager(catalog).executeToolCalls(prompt(budget.runId(), "owner"), requestTool());
            var attempt = org.mockito.ArgumentCaptor.forClass(ModelUsageAccounting.Attempt.class);
            verify(accounting).begin(attempt.capture());
            assertEquals(budget.runId(), attempt.getValue().runId());
            assertEquals("owner", attempt.getValue().userId());
            assertEquals("EMBEDDING", attempt.getValue().purpose());
            assertNull(MDC.get("runId"));
            assertNull(MDC.get("agent.run_id"));
            assertEquals("worker-before", MDC.get("userId"));
            assertEquals("worker-agent", MDC.get("agentId"));
        } finally {
            ModelUsageLedger.uninstall(accounting);
            RunBudgetRegistry.finish(budget.runId());
            restore(previous);
        }
    }

    @Test
    public void unknownExplicitRunCannotBorrowAnUnrelatedWorkerBudget() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        var priorBudget = run("prior-owner");
        try {
            MDC.put("runId", priorBudget.runId());
            MDC.put("agent.run_id", priorBudget.runId());
            var catalog = mock(McpToolCatalogService.class);
            when(catalog.resolveDynamicToolCallbacks(any(), any(), any(), any(), any(), anyList())).thenAnswer(call -> {
                assertNull("unknown explicit run must stay unaccounted and fail closed at HTTP guard",
                        RunBudgetRegistry.find(Map.of()));
                return List.of();
            });
            manager(catalog).executeToolCalls(prompt("missing-" + UUID.randomUUID(), "owner"), requestTool());
            verify(catalog).resolveDynamicToolCallbacks(any(), any(), any(), any(), any(), anyList());
            assertSame(priorBudget, RunBudgetRegistry.find(Map.of()));
        } finally {
            RunBudgetRegistry.finish(priorBudget.runId());
            restore(previous);
        }
    }

    @Test
    public void anotherOwnersRunCannotReachEmbeddingOrCatalog() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        var budget = run("owner");
        try {
            MDC.clear();
            var catalog = mock(McpToolCatalogService.class);
            manager(catalog).executeToolCalls(prompt(budget.runId(), "other-owner"), requestTool());
            verifyNoInteractions(catalog);
            assertNull(MDC.get("runId"));
            assertNull(MDC.get("userId"));
        } finally {
            RunBudgetRegistry.finish(budget.runId());
            restore(previous);
        }
    }

    private static RunBudgetRegistry.State run(String owner) {
        return RunBudgetRegistry.start("tool-accounting-" + UUID.randomUUID(), owner,
                new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 10, 10, 10000));
    }

    private static RobustToolCallingManager manager(McpToolCatalogService catalog) {
        var manager = new RobustToolCallingManager(mock(ToolCallingManager.class));
        manager.setMcpToolCatalogService(catalog);
        manager.setRequestToolEnabled(true);
        return manager;
    }

    private static Prompt prompt(String runId, String owner) {
        return new Prompt("query", OpenAiChatOptions.builder().toolContext(Map.of(
                "agent.run_id", runId, "userId", owner, "agentId", "agent",
                ToolCapabilities.TOOL_CONTEXT_KEY, "ALL")).build());
    }

    private static ChatResponse requestTool() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").properties(Map.of())
                .toolCalls(List.of(new AssistantMessage.ToolCall("load", "function", "request_tool",
                        "{\"needs\":[\"weather\"]}"))).build())));
    }

    private static void restore(Map<String, String> previous) {
        if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
    }
}
