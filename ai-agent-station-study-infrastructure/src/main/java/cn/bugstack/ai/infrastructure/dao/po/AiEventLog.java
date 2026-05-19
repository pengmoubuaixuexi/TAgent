package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 步骤事件日志 PO（P2.8 17.2 Replay-able Event Log）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiEventLog {

    private Long id;
    private String sessionId;
    /** V035 新增：用户ID，用于 per-user 聚合 */
    private String userId;
    /** V035 新增：租户ID */
    private String tenantId;
    /** V035 新增：Agent ID，用于按 agent 维度的成本/token 聚合 */
    private String agentId;
    /** V036 新增：额度/计费口径，区分用户可扣额度与系统内部开销 */
    private String billingScope;
    private String stepName;
    private int stepIndex;
    private String inputPrompt;
    private String outputText;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long latencyMs;
    private LocalDateTime createdAt;
}
