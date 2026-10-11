package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolCallMetadata;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Actual external attempts, not retry branches, are bounded. No network or database. */
public class MeteredToolRetryBudgetTest {
    private static final McpToolCallMetadata READ_ONLY = new McpToolCallMetadata("mcp", true, false, null, false);

    @Test public void oneAttemptBudgetDoesNotProbeOrReconnectAfterTimeout() {
        ScriptedTool initial = new ScriptedTool(READ_ONLY, Integer.MAX_VALUE);
        FakeRegistry registry = new FakeRegistry(initial, new ScriptedTool(READ_ONLY, 0));
        assertThrows(RuntimeException.class, () -> metered(initial, registry, 1, false).call("{}"));
        assertEquals(1, initial.calls.get());
        assertEquals(0, registry.probes.get());
        assertEquals(0, registry.reconnects.get());
    }

    @Test public void liveProbeRetryCannotBypassTwoAttemptBudget() {
        ScriptedTool initial = new ScriptedTool(READ_ONLY, Integer.MAX_VALUE);
        FakeRegistry registry = new FakeRegistry(initial, new ScriptedTool(READ_ONLY, 0));
        assertThrows(RuntimeException.class, () -> metered(initial, registry, 2, false).call("{}"));
        assertEquals(2, initial.calls.get());
        assertEquals(1, registry.probes.get());
        assertEquals(0, registry.reconnects.get());
    }

    @Test public void reconnectConsumesThirdAttemptInsteadOfHiddenExtraCall() {
        ScriptedTool initial = new ScriptedTool(READ_ONLY, Integer.MAX_VALUE);
        ScriptedTool fresh = new ScriptedTool(READ_ONLY, 0);
        FakeRegistry registry = new FakeRegistry(initial, fresh);
        assertEquals("OK", metered(initial, registry, 3, false).call("{}"));
        assertEquals(2, initial.calls.get());
        assertEquals(1, fresh.calls.get());
        assertEquals(1, registry.probes.get());
        assertEquals(1, registry.reconnects.get());
    }

    @Test public void unknownWriteOutcomeDoesNotRetryAndTellsModelNotToResubmit() {
        ScriptedTool unknown = new ScriptedTool(ToolMetadata.builder().build(), Integer.MAX_VALUE);
        FakeRegistry registry = new FakeRegistry(unknown, new ScriptedTool(READ_ONLY, 0));
        String output = metered(unknown, registry, 5, true).call("{\"create\":true}");
        assertEquals(1, unknown.calls.get());
        assertEquals(0, registry.probes.get());
        assertEquals(0, registry.reconnects.get());
        assertTrue(output.contains("\"outcome\":\"unknown\""));
        assertTrue(output.contains("\"retryAllowed\":false"));
    }

    @Test public void destructiveHintOverridesClaimedIdempotency() {
        ScriptedTool destructive = new ScriptedTool(new McpToolCallMetadata("mcp", false, true, true, false), 1);
        assertThrows(MeteredToolCallback.ToolOutcomeUnknownException.class,
                () -> metered(destructive, null, 3, false).call("{}"));
        assertEquals(1, destructive.calls.get());
    }

    @Test public void explicitNonDestructiveIdempotencyAllowsBoundedRetry() {
        ScriptedTool idempotent = new ScriptedTool(new McpToolCallMetadata("mcp", false, false, true, false), 1);
        assertEquals("OK", metered(idempotent, null, 2, false).call("{}"));
        assertEquals(2, idempotent.calls.get());
    }

    @Test public void reconnectWithUnknownAnnotationsDoesNotReplayOperation() {
        ScriptedTool initial = new ScriptedTool(READ_ONLY, Integer.MAX_VALUE);
        ScriptedTool fresh = new ScriptedTool(ToolMetadata.builder().build(), 0);
        FakeRegistry registry = new FakeRegistry(initial, fresh);
        registry.alive = false;
        assertThrows(MeteredToolCallback.ToolOutcomeUnknownException.class,
                () -> metered(initial, registry, 2, false).call("{}"));
        assertEquals(1, initial.calls.get());
        assertEquals(0, fresh.calls.get());
    }

    @Test public void cancellationDuringFailedCallPreventsRecovery() {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.start("cancel-retry");
        ScriptedTool initial = new ScriptedTool(READ_ONLY, Integer.MAX_VALUE) {
            @Override public String call(String input) {
                activity.cancel();
                return super.call(input);
            }
        };
        FakeRegistry registry = new FakeRegistry(initial, new ScriptedTool(READ_ONLY, 0));
        try (StreamingActivityTracker.Scope ignored = StreamingActivityTracker.scope(activity)) {
            assertThrows(CancellationException.class, () -> metered(initial, registry, 3, true).call("{}"));
        }
        assertEquals(1, initial.calls.get());
        assertEquals(0, registry.probes.get());
        assertEquals(0, registry.reconnects.get());
    }

    @Test public void runBudgetCountsRetryAttemptsAndExhaustionIsNeverToolErrorText() {
        String runId = "mcp-budget-" + UUID.randomUUID();
        RunBudgetRegistry.State budget = RunBudgetRegistry.start(runId, "alice",
                new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 10, 1, 10000));
        ScriptedTool initial = new ScriptedTool(READ_ONLY, 1);
        ToolContext context = new ToolContext(Map.of("agent.run_id", runId, "userId", "alice"));
        try {
            assertThrows(RunBudgetExceededException.class,
                    () -> metered(initial, null, 3, true).call("{}", context));
            assertEquals(1, initial.calls.get());
            assertEquals(1, budget.snapshot().get("toolAttempts"));
        } finally { RunBudgetRegistry.finish(runId); }
    }

    @Test public void noContextCallsAlsoUseTheRunBudgetFromMdc() {
        String runId = "mcp-mdc-budget-" + UUID.randomUUID();
        RunBudgetRegistry.start(runId, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 10, 1, 10000));
        ScriptedTool initial = new ScriptedTool(READ_ONLY, 0);
        org.slf4j.MDC.put("runId", runId);
        try {
            MeteredToolCallback callback = metered(initial, null, 3, true);
            assertEquals("OK", callback.call("{}"));
            assertThrows(RunBudgetExceededException.class, () -> callback.call("{}"));
            assertEquals(1, initial.calls.get());
        } finally { org.slf4j.MDC.remove("runId"); RunBudgetRegistry.finish(runId); }
    }

    @Test public void confirmedSessionRejectionReconnectsEvenWithoutRetryAnnotations() {
        ScriptedTool initial = failingTool(ToolMetadata.builder().build(), sessionRejection());
        ScriptedTool fresh = new ScriptedTool(ToolMetadata.builder().build(), 0);
        FakeRegistry registry = new FakeRegistry(initial, fresh);

        assertEquals("OK", metered(initial, registry, 2, false).call("{}"));
        assertEquals(1, initial.calls.get());
        assertEquals(1, fresh.calls.get());
        assertEquals(1, registry.reconnects.get());
        assertEquals(0, registry.probes.get());
    }

    @Test public void rejectedWriteWasNotAcceptedButLaterLostResponseStillMustNotReplay() {
        McpToolCallMetadata write = new McpToolCallMetadata("mcp", false, true, false, false);
        ScriptedTool initial = failingTool(write, sessionRejection());
        ScriptedTool fresh = new ScriptedTool(write, Integer.MAX_VALUE);
        FakeRegistry registry = new FakeRegistry(initial, fresh);

        String output = metered(initial, registry, 5, true).call("{}");
        assertEquals(1, initial.calls.get());
        assertEquals(1, fresh.calls.get());
        assertEquals(1, registry.reconnects.get());
        assertTrue(output.contains("\"outcome\":\"unknown\""));
        assertTrue(output.contains("\"retryAllowed\":false"));
        assertTrue(output.contains("TimeoutException"));
    }

    @Test public void generic400AndUnstructuredSessionTextNeverAuthorizeUnknownWriteReplay() {
        for (String message : List.of(
                "Sending message failed with a non-OK HTTP code: 400",
                transportError(400, "{\"message\":\"invalid arguments\"}"),
                transportError(500, "{\"message\":\"Session ID not found, Please reconnect\"}"),
                transportError(400, "{\"message\":\"write failed\",\"stackTrace\":\"Session ID not found, Please reconnect\"}"),
                transportError(400, "{\"message\":\"Session ID not found, Please reconnect\",\"jsonRpcError\":{\"code\":-32603}}"),
                transportError(400, "{\"message\":\"Session ID not found, Please reconnect\""),
                "Connection reset: Session ID not found, Please reconnect")) {
            ScriptedTool initial = failingTool(ToolMetadata.builder().build(), new RuntimeException(message));
            ScriptedTool fresh = new ScriptedTool(READ_ONLY, 0);
            FakeRegistry registry = new FakeRegistry(initial, fresh);
            assertThrows(message, MeteredToolCallback.ToolOutcomeUnknownException.class,
                    () -> metered(initial, registry, 3, false).call("{}"));
            assertEquals(message, 1, initial.calls.get());
            assertEquals(message, 0, registry.reconnects.get());
            assertEquals(message, 0, fresh.calls.get());
        }
    }

    @Test public void sessionRejectionNeverRetriesTheSameSessionWithoutSuccessfulReconnect() {
        ScriptedTool initial = failingTool(ToolMetadata.builder().build(), sessionRejection());
        String noRegistry = metered(initial, null, 5, true).call("{}");
        assertEquals(1, initial.calls.get());
        assertTrue(noRegistry.contains("MCP 会话已失效"));
        assertFalse(noRegistry.contains("\"outcome\":\"unknown\""));

        FakeRegistry registry = new FakeRegistry(initial, null);
        String reconnectFailed = metered(initial, registry, 5, true).call("{}");
        assertEquals(2, initial.calls.get());
        assertEquals(1, registry.reconnects.get());
        assertTrue(reconnectFailed.contains("MCP 会话已失效"));
    }

    @Test public void sessionRejectionRespectsSingleAttemptAndRepeatedRejectionBudgets() {
        ScriptedTool initial = failingTool(ToolMetadata.builder().build(), sessionRejection());
        ScriptedTool fresh = failingTool(ToolMetadata.builder().build(), sessionRejection());
        FakeRegistry registry = new FakeRegistry(initial, fresh);
        metered(initial, registry, 1, true).call("{}");
        assertEquals(1, initial.calls.get());
        assertEquals(0, registry.reconnects.get());

        String output = metered(initial, registry, 3, true).call("{}");
        assertEquals(2, initial.calls.get());
        assertEquals(2, fresh.calls.get());
        assertEquals(2, registry.reconnects.get());
        assertTrue(output.contains("HTTP 400"));
        assertFalse(output.contains("\"outcome\":\"unknown\""));
    }

    @Test public void sessionRecoveryStillConsumesTheRunToolQuota() {
        String runId = "mcp-session-budget-" + UUID.randomUUID();
        RunBudgetRegistry.State budget = RunBudgetRegistry.start(runId, "alice",
                new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 10, 1, 10000));
        ScriptedTool initial = failingTool(ToolMetadata.builder().build(), sessionRejection());
        ScriptedTool fresh = new ScriptedTool(ToolMetadata.builder().build(), 0);
        FakeRegistry registry = new FakeRegistry(initial, fresh);
        ToolContext context = new ToolContext(Map.of("agent.run_id", runId, "userId", "alice"));
        try {
            assertThrows(RunBudgetExceededException.class,
                    () -> metered(initial, registry, 3, true).call("{}", context));
            assertEquals(1, initial.calls.get());
            assertEquals(0, fresh.calls.get());
            assertEquals(1, budget.snapshot().get("toolAttempts"));
        } finally { RunBudgetRegistry.finish(runId); }
    }

    @Test public void failureDiagnosticsKeepOnlySanitizedReasonForModelAndProgress() {
        RuntimeException error = new RuntimeException("SDK wrapper", new RuntimeException(transportError(400,
                "{\"message\":\"upstream unavailable api_key=KEY_SECRET Authorization: Bearer BEARER_SECRET https://example.invalid?key=URL_SECRET\","
                        + "\"stackTrace\":[\"STACK_SECRET\"],\"cause\":{\"password\":\"BODY_SECRET\"}}")));
        ScriptedTool initial = failingTool(ToolMetadata.builder().build(), error);
        MeteredToolCallback callback = metered(initial, null, 3, true);
        ToolCallProgressEmitter progress = org.mockito.Mockito.mock(ToolCallProgressEmitter.class);
        callback.setToolCallProgressEmitter(progress);

        String output = callback.call("{}", new ToolContext(Map.of("sessionId", "session-1")));
        org.mockito.ArgumentCaptor<String> diagnostic = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(progress).emitError(org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("example_tool"), diagnostic.capture(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString());
        for (String text : List.of(output, diagnostic.getValue())) {
            assertTrue(text.contains("MCP HTTP 400"));
            assertTrue(text.contains("upstream unavailable"));
            for (String secret : List.of("KEY_SECRET", "BEARER_SECRET", "URL_SECRET", "STACK_SECRET", "BODY_SECRET")) {
                assertFalse(text, text.contains(secret));
            }
        }
    }

    @Test public void sessionDiagnosticOmitsProviderStackTraceAndOtherEnvelopeFields() {
        RuntimeException error = new RuntimeException(transportError(400,
                "{\"message\":\"Session ID not found, Please reconnect\",\"stackTrace\":[\"STACK_SECRET\"],\"password\":\"BODY_SECRET\"}"));
        ScriptedTool initial = failingTool(ToolMetadata.builder().build(), error);
        String output = metered(initial, null, 1, true).call("{}");
        assertTrue(output.contains("MCP 会话已失效"));
        assertTrue(output.contains("Session ID not found, Please reconnect"));
        assertFalse(output.contains("STACK_SECRET"));
        assertFalse(output.contains("BODY_SECRET"));
    }

    private static String transportError(int status, String body) {
        return "Sending message failed with a non-OK HTTP code: " + status + " - " + body;
    }

    private static RuntimeException sessionRejection() {
        return new RuntimeException("SDK wrapper", new RuntimeException(transportError(400,
                "{\"cause\":null,\"stackTrace\":[],\"jsonRpcError\":null,\"message\":\"Session ID not found, Please reconnect\","
                        + "\"suppressed\":[],\"localizedMessage\":\"Session ID not found, Please reconnect\"}")));
    }

    private static ScriptedTool failingTool(ToolMetadata metadata, RuntimeException error) {
        return new ScriptedTool(metadata, 0) {
            @Override public String call(String input) {
                calls.incrementAndGet();
                throw error;
            }
        };
    }

    private MeteredToolCallback metered(ToolCallback tool, McpClientRegistry registry, int attempts, boolean returnError) {
        return new MeteredToolCallback(tool, new McpToolMetrics(new SimpleMeterRegistry()),
                returnError, false, 10, 20_000, false, true, attempts, 1, registry, "mcp");
    }

    private static class ScriptedTool implements ToolCallback {
        final ToolMetadata metadata;
        final int failures;
        final AtomicInteger calls = new AtomicInteger();
        ScriptedTool(ToolMetadata metadata, int failures) { this.metadata = metadata; this.failures = failures; }
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("example_tool").description("offline test").inputSchema("{}").build();
        }
        @Override public ToolMetadata getToolMetadata() { return metadata; }
        @Override public String call(String input) {
            if (calls.incrementAndGet() <= failures) throw new RuntimeException(new TimeoutException("response lost"));
            return "OK";
        }
    }

    private static class FakeRegistry extends McpClientRegistry {
        final ToolCallback initial;
        final ToolCallback fresh;
        boolean alive = true;
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger reconnects = new AtomicInteger();
        FakeRegistry(ToolCallback initial, ToolCallback fresh) { this.initial = initial; this.fresh = fresh; }
        @Override public void assertAccessible(String mcpId, String userId, String agentId) {}
        @Override public ToolCallback getCurrentCallback(String mcpId, String toolName) { return initial; }
        @Override public boolean probeAfterTimeout(String mcpId, String toolName) { probes.incrementAndGet(); return alive; }
        @Override public ToolCallback reconnectAfterTimeout(String mcpId, String toolName) { reconnects.incrementAndGet(); return fresh; }
        @Override public ToolCallback forceReconnect(String mcpId, String toolName) { reconnects.incrementAndGet(); return fresh; }
    }
}
