package cn.bugstack.ai.domain.agent.service.execute;

/**
 * P2.8 17.2 Replay-able Event Log：步骤事件写入接口。
 * domain 层只依赖接口，infrastructure 层 MyBatis 实现。
 * <p>
 * V035 (2026-05-14)：扩展为 EventLogEntry record 携带 userId / tenantId / agentId，
 * 支持按用户/agent 维度做 token 与成本聚合查询。旧 9 参签名保留为 default 方法，
 * 老调用方零改动即可继续工作；新调用方走 {@link #log(EventLogEntry)}。
 */
public interface IEventLogService {

    /**
     * 全字段写入入口（推荐）。
     */
    void log(EventLogEntry entry);

    /**
     * 旧版 9 参签名（v0.2.0~v1.2.0 期间使用）。保留兼容，内部转 EventLogEntry。
     */
    default void log(String sessionId, String stepName, int stepIndex,
                     String inputPrompt, String outputText,
                     String model, Integer promptTokens, Integer completionTokens, Long latencyMs) {
        log(EventLogEntry.builder()
                .sessionId(sessionId)
                .stepName(stepName)
                .stepIndex(stepIndex)
                .inputPrompt(inputPrompt)
                .outputText(outputText)
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .latencyMs(latencyMs)
                .build());
    }
}
