package cn.bugstack.ai.domain.agent.service.execute.event;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Live preparation feedback, kept separate from model activity and durable execution history. */
@Component
public class RunPreparationProgress {
    private final RunEventPublisher publisher;
    private final ConcurrentHashMap<String, Preparation> active = new ConcurrentHashMap<>();

    public RunPreparationProgress(RunEventPublisher publisher) { this.publisher = publisher; }

    public void start(String runId, String sessionId) {
        Preparation preparation = new Preparation(runId, sessionId);
        if (active.putIfAbsent(runId, preparation) == null) preparation.emit();
    }

    public void phase(String runId, String phase) {
        Preparation preparation = active.get(runId);
        if (preparation != null) preparation.change(phase, null);
    }

    /** Called only after MCP policy validation, on the worker carrying the authenticated run identity. */
    public Scope connecting(String name) {
        String runId = MDC.get("runId");
        Preparation preparation = runId == null ? null : active.get(runId);
        if (preparation == null) return () -> {};
        preparation.change("mcp_connecting", safeName(name));
        return () -> preparation.change("agent_preparing", null);
    }

    public void ready(String runId) {
        Preparation preparation = active.remove(runId);
        if (preparation != null) preparation.finish(true);
    }

    public void close(String runId) {
        Preparation preparation = active.remove(runId);
        if (preparation != null) preparation.finish(false);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void heartbeat() { active.values().forEach(Preparation::emit); }

    @jakarta.annotation.PreDestroy
    public void shutdown() { active.keySet().forEach(this::close); }

    public interface Scope extends AutoCloseable { @Override void close(); }

    private static String safeName(String name) {
        if (name == null) return null;
        String safe = name.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.substring(0, Math.min(120, safe.length()));
    }

    private final class Preparation {
        private final String runId;
        private final String sessionId;
        private String phase = "agent_preparing";
        private String resourceName;
        private long startedAt = System.nanoTime();
        private boolean closed;

        private Preparation(String runId, String sessionId) { this.runId = runId; this.sessionId = sessionId; }

        private synchronized void change(String next, String resource) {
            if (closed) return;
            phase = next;
            resourceName = resource;
            startedAt = System.nanoTime();
            emit();
        }

        private synchronized void finish(boolean ready) {
            if (closed) return;
            if (ready) change("run_ready", null);
            closed = true;
        }

        private synchronized void emit() {
            if (closed) return;
            var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.find(runId);
            if (budget != null) {
                try { budget.checkActive(); }
                catch (RuntimeException stopped) { closed = true; return; }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("sessionId", sessionId);
            payload.put("phase", phase);
            payload.put("waitSeconds", Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000_000L));
            payload.put("timestamp", System.currentTimeMillis());
            if (resourceName != null && !resourceName.isBlank()) payload.put("resourceName", resourceName);
            // Observation must never fail or delay the business lifecycle because storage is unavailable.
            try { publisher.publish(runId, sessionId, "run_progress", payload); }
            catch (RuntimeException ignored) { }
        }
    }
}
