package cn.bugstack.ai.domain.agent.service.execute.snapshot;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;

import java.util.List;
import java.util.Optional;

public interface RunSnapshotService {

    String STATUS_RUNNING = "RUNNING";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";

    void startRun(ExecuteCommandEntity request, String agentType, String agentName);

    Optional<RunSnapshot> find(String runId);

    List<RunSnapshot> listRecent(String sessionId, int limit);

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

    void markStatus(String runId, String status, String lastError);

    Optional<String> buildRedoContext(String sourceRunId, Integer redoFromStep, String sessionId);

    Optional<String> buildRedoTargetStepContext(String sourceRunId, Integer redoFromStep, String sessionId);

    List<RunStepSnapshot> inheritedSteps(String sourceRunId, Integer redoFromStep, String sessionId);
}
