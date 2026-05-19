package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AutoAgent 请求 DTO
 *
 * @author TAgent
 * 2025/1/15 10:00
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AutoAgentRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * AI智能体ID
     */
    private String aiAgentId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 会话ID
     */
    private String sessionId;

    /** P1.2.3 多租户隔离：用户 ID（可选，缺失时从 X-User-Id header 读） */
    private String userId;

    /** P1.2.3 多租户隔离：租户 ID（可选，缺失时从 X-Tenant-Id header 读） */
    private String tenantId;

    /**
     * 最大执行步数
     */
    private Integer maxStep;

}