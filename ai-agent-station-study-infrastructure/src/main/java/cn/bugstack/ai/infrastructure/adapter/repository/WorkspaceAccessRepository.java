package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.LinkedHashSet;
import java.util.Set;

/** Server-owned resource IDs; no user-supplied owner or role is trusted. */
@Repository
public class WorkspaceAccessRepository implements IWorkspaceAccessRepository {
    private final JdbcTemplate jdbc;
    public WorkspaceAccessRepository(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Set<String> platformModelIds() {
        return new LinkedHashSet<>(jdbc.queryForList("""
            SELECT m.model_id FROM ai_client_model m JOIN ai_client_api a ON a.api_id=m.api_id
            WHERE m.platform_enabled=1 AND m.status=1 AND a.status=1
            """, String.class));
    }
    @Override public Set<String> ownedAgentIds(String userId) {
        if (userId == null || userId.isBlank()) return Set.of();
        return new LinkedHashSet<>(jdbc.queryForList("""
            SELECT a.agent_id FROM ai_agent a WHERE a.owner_user_id=? AND a.status=1 AND a.archived=0
            AND (a.source_agent_id IS NULL OR EXISTS
              (SELECT 1 FROM ai_agent s WHERE s.agent_id=a.source_agent_id AND s.is_public=1 AND s.status=1 AND s.archived=0))
            AND NOT EXISTS (
              SELECT 1 FROM ai_agent_flow_config f JOIN ai_client_config c
                ON c.source_type='client' AND c.source_id=f.client_id AND c.target_type='model' AND c.status=1
              LEFT JOIN ai_client_model m ON m.model_id=c.target_id
              LEFT JOIN ai_client_api p ON p.api_id=m.api_id
              LEFT JOIN ai_client_model origin ON origin.model_id=m.source_model_id
              WHERE f.agent_id=a.agent_id AND f.status=1 AND
                (m.model_id IS NULL OR m.status<>1 OR p.api_id IS NULL OR p.status<>1 OR
                 (m.source_model_id IS NOT NULL AND (origin.model_id IS NULL OR origin.status<>1 OR origin.platform_enabled<>1))))
            """, String.class, userId));
    }
    @Override public Set<String> ownedMcpIds(String userId) {
        if (userId == null || userId.isBlank()) return Set.of();
        return new LinkedHashSet<>(jdbc.queryForList("""
            SELECT m.mcp_id FROM ai_client_tool_mcp m WHERE m.owner_user_id=? AND m.status=1
            AND (m.source_mcp_id IS NULL OR EXISTS
              (SELECT 1 FROM ai_client_tool_mcp s WHERE s.mcp_id=m.source_mcp_id AND s.status=1 AND s.is_public=1))
            """, String.class, userId));
    }
    @Override public java.util.Map<String,String> publicMcpOrigins(String userId) {
        if(userId==null||userId.isBlank())return java.util.Map.of();
        var result=new java.util.LinkedHashMap<String,String>();
        jdbc.query("""
            SELECT m.mcp_id,COALESCE(m.source_mcp_id,m.mcp_id) AS origin
            FROM ai_client_tool_mcp m JOIN ai_client_tool_mcp s ON s.mcp_id=COALESCE(m.source_mcp_id,m.mcp_id)
            WHERE m.owner_user_id=? AND m.status=1 AND s.status=1 AND s.is_public=1
            """,rs->{result.put(rs.getString("mcp_id"),rs.getString("origin"));},userId);
        return result;
    }
    @Override public boolean ownsAgent(String userId, String agentId) {
        return agentId != null && ownedAgentIds(userId).contains(agentId);
    }
    @Override public Set<String> agentMcpIds(String userId, String agentId) {
        if (!ownsAgent(userId, agentId)) return Set.of();
        Set<String> ids = new LinkedHashSet<>(jdbc.queryForList("""
            SELECT DISTINCT c.target_id FROM ai_agent_flow_config f JOIN ai_client_config c
              ON c.source_type='client' AND c.source_id=f.client_id AND c.status=1
            WHERE f.agent_id=? AND c.target_type='tool_mcp'
            UNION
            SELECT DISTINCT m.target_id FROM ai_agent_flow_config f JOIN ai_client_config c
              ON c.source_type='client' AND c.source_id=f.client_id AND c.target_type='model' AND c.status=1
            JOIN ai_client_config m ON m.source_type='model' AND m.source_id=c.target_id AND m.target_type='tool_mcp' AND m.status=1
            WHERE f.agent_id=?
            """, String.class, agentId, agentId));
        ids.retainAll(ownedMcpIds(userId));
        if(jdbc.queryForObject("""
            SELECT COUNT(*) FROM ai_agent a WHERE a.agent_id=? AND a.owner_user_id=? AND
            (a.source_agent_id IS NOT NULL OR a.is_public=1 OR
             JSON_CONTAINS(JSON_EXTRACT(a.workspace_config,'$.nodes[*].usePublicTools'),'true')=1)
            """,Integer.class,agentId,userId)>0) ids.addAll(publicMcpOrigins(userId).keySet());
        return ids;
    }
    @Override public boolean ownsMcp(String userId, String mcpId) {
        return mcpId != null && ownedMcpIds(userId).contains(mcpId);
    }
}
