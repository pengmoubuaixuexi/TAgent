package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RunBudgetTest {
    @Test public void trustedMaintenanceSharesBudgetAfterForegroundFinishes() {
        String id = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(id, "a", new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 3, 3, 100));
        budget.reserveModel(20, 10).settle(25L);
        Runnable work;
        org.slf4j.MDC.put("runId", id);
        try { work = RunBudgetRegistry.captureMaintenance(() -> {
            assertSame(budget, RunBudgetRegistry.find(Map.of()));
            budget.reserveModel(20, 10).settle(27L);
        }); } finally { org.slf4j.MDC.remove("runId"); }
        RunBudgetRegistry.finish(id);
        assertThrows(RunBudgetExceededException.class, budget::checkActive);
        work.run();
        assertEquals(52L, budget.snapshot().get("confirmedTokens"));
        assertEquals(2, budget.snapshot().get("modelCalls"));
        assertNull(RunBudgetRegistry.find(Map.of()));
        assertThrows(IllegalStateException.class, work::run);
        org.slf4j.MDC.put("runId", id);
        try { assertThrows(RunBudgetExceededException.class, () -> RunBudgetRegistry.captureMaintenance(() -> {})); }
        finally { org.slf4j.MDC.remove("runId"); }
    }
    @Test public void cancellationAndDeadlineRevokeQueuedMaintenance() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            String id = UUID.randomUUID().toString();
            var budget = RunBudgetRegistry.start(id, "a", new RunBudgetRegistry.Limits(Duration.ofMillis(100), 3, 3, 100));
            AtomicInteger calls = new AtomicInteger();
            Runnable work;
            org.slf4j.MDC.put("runId", id);
            try { work = RunBudgetRegistry.captureMaintenance(calls::incrementAndGet); }
            finally { org.slf4j.MDC.remove("runId"); }
            RunBudgetRegistry.finish(id);
            if (cancel) {
                budget.cancel();
                assertThrows(java.util.concurrent.CancellationException.class, work::run);
            } else {
                Thread.sleep(120);
                assertThrows(RunBudgetExceededException.class, work::run);
            }
            assertEquals(0, calls.get());
            assertNull(RunBudgetRegistry.find(Map.of()));
        }
    }
    private RunBudgetRegistry.State state(int models, int tools, long tokens) {
        return RunBudgetRegistry.start(UUID.randomUUID().toString(), "owner",
                new RunBudgetRegistry.Limits(Duration.ofSeconds(30), models, tools, tokens));
    }
    @Test public void parallelReservationsCannotSpendTheSameTokens() throws Exception {
        var budget = state(100, 100, 1000);
        var workers = Executors.newFixedThreadPool(8);
        AtomicInteger admitted = new AtomicInteger();
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0; i<20; i++) jobs.add(workers.submit(() -> {
                try { budget.reserveModel(80, 20); admitted.incrementAndGet(); }
                catch (RunBudgetExceededException expected) {}
            }));
            for (var job : jobs) job.get();
            assertEquals(10, admitted.get());
            assertEquals(1000L, budget.snapshot().get("reservedTokens"));
        } finally { workers.shutdownNow(); }
    }
    @Test public void retryAndToolsNeverResetCounters() {
        var budget = state(2, 2, 1000);
        budget.reserveModel(10, 10).settle(15L);
        budget.reserveModel(10, 10).settle(null);
        assertThrows(RunBudgetExceededException.class, () -> budget.reserveModel(1, 1));
        assertEquals(2, budget.snapshot().get("modelCalls"));
        assertEquals(1, budget.snapshot().get("unknownUsageCalls"));
        assertThrows(RunBudgetExceededException.class, budget::reserveTool);
    }
    @Test public void actualUsageSettlementIsIdempotentAndUnknownIsNotFree() {
        var budget = state(5, 5, 1000);
        var first = budget.reserveModel(100, 100);
        first.settle(150L); first.settle(10L);
        var unknown = budget.reserveModel(100, 100);
        unknown.settle(null); unknown.settle(null);
        assertEquals(350L, budget.snapshot().get("reservedTokens"));
        assertEquals(150L, budget.snapshot().get("confirmedTokens"));
        assertEquals(1, budget.snapshot().get("unknownUsageCalls"));
    }
    @Test public void continuousOutputDoesNotExtendDeadline() {
        var budget = RunBudgetRegistry.start(UUID.randomUUID().toString(), "u",
                new RunBudgetRegistry.Limits(Duration.ofMillis(120), 10, 10, 1000));
        assertThrows(RunBudgetExceededException.class, () -> Flux.interval(Duration.ofMillis(5))
                .takeUntilOther(budget.termination()).blockLast(Duration.ofSeconds(2)));
        assertThrows(RunBudgetExceededException.class, budget::reserveTool);
    }
    @Test public void ownershipAndTerminalStateAreEnforced() {
        String id = UUID.randomUUID().toString();
        var limits = new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 5, 5, 1000);
        RunBudgetRegistry.start(id, "a", limits);
        assertThrows(SecurityException.class, () -> RunBudgetRegistry.find(Map.of("agent.run_id", id, "userId", "b")));
        RunBudgetRegistry.finish(id);
        assertThrows(RunBudgetExceededException.class, () -> RunBudgetRegistry.start(id, "a", limits));
    }
    @Test public void reviewedPlanRetainsConsumedBudgetAndCancellationCannotResume() {
        String id = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(id, "a", new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 2, 2, 1000));
        budget.reserveModel(10,10).settle(15L);
        budget.awaitReview();
        RunBudgetRegistry.finish(id);
        assertTrue(budget.waitingForReview());
        budget.resumeReview();
        assertEquals(1, budget.snapshot().get("modelCalls"));
        budget.reserveModel(10,10);
        assertThrows(RunBudgetExceededException.class, () -> budget.reserveModel(1,1));
        var cancelled = state(3,3,1000);
        cancelled.awaitReview(); cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, cancelled::resumeReview);
    }
}
