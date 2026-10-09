package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded route summaries for the model. The caller must retain the untouched result as tool evidence. */
public final class AmapRouteResultCompactor {
    public static final int MAX_RESULT_CHARS = 6_000;
    private static final int MAX_PARSE_CHARS = 2_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOOL_NAMES = Set.of(
            "maps_direction_transit_integrated", "maps_direction_driving",
            "maps_direction_walking", "maps_direction_bicycling", "maps_direction_cycling");
    private static final Set<String> FIELDS = Set.of(
            "origin", "destination", "distance", "duration", "walking_distance", "cost", "expense",
            "tolls", "toll_distance", "taxi_cost", "transit_fee", "bus_fee", "nightflag", "strategy",
            "transits", "paths", "segments", "walking", "bus", "buslines", "railway", "taxi", "ride",
            "driving", "entrance", "exit", "steps", "departure_stop", "arrival_stop",
            "departure_station", "arrival_station", "name", "id", "uid", "poi_id", "poiid", "poiId",
            "location", "address", "url", "link", "scheme", "deep_link", "trip", "type", "number",
            "start_time", "end_time", "departure_time", "arrival_time", "instruction", "road",
            "action", "assistant_action", "price", "seat", "ticket", "spaces", "status", "info", "infocode");

    private AmapRouteResultCompactor() { }

    public static String compact(String toolName, String rawResult) {
        if (!isRouteTool(toolName) || rawResult == null || rawResult.length() <= MAX_RESULT_CHARS
                || rawResult.length() > MAX_PARSE_CHARS) return rawResult;
        try {
            JsonNode parsed = JSON.readTree(rawResult);
            if (containsError(parsed, 0)) return rawResult;
            JsonNode route = findRoute(parsed, 0);
            if (route == null) return rawResult;
            // First keep several alternatives. Reduce detail only if serialized JSON still exceeds the budget.
            for (Limits limits : new Limits[]{new Limits(3, 8, 3), new Limits(2, 6, 2),
                    new Limits(1, 6, 2), new Limits(1, 4, 1), new Limits(1, 2, 1), new Limits(1, 0, 0)}) {
                ObjectNode out = envelope(rawResult.length());
                out.set("route", summarize(route, "route", limits, 0));
                String result = JSON.writeValueAsString(out);
                if (result.length() <= MAX_RESULT_CHARS) return result;
            }
            // Unexpected, excessively large scalar objects: retain totals without breaking JSON or fabricating values.
            ObjectNode out = envelope(rawResult.length());
            ObjectNode totals = out.putObject("route");
            copyTotals(route, totals);
            for (String alternatives : new String[]{"transits", "paths"}) {
                JsonNode values = route.get(alternatives);
                if (values != null && values.isArray()) {
                    totals.put("total_" + alternatives, values.size());
                    if (!values.isEmpty()) copyTotals(values.get(0), totals.putObject("first_alternative"));
                }
            }
            out.put("detailOmitted", true);
            return JSON.writeValueAsString(out);
        } catch (Exception ignored) {
            // Unknown response shapes and business errors must retain their original meaning.
            return rawResult;
        }
    }

    private static ObjectNode envelope(int rawChars) {
        ObjectNode out = JSON.createObjectNode();
        out.put("source", "amap_route_compacted");
        out.put("compacted", true);
        out.put("rawChars", rawChars);
        out.put("notice", "Route summary only; geometry, intermediate stops and some alternatives/details are omitted. "
                + "Omitted details do not mean no route or no service.");
        return out;
    }

    private static boolean isRouteTool(String toolName) {
        if (toolName == null) return false;
        String normalized = toolName.toLowerCase(Locale.ROOT);
        for (String name : TOOL_NAMES) {
            if (normalized.equals(name) || normalized.endsWith("__" + name)
                    || normalized.endsWith(":" + name)) return true;
        }
        return false;
    }

    private static JsonNode findRoute(JsonNode node, int depth) throws Exception {
        if (node == null || depth > 8) return null;
        if (node.isTextual()) return findRoute(parseEmbedded(node.textValue()), depth + 1);
        if (node.isObject()) {
            if (node.path("transits").isArray() || node.path("paths").isArray()) return node;
            // MCP adapters return either content blocks, their text array, or the route object directly.
            for (String key : new String[]{"route", "data", "result", "structuredContent", "content", "text"}) {
                JsonNode route = findRoute(node.get(key), depth + 1);
                if (route != null) return route;
            }
        } else if (node.isArray()) {
            for (int i = 0; i < Math.min(node.size(), 16); i++) {
                JsonNode route = findRoute(node.get(i), depth + 1);
                if (route != null) return route;
            }
        }
        return null;
    }

    private static JsonNode parseEmbedded(String value) throws Exception {
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON.readTree(trimmed) : null;
    }

    private static boolean containsError(JsonNode node, int depth) throws Exception {
        if (node == null || depth > 8) return false;
        if (node.isTextual()) return containsError(parseEmbedded(node.textValue()), depth + 1);
        if (node.isArray()) {
            for (JsonNode child : node) if (containsError(child, depth + 1)) return true;
        } else if (node.isObject()) {
            if (node.path("isError").asBoolean(false) || "0".equals(node.path("status").asText())
                    || node.has("success") && !node.path("success").asBoolean(true)
                    || node.has("ok") && !node.path("ok").asBoolean(true)
                    || node.hasNonNull("infocode") && !"10000".equals(node.path("infocode").asText())
                    || node.hasNonNull("error") && !node.path("error").asText().isBlank()
                    || node.path("error").isObject()) return true;
            for (String key : new String[]{"route", "data", "result", "structuredContent", "content", "text"}) {
                if (containsError(node.get(key), depth + 1)) return true;
            }
        }
        return false;
    }

    private static JsonNode summarize(JsonNode node, String key, Limits limits, int depth) {
        if (depth > 14) return JSON.getNodeFactory().textNode("[detail omitted]");
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("via_stops".equals(field.getKey()) && field.getValue().isArray()) {
                    out.put("via_stop_count", field.getValue().size());
                } else if (FIELDS.contains(field.getKey())) {
                    JsonNode value = field.getValue();
                    out.set(field.getKey(), summarize(value, field.getKey(), limits, depth + 1));
                    if (value.isArray() && value.size() > arrayLimit(field.getKey(), limits)) {
                        out.put("omitted_" + field.getKey(), value.size() - arrayLimit(field.getKey(), limits));
                    }
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            int limit = Math.min(node.size(), arrayLimit(key, limits));
            for (int i = 0; i < limit; i++) {
                // Preserve the final transfer / arrival instruction when intermediate details are omitted.
                int sourceIndex = i == limit - 1 && limit > 1 && node.size() > limit
                        && ("segments".equals(key) || "steps".equals(key)) ? node.size() - 1 : i;
                out.add(summarize(node.get(sourceIndex), key, limits, depth + 1));
            }
            return out;
        }
        if (node.isTextual() && node.textValue().length() > 256) {
            // Never turn a clipped identifier or navigation URL into an apparently usable value.
            if (Set.of("id", "uid", "poi_id", "poiid", "poiId", "url", "link", "scheme", "deep_link").contains(key)) {
                return JSON.getNodeFactory().textNode("[oversized value omitted]");
            }
            return JSON.getNodeFactory().textNode(node.textValue().substring(0, 240) + " [truncated]");
        }
        return node;
    }

    private static int arrayLimit(String key, Limits limits) {
        return switch (key) {
            case "transits", "paths" -> limits.routes();
            case "segments" -> limits.segments();
            case "steps" -> limits.steps();
            default -> 2;
        };
    }

    private static void copyTotals(JsonNode source, ObjectNode target) {
        for (String key : new String[]{"origin", "destination", "distance", "duration", "walking_distance",
                "cost", "expense", "tolls", "taxi_cost"}) {
            JsonNode value = source.get(key);
            if (value != null && value.isValueNode() && value.asText().length() <= 128) target.set(key, value);
        }
    }

    private record Limits(int routes, int segments, int steps) { }
}
