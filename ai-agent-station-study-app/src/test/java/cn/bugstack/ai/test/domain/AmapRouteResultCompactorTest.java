package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.AmapRouteResultCompactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.*;

public class AmapRouteResultCompactorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "maps_direction_transit_integrated";

    @Test
    public void preservesUsefulTransitDetailsAndOmitsGeometryAndAlternativeOverflow() throws Exception {
        String raw = JSON.writeValueAsString(route());
        String compact = AmapRouteResultCompactor.compact(TOOL, raw);
        JsonNode out = JSON.readTree(compact);

        assertTrue(compact.length() <= AmapRouteResultCompactor.MAX_RESULT_CHARS);
        assertTrue(compact.length() < raw.length() / 2);
        assertTrue(out.path("compacted").asBoolean());
        assertEquals(raw.length(), out.path("rawChars").asInt());
        JsonNode route = out.path("route");
        assertEquals("116.1,39.1", route.path("origin").asText());
        assertEquals("5800", route.path("distance").asText());
        JsonNode first = route.path("transits").get(0);
        assertEquals("1800", first.path("duration").asText());
        assertEquals("6", first.path("cost").asText());
        JsonNode line = first.path("segments").get(0).path("bus").path("buslines").get(0);
        assertEquals("Line 2", line.path("name").asText());
        assertEquals("Start station", line.path("departure_stop").path("name").asText());
        assertEquals("Destination station", line.path("arrival_stop").path("name").asText());
        assertEquals("poi-arrival", line.path("arrival_stop").path("poiId").asText());
        assertEquals("https://example.com/route", line.path("url").asText());
        assertEquals(12, line.path("via_stop_count").asInt());
        assertFalse(compact.contains("polyline"));
        assertFalse(compact.contains("via_stops"));
        assertTrue(route.path("omitted_transits").asInt() > 0);
        assertTrue(first.path("segments").get(0).path("walking").path("omitted_steps").asInt() > 0);
        // Last arrival instruction remains visible even when the intermediate turns are omitted.
        assertTrue(compact.contains("Arrive at station"));
    }

    @Test
    public void handlesMcpTextBlockArrayUsedByTheMapAdapter() throws Exception {
        String route = JSON.writeValueAsString(route());
        ArrayNode blocks = JSON.createArrayNode();
        blocks.addObject().put("text", route);
        String compact = AmapRouteResultCompactor.compact(TOOL, JSON.writeValueAsString(blocks));

        assertEquals("amap_route_compacted", JSON.readTree(compact).path("source").asText());
        assertEquals("116.1,39.1", JSON.readTree(compact).path("route").path("origin").asText());
    }

    @Test
    public void handlesMcpEnvelopeAndNamespacedTool() throws Exception {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("isError", false);
        envelope.putArray("content").addObject().put("type", "text")
                .put("text", JSON.writeValueAsString(route()));
        String compact = AmapRouteResultCompactor.compact("amap__" + TOOL, JSON.writeValueAsString(envelope));

        assertTrue(JSON.readTree(compact).path("compacted").asBoolean());
        assertTrue(compact.length() <= AmapRouteResultCompactor.MAX_RESULT_CHARS);
    }

    @Test
    public void leavesSmallResultsAndUnrelatedToolsUnchanged() throws Exception {
        String small = "{\"origin\":\"a\",\"destination\":\"b\",\"transits\":[]}";
        assertSame(small, AmapRouteResultCompactor.compact(TOOL, small));
        String large = JSON.writeValueAsString(route());
        assertSame(large, AmapRouteResultCompactor.compact("maps_text_search", large));
        assertSame(large, AmapRouteResultCompactor.compact("maps_schema_personal_map", large));
        assertNull(AmapRouteResultCompactor.compact(TOOL, null));
    }

    @Test
    public void preservesBusinessErrorsIncludingErrorsInsideMcpText() throws Exception {
        ObjectNode failed = route();
        failed.put("status", "0");
        failed.put("info", "CUQPS_HAS_EXCEEDED_THE_LIMIT");
        String direct = JSON.writeValueAsString(failed);
        assertSame(direct, AmapRouteResultCompactor.compact(TOOL, direct));

        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("isError", true);
        envelope.putArray("content").addObject().put("text", JSON.writeValueAsString(route()));
        String mcpError = JSON.writeValueAsString(envelope);
        assertSame(mcpError, AmapRouteResultCompactor.compact(TOOL, mcpError));

        ArrayNode blocks = JSON.createArrayNode();
        blocks.addObject().put("text", direct);
        String nestedError = JSON.writeValueAsString(blocks);
        assertSame(nestedError, AmapRouteResultCompactor.compact(TOOL, nestedError));
    }

    @Test
    public void leavesUnknownAndMalformedResponsesUnchanged() {
        String unknown = "{\"message\":\"" + "x".repeat(8_000) + "\"}";
        String malformed = "[{\"text\":\"" + "x".repeat(8_000);
        assertSame(unknown, AmapRouteResultCompactor.compact(TOOL, unknown));
        assertSame(malformed, AmapRouteResultCompactor.compact(TOOL, malformed));
    }

    @Test
    public void walkingPathOutputStaysValidAndBoundedEvenAfterJsonEscaping() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("origin", "a");
        root.put("destination", "b");
        ObjectNode path = root.putArray("paths").addObject();
        path.put("distance", "1200");
        path.put("duration", "900");
        ArrayNode steps = path.putArray("steps");
        for (int i = 0; i < 30; i++) {
            steps.addObject().put("instruction", "\n\"\\".repeat(600))
                    .put("distance", "40").put("polyline", "116.1,39.1;".repeat(600));
        }
        String compact = AmapRouteResultCompactor.compact("maps_direction_walking", JSON.writeValueAsString(root));
        JsonNode out = JSON.readTree(compact);
        assertTrue(compact.length() <= AmapRouteResultCompactor.MAX_RESULT_CHARS);
        assertEquals("1200", out.path("route").path("paths").get(0).path("distance").asText());
        assertTrue(compact.contains("[truncated]"));
        assertFalse(compact.contains("polyline"));
    }

    private static ObjectNode route() {
        ObjectNode route = JSON.createObjectNode();
        route.put("origin", "116.1,39.1");
        route.put("destination", "116.2,39.2");
        route.put("distance", "5800");
        ArrayNode alternatives = route.putArray("transits");
        for (int i = 0; i < 6; i++) {
            ObjectNode transit = alternatives.addObject();
            transit.put("duration", "1800");
            transit.put("cost", "6");
            transit.put("walking_distance", "300");
            ObjectNode segment = transit.putArray("segments").addObject();
            ObjectNode walking = segment.putObject("walking");
            walking.put("distance", "300");
            walking.put("duration", "250");
            ArrayNode steps = walking.putArray("steps");
            for (int step = 0; step < 8; step++) {
                steps.addObject().put("instruction", step == 7 ? "Arrive at station" : "Walk to the next street")
                        .put("distance", "40").put("polyline", "116.1,39.1;".repeat(180));
            }
            ObjectNode line = segment.putObject("bus").putArray("buslines").addObject();
            line.put("name", "Line 2");
            line.put("distance", "5500");
            line.put("duration", "1400");
            line.put("url", "https://example.com/route");
            line.putObject("departure_stop").put("name", "Start station").put("id", "start-id");
            line.putObject("arrival_stop").put("name", "Destination station").put("poiId", "poi-arrival");
            ArrayNode stops = line.putArray("via_stops");
            for (int stop = 0; stop < 12; stop++) stops.addObject().put("name", "Intermediate " + stop);
            segment.putObject("entrance").put("name", "A exit");
        }
        return route;
    }
}
