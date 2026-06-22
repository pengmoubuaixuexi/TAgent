package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2-A1 lease 内存实现：{@code Map<runId, Map<toolIdentity, lease>>}，与现 {@code sessionNeeds} 同进程语义
 * （§69.4：先 in-memory，不引入 Redis）。runId 隔离（两 run 不串）；同 runId+identity <b>幂等 merge</b>。
 */
@Component
public class InMemoryResolvedToolLeaseStore implements ResolvedToolLeaseStore {

    private final Map<String, Map<String, ResolvedToolLease>> byRun = new ConcurrentHashMap<>();

    @Override
    public void createOrMerge(String runId, String sessionId, String originalNeed, String toolIdentity) {
        if (runId == null || runId.isBlank() || toolIdentity == null || toolIdentity.isBlank()) return;
        byRun.computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .compute(toolIdentity, (id, existing) -> existing != null
                        // 幂等：同 need/identity 重复 request_tool 不重复建租约；若此前被标 invalidated，
                        // 后续确实又成功解析到同一 identity，则以成功解析为准恢复 AVAILABLE。
                        ? new ResolvedToolLease(existing.runId(), sessionId, originalNeed, existing.toolIdentity(),
                                System.currentTimeMillis(), ResolvedToolLease.Availability.AVAILABLE)
                        : new ResolvedToolLease(runId, sessionId, originalNeed, toolIdentity,
                                System.currentTimeMillis(), ResolvedToolLease.Availability.AVAILABLE));
    }

    @Override
    public List<ResolvedToolLease> listLeases(String runId) {
        Map<String, ResolvedToolLease> m = byRun.get(runId);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    @Override
    public Optional<ResolvedToolLease> find(String runId, String toolIdentity) {
        Map<String, ResolvedToolLease> m = byRun.get(runId);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(toolIdentity));
    }

    @Override
    public void markInvalidated(String runId, String toolIdentity, ResolvedToolLease.Availability reason) {
        if (reason == null || reason == ResolvedToolLease.Availability.AVAILABLE) return;
        Map<String, ResolvedToolLease> m = byRun.get(runId);
        if (m == null) return;
        m.computeIfPresent(toolIdentity, (id, lease) -> lease.withAvailability(reason));
    }

    @Override
    public void cleanupRun(String runId) {
        if (runId != null) byRun.remove(runId);
    }
}
