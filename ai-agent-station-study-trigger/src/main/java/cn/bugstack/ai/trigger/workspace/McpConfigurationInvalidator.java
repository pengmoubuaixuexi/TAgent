package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Set;

/** Retire every isolated runtime connection that consumes an updated platform configuration. */
@Service
public class McpConfigurationInvalidator {
    private final JdbcTemplate db;
    private final IArmoryService armory;
    private final McpToolCatalogService catalog;

    public McpConfigurationInvalidator(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db,
                                      IArmoryService armory, McpToolCatalogService catalog) {
        this.db = db;
        this.armory = armory;
        this.catalog = catalog;
    }

    public void invalidateAfterCommit(String... sourceIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : sourceIds) if (id != null && !id.isBlank()) ids.add(id);
        if (ids.isEmpty()) return;
        Runnable invalidate = () -> invalidate(ids);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate.run(); }
            });
        } else invalidate.run();
    }

    private void invalidate(Set<String> sources) {
        Set<String> connections = new LinkedHashSet<>(sources);
        // Query even after source deletion: surviving clones still hold its now-revoked credentials.
        for (String source : sources) connections.addAll(db.queryForList(
                "SELECT mcp_id FROM ai_client_tool_mcp WHERE source_mcp_id=?", String.class, source));
        Set<String> agents = new LinkedHashSet<>();
        for (String id : connections) agents.addAll(db.queryForList("""
                SELECT DISTINCT f.agent_id FROM ai_agent_flow_config f
                JOIN ai_client_config c ON c.source_type='client' AND c.source_id=f.client_id
                WHERE (c.target_type='tool_mcp' AND c.target_id=?) OR (c.target_type='model' AND EXISTS
                  (SELECT 1 FROM ai_client_config m WHERE m.source_type='model' AND m.source_id=c.target_id
                   AND m.target_type='tool_mcp' AND m.target_id=?))
                """, String.class, id, id));
        armory.invalidateAgents(agents, () -> {
            RuntimeException failure = null;
            for (String id : connections) try {
                // Includes the connection pool and all dynamic callbacks; leased calls can finish,
                // but later calls rebuild under the same per-user ID from the current source.
                catalog.invalidateMcp(id);
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        });
    }
}
