package cn.bugstack.ai.domain.agent.service.execute.snapshot;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RunSnapshot {

    private String runId;
    private String sessionId;
    /** Owner used only for listing this user's still-live Redis runs. */
    private String userId;
    private String agentId;
    private String agentName;
    private String agentType;
    private String originalMessage;
    /** Stable metadata only; never contains BASE64 bytes or expiring access URLs. */
    private List<ChatImageRef> images;
    private String sourceRunId;
    private Integer redoFromStep;
    private String status;
    private String lastError;
    private Long createdAt;
    private Long updatedAt;
    private Long expiresAt;

    @Builder.Default
    private List<RunStepSnapshot> steps = new ArrayList<>();

    /**
     * Flow 在进入 Step4 前一次性保存的完整、已确认执行计划。
     * Redo 必须从这里读取完整 DAG；逐步骤的 stepContent 只作为执行现场兼容旧快照。
     */
    @Builder.Default
    private Map<String, String> flowPlanSteps = new LinkedHashMap<>();

    /** Flow 完整计划对应的依赖图（stepNo -> dependency stepNos）。 */
    @Builder.Default
    private Map<Integer, Set<Integer>> flowPlanDependencies = new LinkedHashMap<>();

    /**
     * 本 run 通过 request_tool 动态装载的额外工具能力需求（lease.originalNeed 去重）。
     * Redo 据此重新申请旧能力；这不是精确 tool identity 快照，常驻工具仍由 ensureArmed 装配。
     */
    private List<String> extraToolNeeds;

    /** Compact projection of all events that were visible in the run UI. */
    @Builder.Default
    private List<RunEventRecord> timelineEvents = new ArrayList<>();

    /** Redis Stream cursor already covered by {@link #timelineEvents}. */
    private String lastEventId;
}
