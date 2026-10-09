package cn.bugstack.ai.domain.agent.service.workspace;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-instance lifecycle guard: a graph cannot be edited while a run is using it. */
@Component
public class WorkspaceExecutionGuard {
    private final Map<String, Integer> activeRuns = new HashMap<>();
    private final Set<String> editing = new LinkedHashSet<>();

    public synchronized Lease startRun(String agentId) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("Agent ID is required");
        if (editing.contains(agentId)) throw new BusyException("Agent 配置正在更新，请稍后重试");
        activeRuns.merge(agentId, 1, Integer::sum);
        return new Lease(() -> releaseRun(agentId));
    }

    /** Keep the returned lease until transaction afterCompletion, including rollback. */
    public synchronized Lease beginUpdate(Collection<String> agentIds) {
        Set<String> ids = new LinkedHashSet<>(agentIds);
        ids.removeIf(id -> id == null || id.isBlank());
        for (String id : ids) {
            if (editing.contains(id) || activeRuns.getOrDefault(id, 0) > 0) {
                throw new BusyException("Agent 正在运行或更新，请等待完成后再修改配置");
            }
        }
        editing.addAll(ids);
        return new Lease(() -> releaseUpdate(ids));
    }

    private synchronized void releaseRun(String id) {
        activeRuns.computeIfPresent(id, (key, count) -> count <= 1 ? null : count - 1);
    }

    private synchronized void releaseUpdate(Set<String> ids) {
        editing.removeAll(ids);
    }

    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable release;
        private Lease(Runnable release) { this.release = release; }
        @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
    }

    public static final class BusyException extends IllegalStateException {
        public BusyException(String message) { super(message); }
    }
}
