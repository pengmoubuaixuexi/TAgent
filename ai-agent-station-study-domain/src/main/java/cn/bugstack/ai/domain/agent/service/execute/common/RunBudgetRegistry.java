package cn.bugstack.ai.domain.agent.service.execute.common;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One shared budget for routing, parallel steps, tool retries and model tool loops. */
@Component
public final class RunBudgetRegistry {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunBudgetRegistry.class);
    private static final ConcurrentHashMap<String, State> RUNS = new ConcurrentHashMap<>();
    private static final ThreadLocal<State> MAINTENANCE_SCOPE = new ThreadLocal<>();
    @Value("${agent.run-budget.max-seconds:1800}") private long maxSeconds = 1800;
    @Value("${agent.run-budget.max-model-calls:60}") private int maxModels = 60;
    @Value("${agent.run-budget.max-tool-calls:100}") private int maxTools = 100;
    @Value("${agent.run-budget.max-tokens:500000}") private long maxTokens = 500000;
    @Value("${agent.context.max-input-tokens:${agent.context.max-input-estimate:128000}}") private int inputLimit = 128000;
    @Value("${agent.context.default-output-limit:8192}") private int outputLimit = 8192;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelContextSettings contextSettings;

    @jakarta.annotation.PostConstruct
    public void configure() {
        ModelRequestGuard.configure(inputLimit, outputLimit,
                contextSettings == null ? Map.of() : contextSettings.getModelContextWindows());
    }

    public State start(String runId, String userId) {
        return start(runId, userId, new Limits(Duration.ofSeconds(maxSeconds), maxModels, maxTools, maxTokens));
    }

    public static State start(String runId, String userId, Limits limits) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        RUNS.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().createdAt > Duration.ofHours(24).toMillis());
        if (RUNS.size() >= 10000 && !RUNS.containsKey(runId)) throw new IllegalStateException("运行预算容量已满，请稍后重试");
        State result = RUNS.computeIfAbsent(runId, ignored -> {
            State state = new State(runId, userId, limits);
            cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger.openRun(runId,userId,state.createdAt+limits.duration().toMillis());
            return state;
        });
        result.requireOwner(userId);
        result.checkActive();
        return result;
    }

    public static State find(Map<String, Object> context) {
        Object explicit = context == null ? null : context.get("agent.run_id");
        String runId = explicit == null ? MDC.get("runId") : String.valueOf(explicit);
        if (runId == null) runId = MDC.get("agent.run_id");
        State state = explicit == null && MAINTENANCE_SCOPE.get() != null ? MAINTENANCE_SCOPE.get()
                : runId == null ? null : RUNS.get(runId);
        if (state != null) {
            // Auxiliary calls rely on propagated MDC, so validate that identity as well.
            // A server-assembled explicit ToolContext is authoritative when entering a worker
            // scope; that scope replaces any stale MDC after checking the explicit owner.
            String mdcUser = MDC.get("userId");
            if (explicit == null && mdcUser != null && !mdcUser.isBlank()) state.requireOwner(mdcUser);
            if (context != null && context.get("userId") != null)
                state.requireOwner(String.valueOf(context.get("userId")));
        }
        return state;
    }

    public static State find(String runId) { return runId == null ? null : RUNS.get(runId); }

    public static void finish(String runId) {
        State state = find(runId);
        if (state != null) state.finishForeground();
    }

    /**
     * Capture before queueing a known maintenance task. The single-use permit survives normal foreground
     * completion, but never cancellation, hard budget exhaustion or the original absolute deadline.
     * It cannot be minted after foreground completion or transferred by ordinary MDC propagation.
     */
    public static Runnable captureMaintenance(Runnable task) {
        java.util.Objects.requireNonNull(task, "task");
        State state = find(Map.of());
        if (state == null) return task;
        synchronized (state) {
            state.checkActive();
            if (state.foregroundFinished) throw new RunBudgetExceededException("任务已结束，不能新增维护任务");
        }
        var used = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!used.compareAndSet(false, true)) throw new IllegalStateException("维护任务许可只能执行一次");
            State previous = MAINTENANCE_SCOPE.get();
            try {
                MAINTENANCE_SCOPE.set(state);
                state.checkActive();
                task.run();
            } finally {
                if (previous == null) MAINTENANCE_SCOPE.remove(); else MAINTENANCE_SCOPE.set(previous);
            }
        };
    }

    public record Limits(Duration duration, int modelCalls, int toolCalls, long tokens) {
        public Limits {
            if (duration == null || duration.isNegative() || duration.isZero() || modelCalls < 1 || toolCalls < 1 || tokens < 1)
                throw new IllegalArgumentException("Run budget limits must be positive");
        }
    }

    public static final class State {
        private final String runId;
        private final String userId;
        private final Limits limits;
        private final long createdAt = System.currentTimeMillis();
        private final long deadline;
        private final Sinks.One<Void> stopped = Sinks.one();
        private final Sinks.One<Void> foregroundEnded = Sinks.one();
        private int models;
        private int tools;
        private long reservedTokens;
        private long confirmedTokens;
        private int unknownUsage;
        private String stopReason;
        private boolean waitingForReview;
        private boolean cancelled;
        private boolean foregroundFinished;
        private String persistedTerminalState;

        State(String runId, String userId, Limits limits) {
            this.runId = runId;
            this.userId = userId;
            this.limits = limits;
            this.deadline = System.nanoTime() + limits.duration().toNanos();
        }

        private void requireOwner(String owner) {
            if (!java.util.Objects.equals(userId, owner)) throw new SecurityException("运行预算归属不匹配");
        }

        public String ownerUserId() { return userId; }
        public String runId() { return runId; }

        public synchronized void checkActive() {
            if (cancelled) throw new java.util.concurrent.CancellationException("任务已取消");
            if (stopReason != null) throw new RunBudgetExceededException(stopReason);
            if (System.nanoTime() >= deadline) exhaust("任务已达到总时限（包括人工等待），已停止后续调用");
            if (foregroundFinished && MAINTENANCE_SCOPE.get() != this)
                throw new RunBudgetExceededException("任务已结束，拒绝继续执行");
        }

        private synchronized void finishForeground() {
            if (!waitingForReview && !foregroundFinished) {
                foregroundFinished = true;
                foregroundEnded.tryEmitEmpty();
            }
            if (foregroundFinished) persistTerminal(cancelled ? "CANCELLED" : stopReason != null ? "EXHAUSTED" : "COMPLETED");
        }

        public synchronized void reserveTool() {
            checkActive();
            if (tools >= limits.toolCalls()) exhaust("任务已达到工具实际调用次数上限");
            tools++;
        }

        public synchronized Reservation reserveModel(long inputEstimate, long outputLimit) {
            checkActive();
            long requested = Math.addExact(Math.max(0, inputEstimate), Math.max(0, outputLimit));
            if (models >= limits.modelCalls()) exhaust("任务已达到模型请求次数上限");
            if (requested > limits.tokens() - reservedTokens) {
                long held = Math.max(0, reservedTokens - confirmedTokens);
                log.warn("Run Token budget rejected: runId={} limit={} confirmed={} held={} requested={} modelCalls={} unknownUsageCalls={}",
                        runId, limits.tokens(), confirmedTokens, held, requested, models, unknownUsage);
                exhaust("任务剩余 Token 预算不足，已停止后续调用（单次任务上限 " + limits.tokens()
                        + "，已确认 " + confirmedTokens + "，在途或未确认预留 " + held
                        + "，下一次请求需预留 " + requested + "；累计模型请求 " + models + " 次）");
            }
            models++;
            reservedTokens += requested;
            return new Reservation(this, requested);
        }

        public synchronized Map<String, Object> snapshot() {
            return Map.of("modelCalls", models, "toolAttempts", tools, "reservedTokens", reservedTokens,
                    "confirmedTokens", confirmedTokens, "unknownUsageCalls", unknownUsage,
                    "maxTokens", limits.tokens(), "stopReason", stopReason == null ? "" : stopReason,
                    "remainingMillis", Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L));
        }

        public synchronized void awaitReview() {
            checkActive();
            cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger.changeRunState(runId,userId,"WAITING_REVIEW");
            waitingForReview = true;
        }
        public synchronized boolean waitingForReview() { return waitingForReview; }
        public synchronized void resumeReview() {
            checkActive();
            cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger.changeRunState(runId,userId,"ACTIVE");
            waitingForReview = false;
        }
        public synchronized void cancel() { cancelled = true; waitingForReview = false; stop("任务已取消"); }

        public Mono<Void> termination() {
            boolean maintenance = MAINTENANCE_SCOPE.get() == this;
            long remaining = Math.max(1, deadline - System.nanoTime());
            Mono<Void> signal = Mono.firstWithSignal(stopped.asMono(), Mono.delay(Duration.ofNanos(remaining)).then());
            if (!maintenance) signal = Mono.firstWithSignal(signal, foregroundEnded.asMono());
            return signal
                    .then(Mono.defer(() -> {
                        synchronized (this) {
                            if (cancelled) return Mono.error(new java.util.concurrent.CancellationException("任务已取消"));
                            if (stopReason == null && !maintenance && foregroundFinished)
                                return Mono.error(new RunBudgetExceededException("任务已结束，拒绝继续执行"));
                            if (stopReason == null) stop("任务已达到总时限（包括人工等待）");
                            return Mono.error(new RunBudgetExceededException(stopReason));
                        }
                    }));
        }

        public synchronized void stop(String reason) {
            if (stopReason == null) {
                stopReason = reason; stopped.tryEmitEmpty();
            }
            persistTerminal(cancelled ? "CANCELLED" : "EXHAUSTED");
        }

        /** Local stop signals cannot depend on database availability. A later terminal call retries;
         * absent a retry, the durable slot remains conservatively occupied until its original deadline. */
        private void persistTerminal(String state) {
            if (state.equals(persistedTerminalState)) return;
            try {
                cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger.changeRunState(runId,userId,state);
                persistedTerminalState = state;
            } catch (RuntimeException unavailable) {
                log.warn("Run usage terminal update pending: runId={} state={} failureType={}",
                        runId, state, unavailable.getClass().getSimpleName());
            }
        }

        private void exhaust(String reason) { stop(reason); throw new RunBudgetExceededException(reason); }
    }

    /** Unknown usage conservatively retains the reserved amount instead of claiming a free call. */
    public static final class Reservation {
        private final State state;
        private final long amount;
        private boolean settled;
        Reservation(State state, long amount) { this.state = state; this.amount = amount; }
        public void settle(Long actualTokens) {
            synchronized (state) {
                if (settled) return;
                settled = true;
                if (actualTokens == null || actualTokens < 0) { state.unknownUsage++; return; }
                state.confirmedTokens += actualTokens;
                state.reservedTokens += actualTokens - amount;
                if (state.reservedTokens > state.limits.tokens()) state.stop("实际用量已达到任务 Token 预算上限");
            }
        }
    }
}
