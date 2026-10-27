package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V071 {@code ai_model_catalog}; ownership always comes from the connection row, never from the caller. */
@Repository
public class ModelCatalogRepository implements IModelCatalogRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = """
            c.owner_scope,c.api_id,c.model_name,c.tier,c.sort_order,c.capabilities_json,c.enabled,
            EXISTS(SELECT 1 FROM ai_client_model m WHERE m.api_id=c.api_id AND m.model_name=c.model_name
                   AND m.platform_enabled=1 AND m.status=1) AS published
            """;
    private final JdbcTemplate jdbc;

    public ModelCatalogRepository(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public List<ModelCatalogEntry> usableEntries() {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM ai_model_catalog c
                JOIN ai_client_api a ON a.api_id=c.api_id AND a.status=1
                LEFT JOIN ai_model_secret s ON s.secret_ref=a.secret_ref
                WHERE c.enabled=1 AND (
                  (c.owner_scope='PLATFORM' AND a.secret_ref IS NULL) OR
                  (a.secret_ref IS NOT NULL AND a.owner_user_id=c.owner_scope AND s.owner_user_id=c.owner_scope AND s.status=1))
                """, ROW);
    }

    @Override public Map<String, String> connectionScopes() {
        Map<String, String> scopes = new LinkedHashMap<>();
        jdbc.query("SELECT api_id,IF(secret_ref IS NULL,'PLATFORM',owner_user_id) AS scope FROM ai_client_api WHERE status=1",
                rs -> { scopes.put(rs.getString("api_id"), rs.getString("scope")); });
        return scopes;
    }

    @Override public List<ModelCatalogEntry> listByScope(String ownerScope) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_model_catalog c WHERE c.owner_scope=? ORDER BY FIELD(c.tier,'small','medium','large'),c.sort_order,c.model_name",
                ROW, ownerScope);
    }

    @Override public void upsert(ModelCatalogEntry entry) {
        jdbc.update("""
                INSERT INTO ai_model_catalog(owner_scope,api_id,model_name,tier,sort_order,capabilities_json,enabled)
                VALUES(?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE tier=VALUES(tier),sort_order=VALUES(sort_order),
                  capabilities_json=VALUES(capabilities_json),enabled=VALUES(enabled)
                """, entry.ownerScope(), entry.apiId(), entry.modelName(), entry.tier(), entry.sortOrder(),
                capabilities(entry), entry.enabled() ? 1 : 0);
    }

    @Override public boolean update(String ownerScope, String apiId, String modelName, String tier, int sortOrder, boolean enabled) {
        return jdbc.update("UPDATE ai_model_catalog SET tier=?,sort_order=?,enabled=? WHERE owner_scope=? AND api_id=? AND model_name=?",
                ModelCatalogEntry.normalizeTier(tier), sortOrder, enabled ? 1 : 0, ownerScope, apiId, modelName) == 1;
    }

    @Override public boolean delete(String ownerScope, String apiId, String modelName) {
        return jdbc.update("DELETE FROM ai_model_catalog WHERE owner_scope=? AND api_id=? AND model_name=?",
                ownerScope, apiId, modelName) == 1;
    }

    @Override public boolean referenced(String apiId, String modelName) {
        Integer count = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM ai_client_model WHERE api_id=? AND model_name=? AND status=1)
                     + (SELECT COUNT(*) FROM ai_model_catalog WHERE api_id=? AND model_name=?)
                """, Integer.class, apiId, modelName, apiId, modelName);
        return count != null && count > 0;
    }

    private static String capabilities(ModelCatalogEntry entry) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("inputModalities", entry.inputModalities().stream().sorted().toList());
        json.put("imageSources", entry.acceptsImages() ? List.of("URL", "BASE64") : List.of());
        if (!entry.toolCalling()) json.put("toolCalling", false);
        try {
            return JSON.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException("模型能力无法保存", e);
        }
    }

    private static final RowMapper<ModelCatalogEntry> ROW = (rs, row) -> {
        Set<String> modalities = new LinkedHashSet<>();
        boolean tools = true;
        String raw = rs.getString("capabilities_json");
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode caps = JSON.readTree(raw);
                caps.path("inputModalities").forEach(node -> modalities.add(node.asText().toUpperCase()));
                tools = !caps.path("toolCalling").isBoolean() || caps.path("toolCalling").asBoolean();
            } catch (Exception malformed) {
                // Treat unreadable capabilities as text-only with tools, the historical default.
            }
        }
        return new ModelCatalogEntry(rs.getString("owner_scope"), rs.getString("api_id"), rs.getString("model_name"),
                rs.getString("tier"), rs.getInt("sort_order"), modalities, tools, rs.getInt("enabled") == 1,
                rs.getInt("published") == 1);
    };
}
