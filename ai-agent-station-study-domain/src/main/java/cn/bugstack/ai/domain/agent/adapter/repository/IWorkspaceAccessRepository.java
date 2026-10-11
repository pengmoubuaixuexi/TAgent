package cn.bugstack.ai.domain.agent.adapter.repository;

import java.util.Set;

/** Persisted ownership is authoritative, including when a runtime object is cached. */
public interface IWorkspaceAccessRepository {
    Set<String> ownedAgentIds(String userId);
    Set<String> ownedMcpIds(String userId);
    boolean ownsAgent(String userId, String agentId);
    boolean ownsMcp(String userId, String mcpId);
    default Integer activeAgentVersion(String userId, String agentId) { return null; }
    default Set<String> agentMcpIds(String userId, String agentId) { return Set.of(); }
    default Set<String> platformModelIds() { return Set.of(); }
    default java.util.Map<String,String> publicMcpOrigins(String userId) { return java.util.Map.of(); }
    /** Actual persisted owner for an enabled connection; never supplied by a caller. */
    default String mcpCapacityOwner(String mcpId) { return null; }
    default boolean trustedSystemMcp(String mcpId) { return false; }
}
