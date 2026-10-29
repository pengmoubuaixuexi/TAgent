package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;

/** Deterministic WMO labels for Open-Meteo daily codes; no model or forecast rewriting. */
final class WeatherForecastResultAnnotator {
    // Source: https://open-meteo.com/en/docs (WMO weather interpretation codes).
    private static final Map<Integer, String> LABELS = Map.ofEntries(
            Map.entry(0,"晴 / Clear sky"), Map.entry(1,"大部晴朗 / Mainly clear"),
            Map.entry(2,"局部多云 / Partly cloudy"), Map.entry(3,"阴 / Overcast"),
            Map.entry(45,"雾 / Fog"), Map.entry(48,"雾凇 / Depositing rime fog"),
            Map.entry(51,"毛毛雨（弱） / Light drizzle"), Map.entry(53,"毛毛雨（中等） / Moderate drizzle"),
            Map.entry(55,"毛毛雨（强） / Dense drizzle"),
            Map.entry(56,"冻毛毛雨（弱） / Light freezing drizzle"), Map.entry(57,"冻毛毛雨（强） / Dense freezing drizzle"),
            Map.entry(61,"雨（弱） / Slight rain"), Map.entry(63,"雨（中等） / Moderate rain"), Map.entry(65,"雨（强） / Heavy rain"),
            Map.entry(66,"冻雨（弱） / Light freezing rain"), Map.entry(67,"冻雨（强） / Heavy freezing rain"),
            Map.entry(71,"降雪（弱） / Slight snow fall"), Map.entry(73,"降雪（中等） / Moderate snow fall"),
            Map.entry(75,"降雪（强） / Heavy snow fall"), Map.entry(77,"米雪 / Snow grains"),
            Map.entry(80,"阵雨（弱） / Slight rain showers"), Map.entry(81,"阵雨（中等） / Moderate rain showers"),
            Map.entry(82,"阵雨（强） / Violent rain showers"),
            Map.entry(85,"阵雪（弱） / Slight snow showers"), Map.entry(86,"阵雪（强） / Heavy snow showers"),
            Map.entry(95,"雷暴（弱至中等） / Slight or moderate thunderstorm"),
            Map.entry(96,"雷暴伴小冰雹 / Thunderstorm with slight hail"), Map.entry(99,"雷暴伴大冰雹 / Thunderstorm with heavy hail"));
    private WeatherForecastResultAnnotator() { }

    static String annotate(String name, String raw, ObjectMapper json) {
        if (name == null || raw == null || raw.length() > 262144) return raw;
        String n = name.toLowerCase(Locale.ROOT), tool = "weather_forecast";
        if (!(n.equals(tool) || n.endsWith("__" + tool) || n.endsWith(":" + tool))) return raw;
        try {
            JsonNode document = json.readTree(raw);
            if (AmapPoiResultCompactor.containsError(document, 0)) return raw;
            JsonNode forecast = find(document, json, 0);
            if (forecast == null) return raw;
            JsonNode codes = forecast.path("daily").get("weather_code");
            if (codes == null || !codes.isArray() || codes.isEmpty() || codes.size() > 32) return raw;
            ObjectNode out = json.createObjectNode();
            out.set("forecast", forecast);
            ObjectNode labels = out.putObject("weather_code_legend");
            for (JsonNode code : codes) if (code.isIntegralNumber() && code.canConvertToInt() && LABELS.containsKey(code.intValue()))
                labels.put(code.asText(), LABELS.get(code.intValue()));
            if (labels.isEmpty()) return raw;
            out.put("legend_source", "https://open-meteo.com/en/docs");
            out.put("notice", "固定 WMO 代码释义；" + (labels.has("55") ? "55 是强毛毛雨，不是浓雾。" : "")
                    + "forecast 保留原始数值；预报不是天气保证。");
            return out.toString();
        } catch (Exception unfamiliar) { return raw; }
    }

    private static JsonNode find(JsonNode node, ObjectMapper json, int depth) throws Exception {
        if (node == null || depth > 8) return null;
        if (node.isTextual()) {
            String s = node.asText().trim();
            return s.startsWith("{") || s.startsWith("[") ? find(json.readTree(s), json, depth + 1) : null;
        }
        if (node.isObject()) {
            if (node.path("daily").isObject()) return node;
            for (String key : new String[]{"content","text","data","result","structuredContent"}) {
                JsonNode result = find(node.get(key), json, depth + 1);
                if (result != null) return result;
            }
        } else if (node.isArray()) for (int i=0; i<Math.min(16,node.size());i++) {
            JsonNode result=find(node.get(i),json,depth+1); if(result!=null)return result;
        }
        return null;
    }
}
