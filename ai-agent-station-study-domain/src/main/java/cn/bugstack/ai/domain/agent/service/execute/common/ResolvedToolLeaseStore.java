package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.List;
import java.util.Optional;

/**
 * P2-A1 lease 存储边界（v3.md §70.1）。
 *
 * <p>首版 in-memory（{@link InMemoryResolvedToolLeaseStore}），但通过此薄接口隔离——未来换 Redis/跨实例
 * 只替换实现、不动 Step/Manager。本接口只管租约的 CRUD/隔离/清理；
 * <b>物化（lazy hasClient 检测 + 建回调 + typed signal）不在此层</b>，由调用层用 {@link #find} 结果 + registry 完成。
 */
public interface ResolvedToolLeaseStore {

    /** 首次解析成功后建租约；同 runId+toolIdentity <b>幂等</b>（不重复建，仅刷新 resolvedAt）。 */
    void createOrMerge(String runId, String sessionId, String originalNeed, String toolIdentity);

    /** 列出某 run 的全部租约（含已 invalidated/mcp_down，便于诊断/lineage）。 */
    List<ResolvedToolLease> listLeases(String runId);

    /** 按 identity 查租约（后续 step 重物化的依据）。 */
    Optional<ResolvedToolLease> find(String runId, String toolIdentity);

    /** 标记失效（MCP 下线 / definitionHash 变）；<b>不静默替换成相似工具</b>。 */
    void markInvalidated(String runId, String toolIdentity, ResolvedToolLease.Availability reason);

    /** run 终态清理（success/cancel/error/timeout 全覆盖）；防泄漏。 */
    void cleanupRun(String runId);
}
