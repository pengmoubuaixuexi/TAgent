package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/** Model-facing POI summaries only. The original provider response stays in the owned result store. */
public final class AmapPoiResultCompactor {
    public static final int MAX_RESULT_CHARS = 3_200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOOLS = Set.of("maps_text_search", "maps_around_search", "maps_search_detail");
    // Ordered by usefulness for subsequent lookup/navigation, not by the provider's property order.
    private static final String[] FIELDS = {"id", "uid", "poi_id", "poiid", "poiId", "name", "location",
            "longitude", "latitude", "address", "cityname", "adname", "pname", "citycode", "adcode",
            "type", "typecode", "distance", "tel", "website", "url", "link", "business_area",
            "opentime", "opentime_today", "opentime_week", "open_time", "business_time", "cost", "rating",
            "tag", "business", "biz_ext", "indoor_map", "entr_location", "exit_location"};
    private static final Set<String> EXACT_FIELDS = Set.of("id", "uid", "poi_id", "poiid", "poiId", "location",
            "longitude", "latitude", "entr_location", "exit_location", "url", "link", "website");
    private static final String[] WRAPPERS = {"data", "result", "structuredContent", "content", "text"};
    static {
        JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(2_000_000).maxNumberLength(1000).build());
    }

    private AmapPoiResultCompactor() { }

    public static String compact(String toolName, String raw) {
        if (!supports(toolName) || raw == null || raw.length() < 800 || raw.length() > 2_000_000) return raw;
        try {
            JsonNode document = JSON.readTree(raw);
            if (containsError(document, 0)) return raw;
            JsonNode data = findPois(document, 0);
            if (data == null) return raw;
            ArrayNode pois = data.isArray() ? (ArrayNode) data : JSON.createArrayNode().add(data);
            if (pois.isEmpty()) return raw;
            for (int maximum : new int[]{8, 6, 4, 2, 1}) {
                ObjectNode out = JSON.createObjectNode();
                out.put("source", "amap_poi_compacted"); out.put("compacted", true);
                out.put("rawChars", raw.length()); out.put("totalPois", pois.size());
                ArrayNode selected = out.putArray("pois");
                for (int i = 0; i < Math.min(maximum, pois.size()); i++) {
                    if (!pois.get(i).isObject()) return raw;
                    ObjectNode poi = select(pois.get(i), 0);
                    // Do not replace an unknown response with an empty or unidentified POI.
                    if (!poi.has("name") || !hasIdentity(poi)) return raw;
                    selected.add(poi);
                }
                out.put("returnedPois", selected.size());
                out.put("omittedPois", pois.size() - selected.size());
                out.put("notice", "POI summary; photos and secondary metadata are omitted. Missing fields are unknown, "
                        + "not unavailable. If a result reference is available, use it for remaining candidates "
                        + "or details rather than repeating this search.");
                String compact = out.toString();
                if (compact.length() <= MAX_RESULT_CHARS && compact.length() < raw.length()) return compact;
            }
        } catch (Exception ignored) {
            // Leave unfamiliar responses/business errors to the general bounded projection.
        }
        return raw;
    }

    private static boolean supports(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        return TOOLS.stream().anyMatch(tool -> normalized.equals(tool) || normalized.endsWith("__" + tool)
                || normalized.endsWith(":" + tool));
    }

    private static boolean hasIdentity(JsonNode poi) {
        return poi.hasNonNull("id") || poi.hasNonNull("uid") || poi.hasNonNull("poi_id")
                || poi.hasNonNull("poiid") || poi.hasNonNull("poiId") || poi.hasNonNull("location");
    }

    private static ObjectNode select(JsonNode source, int depth) {
        ObjectNode out = JSON.createObjectNode();
        for (String field : FIELDS) {
            JsonNode value = source.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isObject() && depth < 2) {
                ObjectNode nested = select(value, depth + 1);
                if (!nested.isEmpty()) out.set(field, nested);
            } else if (value.isValueNode()) {
                if (value.isTextual() && value.textValue().length() > (EXACT_FIELDS.contains(field) ? 512 : 200)) {
                    // A clipped ID/coordinate/URL could be executed as if real. Omit it explicitly instead.
                    if (EXACT_FIELDS.contains(field)) out.withArray("omittedFields").add(field);
                    else {
                        String text = value.textValue();
                        int end = Character.isHighSurrogate(text.charAt(199)) ? 199 : 200;
                        out.put(field, text.substring(0, end) + " [truncated]");
                    }
                } else out.set(field, value);
            }
        }
        return out;
    }

    private static JsonNode findPois(JsonNode node, int depth) throws Exception {
        if (node == null || depth > 8) return null;
        if (node.isTextual()) return findPois(embedded(node.textValue()), depth + 1);
        if (node.isObject()) {
            if (node.path("pois").isArray()) return node.get("pois");
            if (node.path("poi").isObject()) return node.get("poi");
            if (node.hasNonNull("name") && hasIdentity(node)) return node;
            for (String key : WRAPPERS) {
                JsonNode found = findPois(node.get(key), depth + 1);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            if (!node.isEmpty() && node.get(0).isObject() && node.get(0).has("name") && hasIdentity(node.get(0))) return node;
            for (int i = 0; i < Math.min(node.size(), 16); i++) {
                JsonNode found = findPois(node.get(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsError(JsonNode node, int depth) throws Exception {
        if (node == null || depth > 8) return false;
        if (node.isTextual()) return containsError(embedded(node.textValue()), depth + 1);
        if (node.isArray()) {
            for (JsonNode item : node) if (containsError(item, depth + 1)) return true;
        } else if (node.isObject()) {
            if (node.path("isError").asBoolean(false) || "0".equals(node.path("status").asText())
                    || node.has("success") && !node.path("success").asBoolean(true)
                    || node.has("ok") && !node.path("ok").asBoolean(true)
                    || node.hasNonNull("infocode") && !"10000".equals(node.path("infocode").asText())
                    || node.path("error").isObject() || node.hasNonNull("error") && !node.path("error").asText().isBlank()) return true;
            for (String key : WRAPPERS) if (containsError(node.get(key), depth + 1)) return true;
        }
        return false;
    }

    private static JsonNode embedded(String value) throws Exception {
        String text = value.trim();
        return text.startsWith("{") || text.startsWith("[") ? JSON.readTree(text) : null;
    }
}
