package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.ai.tool.ToolCallback;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P2-A2 ExecutorToolCatalog 骨架（v3.md §66/§70.4 Q4）：从 {@link ToolCallback} 的 ToolDefinition 派生工具
 * schema 快照，补 {@code AgentToolRegistry.ToolInfo}（只有 name/description、<b>无 schema</b>）的缺口。
 *
 * <p><b>用途</b>：V1(Flow Step1) / V2(Step2 重 resolve) / V3(Step4 执行前) 三阶段在对应 step 边界各取一份快照；
 * 断言「计划工具 ⊆ V2」「执行工具 ⊆ V3」，缺失则<b>阻止/修计划，不静默换工具</b>。
 *
 * <p><b>本骨架范围（CC）</b>：catalog 数据结构 + 从 ToolDefinition 派生（exactName/description/inputSchema/sha256 hash/source）
 * + name 级子集断言。<b>待 Codex 接力</b>：①normalizedInputSchema 深化（解析 required/类型/enum/约束，而非 raw json）；
 * ②V1/V2/V3 快照点在 Flow Step1/Step2/Step4 边界接入；③与 P2-A1 lease toolIdentity 的 schemaHash 对齐复用；
 * ④schema 级（非仅 name）子集断言。先确认边界（§76.3）再接入。
 */
public final class ExecutorToolCatalog {

    public enum Source {RESIDENT, DYNAMIC}

    /** 单个工具的 schema 快照条目。inputSchemaJson 首版为 raw schema 串；normalize 深化由 Codex 接力。 */
    public record Entry(String exactName, String hintedDescription, String inputSchemaJson,
                        String normalizedInputSchema, String schemaHash, Source source) {
    }

    private final int snapshotVersion;
    private final Map<String, Entry> byName;

    private ExecutorToolCatalog(int snapshotVersion, Map<String, Entry> byName) {
        this.snapshotVersion = snapshotVersion;
        this.byName = byName;
    }

    /**
     * 从工具回调派生快照。schema 取自 ToolDefinition.inputSchema()（反射，复用 McpToolCatalogService.readInputSchema 口径），
     * hash=sha256(schema)，与 P2-A1 toolIdentity 的 definitionHash 同口径，便于 V1/V2/V3 与 lease 对齐。
     */
    public static ExecutorToolCatalog from(List<ToolCallback> callbacks, Source source, int snapshotVersion) {
        Map<String, Entry> map = new LinkedHashMap<>();
        if (callbacks != null) {
            for (ToolCallback cb : callbacks) {
                if (cb == null || cb.getToolDefinition() == null) continue;
                String name = cb.getToolDefinition().name();
                if (name == null || name.isBlank() || map.containsKey(name)) continue;
                String desc = safe(cb.getToolDefinition().description());
                String schema = readInputSchema(cb);
                map.put(name, new Entry(name, desc, schema, normalizeInputSchema(schema), sha256(schema), source));
            }
        }
        return new ExecutorToolCatalog(snapshotVersion, map);
    }

    public static ExecutorToolCatalog from(List<ToolCallback> residentCallbacks,
                                           List<ToolCallback> dynamicCallbacks,
                                           int snapshotVersion) {
        Map<String, Entry> map = new LinkedHashMap<>();
        append(map, residentCallbacks, Source.RESIDENT);
        append(map, dynamicCallbacks, Source.DYNAMIC);
        return new ExecutorToolCatalog(snapshotVersion, map);
    }

    public int snapshotVersion() {
        return snapshotVersion;
    }

    public Optional<Entry> entry(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<String> toolNames() {
        return new LinkedHashSet<>(byName.keySet());
    }

    /** 子集断言：用到的工具名是否都在本快照里（缺失→调用层阻止/修计划，不静默换工具）。 */
    public boolean containsAll(Collection<String> usedToolNames) {
        return usedToolNames == null || byName.keySet().containsAll(usedToolNames);
    }

    /** 不在本快照里的工具名（用于报"计划/执行工具不在 V2/V3"，保持顺序便于诊断）。 */
    public List<String> missingFrom(Collection<String> usedToolNames) {
        List<String> missing = new ArrayList<>();
        if (usedToolNames != null) {
            for (String n : usedToolNames) {
                if (!byName.containsKey(n)) missing.add(n);
            }
        }
        return missing;
    }

    public List<String> missingOrChangedFrom(ExecutorToolCatalog previous) {
        if (previous == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Entry old : previous.byName.values()) {
            Entry now = byName.get(old.exactName());
            if (now == null) {
                result.add(old.exactName() + ":missing");
            } else if (!safe(now.schemaHash()).equals(safe(old.schemaHash()))) {
                result.add(old.exactName() + ":schema_changed");
            }
        }
        return result;
    }

    public static String normalizeInputSchema(String schema) {
        if (schema == null || schema.isBlank()) return "";
        try {
            JSONObject root = JSON.parseObject(schema);
            StringBuilder sb = new StringBuilder();
            appendKV(sb, "type", root.getString("type"));
            JSONArray required = root.getJSONArray("required");
            if (required != null && !required.isEmpty()) {
                List<String> req = new ArrayList<>();
                for (Object o : required) if (o != null) req.add(String.valueOf(o));
                Collections.sort(req);
                appendKV(sb, "required", String.join(",", req));
            }
            JSONObject props = root.getJSONObject("properties");
            if (props != null && !props.isEmpty()) {
                List<String> names = new ArrayList<>(props.keySet());
                Collections.sort(names);
                for (String name : names) {
                    JSONObject p = props.getJSONObject(name);
                    if (p == null) continue;
                    List<String> parts = new ArrayList<>();
                    addPart(parts, "type", p.getString("type"));
                    addPart(parts, "format", p.getString("format"));
                    addPart(parts, "description", p.getString("description"));
                    addPart(parts, "pattern", p.getString("pattern"));
                    addPart(parts, "minimum", p.getString("minimum"));
                    addPart(parts, "maximum", p.getString("maximum"));
                    addPart(parts, "minLength", p.getString("minLength"));
                    addPart(parts, "maxLength", p.getString("maxLength"));
                    JSONArray enums = p.getJSONArray("enum");
                    if (enums != null && !enums.isEmpty()) {
                        List<String> values = new ArrayList<>();
                        for (Object o : enums) if (o != null) values.add(String.valueOf(o));
                        Collections.sort(values);
                        parts.add("enum=" + String.join("|", values));
                    }
                    sb.append("property.").append(name).append("=");
                    sb.append(String.join(";", parts));
                    sb.append("\n");
                }
            }
            String normalized = sb.toString().trim();
            return normalized.isBlank() ? schema.trim() : normalized;
        } catch (Exception ignored) {
            return schema.trim();
        }
    }

    private static void append(Map<String, Entry> map, List<ToolCallback> callbacks, Source source) {
        if (callbacks == null) return;
        for (ToolCallback cb : callbacks) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            String name = cb.getToolDefinition().name();
            if (name == null || name.isBlank() || map.containsKey(name)) continue;
            String desc = safe(cb.getToolDefinition().description());
            String schema = readInputSchema(cb);
            map.put(name, new Entry(name, desc, schema, normalizeInputSchema(schema), sha256(schema), source));
        }
    }

    private static void appendKV(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) sb.append(key).append("=").append(value).append("\n");
    }

    private static void addPart(List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) parts.add(key + "=" + value);
    }

    private static String readInputSchema(ToolCallback cb) {
        try {
            Object def = cb.getToolDefinition();
            Object schema = def.getClass().getMethod("inputSchema").invoke(def);
            return schema == null ? "" : String.valueOf(schema);
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
