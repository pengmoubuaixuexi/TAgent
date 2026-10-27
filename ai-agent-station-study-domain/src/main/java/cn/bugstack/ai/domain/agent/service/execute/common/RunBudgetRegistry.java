package cn.bugstack.ai.domain.agent.service.execute.common;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One shared budget for routing, parallel steps, tool retries and model tool loops.
 * <p>Tokens are admitted on usage, not on a reservation of the output limit. Reaching the budget marks the
 * run: Flow and Auto finish the phase in progress, start no new one and answer from what is done. Only
 * usage beyond the hard ceiling ({@code overrun-ratio} above the budget) stops a run mid-phase.
 */
@Component
public final class RunBudgetRegistry {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunBudgetRegistry.class);
    private static final ConcurrentHashMap<String, State> RUNS = new ConcurrentHashMap<>();
    private static final ThreadLocal<State> MAINTENANCE_SCOPE = new ThreadLocal<>();
    /** Share of the token budget a run may overrun to finish its current phase. */
    private static volatile double overrunRatio = 0.2;
    private static volatile Listener listener;
    @Value("${agent.run-budget.max-seconds:1800}") private long maxSeconds = 1800;
    @Value("${agent.run-budget.max-model-calls:60}") private int maxModels = 60;
    @Value("${agent.run-budget.max-tool-calls:100}") private int maxTools = 100;
    @Value("${agent.run-budget.max-tokens:500000}") private long maxTokens = 500000;
    @Value("${agent.context.max-input-tokens:${agent.context.max-input-estimate:128000}}") private int inputLimit = 128000;
    @Value("${agent.context.default-output-limit:8192}") private int outputLimit = 8192;
    @Value("${agent.run-budget.overrun-ratio:0.2}") private double configuredOverrunRatio = 0.2;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelContextSettings contextSettings;

    @jakarta.annotation.PostConstruct
    public void configure() {
        ModelRequestGuard.configure(inputLimit, outputLimit,
                contextSettings == null ? Map.of() : contextSettings.getModelContextWindows());
        overrunRatio(configuredOverrunRatio);
    }

    public static void overrunRatio(double ratio) {
        if (ratio < 0 || ratio > 1) throw new IllegalArgumentException("overrun-ratio must be between 0 and 1");
        overrunRatio = ratio;
    }

    public static double overrunRatio() { return overrunRatio; }

    /** Notified outside the budget's lock when a run nears or reaches its budget. */
    public interface Listener {
        void warned(State state, long used, long budget);
        void reached(State state, String reason);
    }

    public static void listener(Listener value) { listener = value; }

    /** Notice above completed results delivered without a final model call. */
    public static final String UNSUMMARIZED_NOTICE = "⚠️ 本次任务的 Token 用量已超过上限，无法再整理成完整回答。以下是已完成部分的原始结果：";

    /** Whether a failure was the budget refusing a call (directly or as a cause). */
    public static boolean isBudgetFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) if (t instanceof RunBudgetExceededException) return true;
        return false;
    }

    /** Why this run should start no new phase, or null. */
    public static String reachedFor(String runId) {
        State state = find(runId);
        return state == null ? null : state.budgetReached();
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
        /** Input estimate of calls whose usage the provider did not report. */
        private long unknownTokens;
        private int unknownUsage;
        private String stopReason;
        private String budgetReached;
        private boolean warned;
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

        /**
         * Admits a call on usage so far; the output limit is not reserved. The input estimate is held
         * until the call settles, and kept as usage when the provider reports none.
         */
        public synchronized Reservation reserveModel(long inputEstimate, long outputLimit) {
            checkActive();
            long input = Math.max(0, inputEstimate);
            if (models >= limits.modelCalls()) exhaust("任务已达到模型请求次数上限");
            long used = used(), hard = hardTokens();
            if (used >= hard) {
                log.warn("Run Token hard ceiling reached: runId={} budget={} hard={} used={} modelCalls={} unknownUsageCalls={}",
                        runId, limits.tokens(), hard, used, models, unknownUsage);
                exhaust(overrunMessage() + "（预算 " + limits.tokens() + "，已用 " + used + "；累计模型请求 " + models + " 次）");
            }
            models++;
            reservedTokens += input;
            return new Reservation(this, input);
        }

        private long used() { return confirmedTokens + unknownTokens; }

        private long hardTokens() { return limits.tokens() + (long) Math.floor(limits.tokens() * overrunRatio); }

        private static String overrunMessage() {
            return "任务 Token 用量已超过预算的 " + Math.round((1 + overrunRatio) * 100) + "%，已停止后续调用";
        }

        /** Why this run should start no new phase (its budget, or the owner's daily quota), or null. */
        public synchronized String budgetReached() { return budgetReached; }

        /** Marks the run so that it finishes the phase in progress and starts no new one. */
        public void reach(String reason) {
            boolean first;
            synchronized (this) {
                first = budgetReached == null;
                if (first) budgetReached = reason;
            }
            Listener current = listener;
            if (first && current != null) {
                try { current.reached(this, reason); } catch (RuntimeException e) { log.warn("Budget listener failed runId={}", runId, e); }
            }
        }

        /** After a call settles: warn once at 80%, mark the run at 100%, stop it beyond the hard ceiling. */
        private void evaluate() {
            long used, budget = limits.tokens();
            boolean warn = false;
            synchronized (this) {
                used = used();
                if (used >= hardTokens()) {
                    stop(overrunMessage() + "（预算 " + budget + "，已用 " + used + "；累计模型请求 " + models + " 次）");
                    return;
                }
                if (used < budget && used >= budget * 8 / 10 && !warned) { warned = true; warn = true; }
            }
            if (used >= budget) {
                reach("本次任务的 Token 预算（" + budget + "）已用完");
                return;
            }
            Listener current = listener;
            if (warn && current != null) {
                try { current.warned(this, used, budget); } catch (RuntimeException e) { log.warn("Budget listener failed runId={}", runId, e); }
            }
        }

        public synchronized Map<String, Object> snapshot() {
            return Map.of("modelCalls", models, "toolAttempts", tools, "reservedTokens", reservedTokens,
                    "confirmedTokens", confirmedTokens, "unknownUsageCalls", unknownUsage,
                    "maxTokens", limits.tokens(), "hardTokens", hardTokens(), "stopReason", stopReason == null ? "" : stopReason,
                    "budgetReached", budgetReached == null ? "" : budgetReached,
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

    /** Unknown usage counts the input estimate instead of claiming a free call. */
    public static final class Reservation {
        private final State state;
        private final long amount;
        private boolean settled;
        Reservation(State state, long amount) { this.state = state; this.amount = amount; }
        public void settle(Long actualTokens) {
            synchronized (state) {
                if (settled) return;
                settled = true;
                if (actualTokens == null || actualTokens < 0) {
                    state.unknownUsage++;
                    state.unknownTokens += amount;
                } else {
                    state.confirmedTokens += actualTokens;
                    state.reservedTokens += actualTokens - amount;
                }
            }
            state.evaluate();
        }
    }
}
