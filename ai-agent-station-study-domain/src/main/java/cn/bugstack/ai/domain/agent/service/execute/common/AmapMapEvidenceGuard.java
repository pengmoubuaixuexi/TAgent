package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.ToolEvidenceRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validate each personal-map point against provider POI coordinates, never other model arguments. */
final class AmapMapEvidenceGuard {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");
    private AmapMapEvidenceGuard() { }

    static boolean supports(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT), shortName = "maps_schema_personal_map";
        return n.equals(shortName) || n.endsWith("__" + shortName) || n.endsWith(":" + shortName);
    }

    /** Null means verified; any rejection prevents calling the external map service. */
    static String rejection(String arguments, List<ToolEvidenceRecord> evidence, ObjectMapper json) {
        try {
            if (arguments == null || arguments.length() > 100000) return "地图参数为空或过大";
            Map<String, BigDecimal[]> known = new HashMap<>();
            if (evidence != null) for (ToolEvidenceRecord row : evidence) {
                if (!AmapPoiResultCompactor.supports(row.getToolName()) || !"success".equals(row.getStatus())
                        || row.getOutput() == null || row.getOutput().length() > 262144) continue;
                try {
                    JsonNode raw = json.readTree(row.getOutput());
                    if (AmapPoiResultCompactor.containsError(raw, 0)) continue;
                    JsonNode pois = AmapPoiResultCompactor.findPois(raw, 0);
                    if (pois == null) continue;
                    for (JsonNode poi : pois.isArray() ? pois : json.createArrayNode().add(pois)) {
                        String id = poi.path("id").asText(poi.path("poiId").asText(""));
                        if (id.isBlank()) continue;
                        BigDecimal[] coordinate = coordinate(poi);
                        if (coordinate != null) known.put(id, coordinate);
                    }
                } catch (Exception malformedProviderResult) { /* No proof from this result. */ }
            }
            JsonNode input = json.readTree(arguments), lines = input == null ? null : input.get("lineList");
            if (lines == null || !lines.isArray() || lines.isEmpty()) return "缺少有效的 lineList";
            int count = 0;
            for (JsonNode line : lines) {
                JsonNode points = line.get("pointInfoList");
                if (points == null || !points.isArray() || points.isEmpty()) return "缺少有效的 pointInfoList";
                for (JsonNode point : points) {
                    if (++count > 100) return "地图地点过多，请先缩小范围";
                    String id = point.path("poiId").asText("");
                    BigDecimal[] actual = known.get(id);
                    if (id.isBlank() || actual == null) return "地点缺少本次运行已核实的 POI 坐标，请先用 maps_search_detail 查询各地点详情";
                    BigDecimal lon = number(point.get("lon")), lat = number(point.get("lat"));
                    if (lon == null || lat == null || lon.subtract(actual[0]).abs().compareTo(TOLERANCE) > 0
                            || lat.subtract(actual[1]).abs().compareTo(TOLERANCE) > 0)
                        return "坐标与该 poiId 的真实查询结果不一致；不得把景区中心坐标批量填到不同景点，请查询并使用各地点自己的坐标";
                }
            }
            return null;
        } catch (Exception invalidArguments) {
            return "地图参数无法校验，请使用有效 JSON 和真实 POI 坐标";
        }
    }

    private static BigDecimal[] coordinate(JsonNode poi) {
        String location = poi.path("location").asText("");
        try {
            BigDecimal lon, lat;
            if (!location.isBlank()) {
                String[] parts = location.split(",", -1);
                if (parts.length != 2) return null;
                lon = decimal(parts[0].trim()); lat = decimal(parts[1].trim());
            } else { lon = number(poi.get("longitude")); lat = number(poi.get("latitude")); }
            if (lon == null || lat == null || lon.abs().compareTo(BigDecimal.valueOf(180)) > 0
                    || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0) return null;
            return new BigDecimal[]{lon, lat};
        } catch (NumberFormatException invalid) { return null; }
    }

    private static BigDecimal number(JsonNode value) {
        if (value == null || (!value.isNumber() && !value.isTextual())) return null;
        return decimal(value.asText());
    }

    private static BigDecimal decimal(String text) {
        if (text.length() > 40) return null;
        try {
            BigDecimal value = new BigDecimal(text);
            return Math.abs((long)value.scale()) > 12 || value.precision() > 20 ? null : value;
        } catch (NumberFormatException invalid) { return null; }
    }
}
