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
    @Test public void parallelCallsAreAdmittedOnUsageAndStopBeyondTheCeiling() throws Exception {
        var budget = state(100, 100, 1000);
        var workers = Executors.newFixedThreadPool(8);
        AtomicInteger admitted = new AtomicInteger();
        var reservations = new java.util.concurrent.ConcurrentLinkedQueue<RunBudgetRegistry.Reservation>();
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0; i<20; i++) jobs.add(workers.submit(() -> {
                try { reservations.add(budget.reserveModel(80, 25600)); admitted.incrementAndGet(); }
                catch (RunBudgetExceededException unexpected) {}
            }));
            for (var job : jobs) job.get();
            assertEquals("calls in flight do not hold the output limit", 20, admitted.get());
            assertEquals("only input estimates are held", 1600L, budget.snapshot().get("reservedTokens"));
            reservations.forEach(r -> r.settle(80L));
            assertThrows("usage beyond 120% of the budget stops the run", RunBudgetExceededException.class, () -> budget.reserveModel(1, 1));
        } finally { workers.shutdownNow(); }
    }
    @Test public void budgetWarnsAtEightyMarksAtTheBudgetAndStopsBeyondTheCeiling() {
        var events = new java.util.ArrayList<String>();
        RunBudgetRegistry.listener(new RunBudgetRegistry.Listener() {
            @Override public void warned(RunBudgetRegistry.State state, long used, long budget) { events.add("warned " + used + "/" + budget); }
            @Override public void reached(RunBudgetRegistry.State state, String reason) { events.add("reached " + reason); }
        });
        try {
            var budget = state(100, 100, 1000);
            budget.reserveModel(100, 25600).settle(700L);
            assertTrue(events.isEmpty());
            budget.reserveModel(100, 25600).settle(150L);
            budget.reserveModel(100, 25600).settle(10L);
            assertEquals("warned once", java.util.List.of("warned 850/1000"), events);
            assertNull(budget.budgetReached());
            budget.reserveModel(100, 25600).settle(200L);
            assertEquals("本次任务的 Token 预算（1000）已用完", budget.budgetReached());
            assertEquals(2, events.size());
            // Marked, the phase in progress may still call the model, up to the hard ceiling.
            budget.reserveModel(100, 25600).settle(100L);
            assertEquals(2, events.size());
            budget.reserveModel(100, 25600).settle(100L);
            var stop = assertThrows(RunBudgetExceededException.class, () -> budget.reserveModel(1, 1));
            assertTrue(stop.getMessage(), stop.getMessage().contains("120%"));
            assertEquals(1200L, budget.snapshot().get("hardTokens"));
        } finally { RunBudgetRegistry.listener(null); }
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
        // The unknown call keeps its input estimate as usage; the output limit was never held.
        assertEquals(250L, budget.snapshot().get("reservedTokens"));
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
