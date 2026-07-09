package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行命令实体
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:46
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteCommandEntity {

    private String aiAgentId;

    private String message;

    private String sessionId;

    /**
     * P2-A1：一次 dispatch -> execute() 的运行边界。
     * <p>用于动态工具租约隔离：同一次执行内 request_tool 首次解析出的工具 identity
     * 后续 step 复用；执行结束 cleanupRun(runId)。steer / answer_now / retry 仍属于同一 run。
     */
    private String runId;

    /**
     * Source run id for step redo.
     */
    private String sourceRunId;

    /**
     * 1-based snapshot step ordinal to redo from.
     */
    private Integer redoFromStep;

    /**
     * Extra context for the model when replaying a historical step. This is not
     * used as the user-visible message.
     */
    private String redoContextPrompt;

    /**
     * Extra context visible only to the target step selected by /runId-stepN.
     * Downstream steps should not consume the old target step as current facts.
     */
    private String redoTargetStepContextPrompt;

    /** P1.2.3 多租户隔离：用户 ID（X-User-Id header），为空时回退 sessionId */
    private String userId;

    /** P1.2.3 多租户隔离：租户 ID（X-Tenant-Id header），为空时回退 "default" */
    private String tenantId;

    private Integer maxStep;

    /**
     * Flow Agent 是否在 Step3 后进入计划确认；null 时由服务端配置决定。
     */
    private Boolean planReviewEnabled;

    // 2026-06-19：原 dynamicMissingToolDesc(路由产出的"可能缺失工具能力"描述/need)字段已退休，
    // 改为按 sessionId 存进 McpToolCatalogService.sessionNeeds（路由 setNeeds / 中途 request_tool appendNeed
    // / step needsFor / 执行结束 clearNeeds）。统一来源，便于 manager 中途追加；代价是需显式清理（见该类）。

    private Double routeConfidence;

}
