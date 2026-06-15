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

    /** P1.2.3 多租户隔离：用户 ID（X-User-Id header），为空时回退 sessionId */
    private String userId;

    /** P1.2.3 多租户隔离：租户 ID（X-Tenant-Id header），为空时回退 "default" */
    private String tenantId;

    private Integer maxStep;

    /**
     * 路由产出的"可能缺失的工具能力"的一句中文描述，用于本次请求做字符模糊匹配补挂工具。
     * 不是工具名，也不保证一定能匹配到工具；为空表示不需要补充工具。
     */
    private String dynamicMissingToolDesc;

    private Double routeConfidence;

}
