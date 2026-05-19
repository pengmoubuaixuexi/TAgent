package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行命令实体
 *
 * @author TAgent
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

}
