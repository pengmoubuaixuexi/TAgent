package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import org.slf4j.MDC;
import reactor.core.Disposable;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Cancels the publication lookup as well as discarding results from a cancelled or exhausted run. */
final class RagReadGuard implements AutoCloseable {
    private final StreamingActivityTracker.Activity activity = StreamingActivityTracker.current();
    private final RunBudgetRegistry.State budget = RunBudgetRegistry.find(Map.of());
    private final AtomicReference<PreparedStatement> statement = new AtomicReference<>();
    private final Disposable activitySignal;
    private final Disposable budgetSignal;

    RagReadGuard() {
        check();
        activitySignal = activity == null ? null : activity.cancellation().subscribe(ignored -> cancel(), error -> cancel(), this::cancel);
        budgetSignal = budget == null ? null : budget.termination().subscribe(ignored -> cancel(), error -> cancel(), this::cancel);
    }

    String owner() {
        String owner = MDC.get("userId");
        if (budget != null) {
            if (owner != null && !owner.equals(budget.ownerUserId())) throw new SecurityException("运行与知识库访问归属不匹配");
            return budget.ownerUserId();
        }
        return owner;
    }

    void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("知识库查询已中断");
        StreamingActivityTracker.checkActive(activity);
        if (budget != null) budget.checkActive();
    }

    void attach(PreparedStatement query) throws java.sql.SQLException {
        statement.set(query);
        // Independent ceiling also bounds non-streaming reads with no run budget.
        query.setQueryTimeout(5);
        check();
    }

    private void cancel() {
        PreparedStatement query = statement.get();
        if (query != null) try { query.cancel(); } catch (java.sql.SQLException ignored) { /* post-query check remains authoritative */ }
    }

    @Override public void close() {
        statement.set(null);
        if (activitySignal != null) activitySignal.dispose();
        if (budgetSignal != null) budgetSignal.dispose();
    }
}
