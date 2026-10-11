package cn.bugstack.ai.domain.agent.service.execute.snapshot;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RunSnapshotService {

    String STATUS_RUNNING = "RUNNING";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED = "FAILED";
    String STATUS_BLOCKED = "BLOCKED";
    String STATUS_CANCELLED = "CANCELLED";

    void startRun(ExecuteCommandEntity request, String agentType, String agentName);

    Optional<RunSnapshot> find(String runId);

    List<RunSnapshot> listRecent(String sessionId, int limit);

    /**
     * List recent Redis runs owned by a user. This keeps a brand-new running
     * session discoverable before its final turn is written to ChatMemory.
     */
    default List<RunSnapshot> listRecentByUser(String userId, int limit) {
        return List.of();
    }

    void recordStep(String runId,
                    String stepId,
                    String title,
                    String type,
                    Integer stepNo,
                    String content,
                    String status);

    /**
     * 记录步骤执行前的原始步骤内容（如 Flow Step4 的单个 DAG 子步骤计划）。
     * <p>默认 no-op，Redis 实现会写入同一个 run snapshot；不涉及 DB 表结构。</p>
     */
    default void recordStepContent(String runId,
                                   String stepId,
                                   String title,
                                   String type,
                                   Integer stepNo,
                                   String stepContent) {
    }

    /**
     * 在 Flow Step4 启动任何 DAG 子步骤前，一次性保存完整、已确认计划。
     * 该快照是后代计算与分支继承的权威来源，不能依赖并行子步骤的逐条 recordStepContent 反推。
     */
    default void recordFlowPlan(String runId,
                                Map<String, String> stepsMap,
                                Map<Integer, Set<Integer>> stepDependencies) {
    }

    /**
     * 记录本 run 动态 request_tool 装载的额外工具需求（lease.originalNeed），供步骤级 redo 重新申请旧能力。
     * <p>默认 no-op，Redis 实现写入同一 run snapshot。常驻工具不在此列——redo 时 ensureArmed 自带。</p>
     */
    default void recordExtraToolNeeds(String runId, List<String> needs) {
    }

    /** Persist the user-visible UI projection in the same Redis snapshot. */
    default void recordTimeline(String runId, List<RunEventRecord> events, String lastEventId) {
    }

    /** Persist a full tool result separately from the compact UI timeline. */
    default void recordToolEvidence(String runId, ToolEvidenceRecord evidence) {
    }

    /** An acknowledged, owner-scoped write used before exposing a model-readable result reference. */
    default boolean saveToolEvidence(String runId, String userId, ToolEvidenceRecord evidence) {
        if (userId == null || evidence == null || find(runId).filter(s -> userId.equals(s.getUserId())).isEmpty()) return false;
        recordToolEvidence(runId, evidence);
        return findToolEvidence(runId, userId, evidence.getEvidenceId())
                .filter(saved -> java.util.Objects.equals(saved.getOutput(), evidence.getOutput())).isPresent();
    }

    /** Reads are confined to the caller's current run; implementations may fetch one hash field. */
    default Optional<ToolEvidenceRecord> findToolEvidence(String runId, String userId, String evidenceId) {
        if (userId == null || evidenceId == null) return Optional.empty();
        return find(runId).filter(s -> userId.equals(s.getUserId()))
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt() > System.currentTimeMillis())
                .flatMap(s -> s.getToolEvidences() == null ? Optional.empty() : s.getToolEvidences().stream()
                        .filter(e -> evidenceId.equals(e.getEvidenceId())).findFirst());
    }

    /** Load a previously generated Evidence Map when its input signature still matches. */
    default Optional<Map<String, Object>> findEvidenceMap(String runId, String signature) {
        return Optional.empty();
    }

    /**
     * Load the latest generated Evidence Map without requiring the short-lived run snapshot.
     * This is used to keep an already-paid-for map readable after its source snapshot expires.
     */
    default Optional<Map<String, Object>> findEvidenceMap(String runId) {
        return Optional.empty();
    }

    /** Cache an Evidence Map with its own, longer sliding TTL. */
    default void saveEvidenceMap(String runId, String signature, Map<String, Object> evidenceMap) {
    }

    void markStatus(String runId, String status, String lastError);

    Optional<String> buildRedoContext(String sourceRunId, Integer redoFromStep, String sessionId);

    Optional<String> buildRedoTargetStepContext(String sourceRunId, Integer redoFromStep, String sessionId);

    List<RunStepSnapshot> inheritedSteps(String sourceRunId, Integer redoFromStep, String sessionId);
}
