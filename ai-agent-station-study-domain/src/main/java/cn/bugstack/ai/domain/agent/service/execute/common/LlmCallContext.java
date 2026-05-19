package cn.bugstack.ai.domain.agent.service.execute.common;

import lombok.Builder;
import lombok.Data;

/**
 * Context that is known before or outside the provider response.
 */
@Data
@Builder
public class LlmCallContext {

    private String stepName;
    private String prompt;
    private String resultText;
    private String sessionId;
    private String userId;
    private String tenantId;
    private String agentId;
    private String clientId;
    private String model;
    /** USER_CHARGEABLE / SYSTEM_OVERHEAD；为空时由 recorder 按 step 自动推断 */
    private String billingScope;
}
