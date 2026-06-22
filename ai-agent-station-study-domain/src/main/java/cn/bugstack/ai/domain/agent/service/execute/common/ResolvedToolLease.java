package cn.bugstack.ai.domain.agent.service.execute.common;

/**
 * P2-A1 动态工具租约（v3.md §66/§70.4 定稿）。
 *
 * <p>把 {@code request_tool} 首次语义解析出的真实工具绑定为稳定 {@link #toolIdentity}，后续 step 按 identity
 * <b>重物化同一工具</b>，消除"每步对 NL needs 重 embedding/top-k 导致工具漂移"的问题。
 *
 * <p><b>生命周期</b>：run 级临时态——以 {@code runId} 隔离（一次 dispatch→execute() 即一个 run，
 * steer/answer_now/retry 同 run），execute 终态 {@code cleanupRun} 清理；in-memory，不跨重启持久。
 */
public record ResolvedToolLease(
        String runId,
        String sessionId,
        String originalNeed,
        /** mcpId + ":" + toolName + ":" + definitionHash —— toolName 单独不足以区分 server。 */
        String toolIdentity,
        long resolvedAt,
        Availability availability
) {
    public enum Availability {
        /** 租约有效，可物化。 */
        AVAILABLE,
        /** 对应 MCP client 下线，不可物化（典型 invalidate 原因）。 */
        MCP_DOWN,
        /** definitionHash 变化或显式失效。 */
        INVALIDATED
    }

    public ResolvedToolLease withAvailability(Availability a) {
        return new ResolvedToolLease(runId, sessionId, originalNeed, toolIdentity, resolvedAt, a);
    }

    public ResolvedToolLease withResolvedAt(long ts) {
        return new ResolvedToolLease(runId, sessionId, originalNeed, toolIdentity, ts, availability);
    }

    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }
}
