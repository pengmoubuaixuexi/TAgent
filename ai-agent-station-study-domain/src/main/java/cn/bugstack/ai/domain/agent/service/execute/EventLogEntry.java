package cn.bugstack.ai.domain.agent.service.execute;

import lombok.Builder;
import lombok.Data;

/**
 * 事件日志数据载体（V035 引入）。
 * <p>
 * 用 Builder 模式取代之前 9 个位置参数，后续要再加 cost/cachedTokens 等字段不会破坏调用方。
 */
@Data
@Builder
public class EventLogEntry {

    private String sessionId;
    /** 用户ID：从 ExecuteCommandEntity 或 MDC 提取 */
    private String userId;
    /** 租户ID：缺省 "default" */
    private String tenantId;
    /** Agent ID：哪一个 agent 产生的调用（fix/auto/flow 或 router） */
    private String agentId;
    /** 额度/计费口径：USER_CHARGEABLE=用户可扣额度；SYSTEM_OVERHEAD=系统内部开销 */
    private String billingScope;
    /** step 名：step1_analyzer / step2_precision_executor / fixed_strategy / unified_router / rag_router 等 */
    private String stepName;
    /** Reflexion 重做序号；0 = 首次 */
    private int stepIndex;
    /** 发往 LLM 的完整 prompt */
    private String inputPrompt;
    /** LLM 返回文本 */
    private String outputText;
    /** 模型名 */
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long latencyMs;
}
