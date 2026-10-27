package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.trigger.workspace.ModelFailoverSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform model catalog for failover: tier, order, enabled state and health. Administrators only (/api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/model-catalog")
public class AdminModelCatalogController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate db;
    private final IModelCatalogRepository catalog;
    private final ModelFailoverSettings settings;

    public AdminModelCatalogController(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate db,
                                       IModelCatalogRepository catalog, ModelFailoverSettings settings) {
        this.db = db;
        this.catalog = catalog;
        this.settings = settings;
    }

    @GetMapping public Object list() {
        Map<String, String> hosts = new LinkedHashMap<>();
        db.query("SELECT api_id,base_url FROM ai_client_api WHERE secret_ref IS NULL", rs -> {
            hosts.put(rs.getString("api_id"), host(rs.getString("base_url")));
        });
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ModelCatalogEntry entry : catalog.listByScope(ModelCatalogEntry.PLATFORM)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("apiId", entry.apiId());
            row.put("modelName", entry.modelName());
            row.put("host", hosts.get(entry.apiId()));
            row.put("tier", entry.tier());
            row.put("sortOrder", entry.sortOrder());
            row.put("enabled", entry.enabled());
            row.put("published", entry.published());
            row.put("imageInput", entry.acceptsImages());
            row.put("health", settings.health(entry.apiId(), entry.modelName(), true));
            entries.add(row);
        }
        // Published platform models that are not in the catalog yet.
        var addable = db.queryForList("""
                SELECT DISTINCT m.api_id AS apiId,m.model_name AS modelName FROM ai_client_model m
                JOIN ai_client_api a ON a.api_id=m.api_id AND a.status=1 AND a.secret_ref IS NULL
                WHERE m.platform_enabled=1 AND m.status=1 AND NOT EXISTS
                  (SELECT 1 FROM ai_model_catalog c WHERE c.owner_scope='PLATFORM' AND c.api_id=m.api_id AND c.model_name=m.model_name)
                ORDER BY m.api_id,m.model_name
                """);
        for (var row : addable) row.put("host", hosts.get((String) row.get("apiId")));
        return ok(Map.of("entries", entries, "addable", addable));
    }

    @PostMapping public Object add(@RequestBody JsonNode body) {
        String apiId = text(body, "apiId", 64), model = text(body, "modelName", 64);
        Integer platform = db.queryForObject("SELECT COUNT(*) FROM ai_client_api WHERE api_id=? AND status=1 AND secret_ref IS NULL", Integer.class, apiId);
        if (platform == null || platform == 0) throw new IllegalArgumentException("只能登记平台连接（非个人密钥）下的模型");
        // Image support follows the model's existing configuration, if any.
        Set<String> modalities = new LinkedHashSet<>(Set.of("TEXT"));
        for (String caps : db.queryForList("SELECT capabilities_json FROM ai_client_model WHERE api_id=? AND model_name=? AND capabilities_json IS NOT NULL",
                String.class, apiId, model)) {
            try { JSON.readTree(caps).path("inputModalities").forEach(node -> modalities.add(node.asText().toUpperCase())); }
            catch (Exception ignored) { /* unreadable: text only */ }
        }
        settings.add(new ModelCatalogEntry(ModelCatalogEntry.PLATFORM, apiId, model, tier(body), order(body), modalities, true, true, false));
        return ok(Map.of());
    }

    @PutMapping public Object update(@RequestBody JsonNode body) {
        String apiId = text(body, "apiId", 64), model = text(body, "modelName", 64);
        requireEntry(apiId, model);
        boolean enabled = !body.path("enabled").isBoolean() || body.path("enabled").asBoolean();
        settings.save(ModelCatalogEntry.PLATFORM, apiId, model, tier(body), order(body), enabled);
        return ok(Map.of());
    }

    @DeleteMapping public Object delete(@RequestParam String apiId, @RequestParam String modelName) {
        if (!settings.delete(ModelCatalogEntry.PLATFORM, apiId, modelName)) throw new IllegalArgumentException("清单中没有该模型");
        return ok(Map.of());
    }

    /** One minimal request as the system (about ten tokens on the platform key). */
    @PostMapping("/check") public Object check(@RequestBody JsonNode body) {
        String apiId = text(body, "apiId", 64), model = text(body, "modelName", 64);
        requireEntry(apiId, model);
        return ok(settings.check(apiId, model, true));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, SecurityException.class})
    public ResponseEntity<?> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "400", "info", e.getMessage() == null ? "操作失败" : e.getMessage()));
    }

    private void requireEntry(String apiId, String model) {
        boolean known = catalog.listByScope(ModelCatalogEntry.PLATFORM).stream()
                .anyMatch(e -> e.apiId().equals(apiId) && e.modelName().equals(model));
        if (!known) throw new IllegalArgumentException("清单中没有该模型");
    }

    private static String tier(JsonNode body) {
        String tier = body.path("tier").asText("medium").trim();
        if (!ModelCatalogEntry.TIERS.contains(tier)) throw new IllegalArgumentException("档位只能是 small、medium 或 large");
        return tier;
    }

    private static int order(JsonNode body) {
        int order = body.path("sortOrder").isInt() ? body.path("sortOrder").asInt() : 100;
        if (order < 0 || order > 10000) throw new IllegalArgumentException("顺序需在 0 到 10000 之间");
        return order;
    }

    private static String text(JsonNode body, String field, int max) {
        String value = body.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("缺少或过长的参数：" + field);
        return value;
    }

    private static String host(String baseUrl) {
        try { return java.net.URI.create(baseUrl).getHost(); } catch (Exception e) { return null; }
    }

    private static Object ok(Object data) { return Map.of("code", "0000", "info", "成功", "data", data); }
}
