package cn.bugstack.ai.domain.agent.service.execute.common;

/**
 * P2-A1 typed signal（v3.md §70.3）：lease 指向的动态工具不可物化（MCP 下线 / definitionHash 变 / 已失效）。
 *
 * <p>materialize lazy 检测到 {@code !mcpClientRegistry.hasClient(mcpId)} 时抛出，交 step 层决定
 * fail-fast / ask_user /（P2-A2 catalog 到位后）重规划。<b>禁止</b>据此 silent replacement、
 * 也禁止语义重匹配成"看起来相似"的工具——这是 P2-A1 的硬约束。
 */
public class DynamicToolUnavailableException extends RuntimeException {

    private final String toolIdentity;
    private final ResolvedToolLease.Availability reason;

    public DynamicToolUnavailableException(String toolIdentity, ResolvedToolLease.Availability reason) {
        super("dynamic tool unavailable: identity=" + toolIdentity + " reason=" + reason);
        this.toolIdentity = toolIdentity;
        this.reason = reason;
    }

    public String getToolIdentity() {
        return toolIdentity;
    }

    public ResolvedToolLease.Availability getReason() {
        return reason;
    }
}
