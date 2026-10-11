package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.AmapPoiResultCompactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.*;

public class AmapPoiResultCompactorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test public void ordinarySearchBelowGenericCeilingRetainsExecutableIdentityAndBoundedCandidates() throws Exception {
        String raw = search().toString();
        assertTrue(raw.length() < 6000);
        String compact = AmapPoiResultCompactor.compact("maps_text_search", raw);
        JsonNode result = JSON.readTree(compact);
        assertEquals("amap_poi_compacted", result.path("source").asText());
        assertEquals(20, result.path("totalPois").asInt());
        assertEquals(8, result.path("returnedPois").asInt());
        assertEquals(12, result.path("omittedPois").asInt());
        assertEquals("poi-0", result.at("/pois/0/id").asText());
        assertEquals("故宫博物院0", result.at("/pois/0/name").asText());
        assertEquals("116.397,39.916", result.at("/pois/0/location").asText());
        assertEquals("北京市东城区", result.at("/pois/0/address").asText());
        assertFalse(compact.contains("photo" + "\":"));
        assertTrue(compact.length() < raw.length() * .65);
        assertTrue(compact.length() <= AmapPoiResultCompactor.MAX_RESULT_CHARS);
    }

    @Test public void parsesMcpTextBlockAndNamespacedAroundSearch() throws Exception {
        String raw = JSON.createArrayNode().addObject().put("text", search().toString()).toString();
        // Use the adapter's actual [ { text: JSON-string } ] result shape.
        raw = "[" + raw + "]";
        JsonNode result = JSON.readTree(AmapPoiResultCompactor.compact("amap__maps_around_search", raw));
        assertEquals("poi-0", result.at("/pois/0/id").asText());
        assertEquals(20, result.path("totalPois").asInt());
    }

    @Test public void detailsRetainBusinessHoursCostRatingAndLinksWithoutPhotos() throws Exception {
        ObjectNode poi = (ObjectNode) search().path("pois").get(0).deepCopy();
        poi.put("website", "https://example.com/book?id=poi-0");
        poi.putObject("business").put("opentime_today", "08:30-17:00").put("cost", "60").put("rating", "4.9");
        poi.put("photo", "https://example.com/" + "image".repeat(500));
        JsonNode result = JSON.readTree(AmapPoiResultCompactor.compact("maps_search_detail", poi.toString()));
        assertEquals("08:30-17:00", result.at("/pois/0/business/opentime_today").asText());
        assertEquals("60", result.at("/pois/0/business/cost").asText());
        assertEquals("4.9", result.at("/pois/0/business/rating").asText());
        assertEquals("https://example.com/book?id=poi-0", result.at("/pois/0/website").asText());
        assertEquals(0, result.path("omittedPois").asInt());
    }

    @Test public void neverTurnsProviderBusinessErrorIntoSuccessfulPoiSummary() {
        ObjectNode failed = search(); failed.put("status", "0").put("info", "INVALID_USER_KEY");
        String raw = failed.toString();
        assertSame(raw, AmapPoiResultCompactor.compact("maps_text_search", raw));
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("isError", true); envelope.putArray("content").addObject().put("text", search().toString());
        raw = envelope.toString();
        assertSame(raw, AmapPoiResultCompactor.compact("maps_text_search", raw));
    }

    @Test public void unknownToolsMalformedAndSmallResponsesRemainUnchanged() {
        String raw = search().toString();
        assertSame(raw, AmapPoiResultCompactor.compact("maps_schema_personal_map", raw));
        String malformed = "{" + "x".repeat(1000);
        assertSame(malformed, AmapPoiResultCompactor.compact("maps_text_search", malformed));
        String empty = "{\"pois\":[]}";
        assertSame(empty, AmapPoiResultCompactor.compact("maps_text_search", empty));
    }

    @Test public void neverFabricatesUsableTruncatedIdentifiersOrLinks() throws Exception {
        ObjectNode poi = JSON.createObjectNode().put("id", "poi-1").put("name", "Palace")
                .put("website", "https://example.com/" + "x".repeat(1000));
        JsonNode summary = JSON.readTree(AmapPoiResultCompactor.compact("maps_search_detail", poi.toString()));
        assertEquals("poi-1", summary.at("/pois/0/id").asText());
        assertFalse(summary.at("/pois/0").has("website"));
        assertEquals("website", summary.at("/pois/0/omittedFields/0").asText());
        poi.put("id", "x".repeat(600));
        String raw = poi.toString();
        assertSame(raw, AmapPoiResultCompactor.compact("maps_search_detail", raw));
    }

    @Test public void projectionRemainsStructuredAndOriginalOwnedResponseIsStillReadable() throws Exception {
        var store = new ToolResultReferenceServiceTest.Store();
        String raw = search().toString();
        var saved = store.service.store(ToolResultReferenceServiceTest.SCOPE, "call-a", "maps_text_search", "{}", raw, "step", 1);
        JsonNode projection = JSON.readTree(store.service.project("maps_text_search", raw, raw, saved));
        assertTrue(projection.path("summary").isObject());
        assertEquals("poi-0", projection.at("/summary/pois/0/id").asText());
        assertEquals("tool:call-a", projection.path("resultRef").asText());
        assertEquals(raw, store.snapshot.getToolEvidences().get(0).getOutput());
        JsonNode page = JSON.readTree(store.service.inspect("{\"resultRef\":\"tool:call-a\",\"jsonPointer\":\"/pois/19/id\"}", ToolResultReferenceServiceTest.SCOPE));
        assertEquals("poi-19", page.path("data").asText());
        assertTrue(projection.toString().length() < raw.length());
    }

    @Test public void unavailableStorageIsExplicitAndNeverInventsReference() throws Exception {
        var service = new cn.bugstack.ai.domain.agent.service.execute.common.ToolResultReferenceService(
                (cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService) null);
        String raw = search().toString();
        JsonNode projection = JSON.readTree(service.project("maps_text_search", raw, raw, null));
        assertFalse(projection.has("resultRef"));
        assertFalse(projection.path("resultAvailable").asBoolean());
        assertTrue(projection.path("summary").isObject());
    }

    @Test public void lowConfiguredInlineLimitStillReturnsValidStructuredJson() throws Exception {
        var store = new ToolResultReferenceServiceTest.Store();
        var field = store.service.getClass().getDeclaredField("inlineChars");
        field.setAccessible(true); field.set(store.service, 1024);
        String raw = search().toString();
        var saved = store.service.store(ToolResultReferenceServiceTest.SCOPE, "call-a", "maps_text_search", "{}", raw, "step", 1);
        String projected = store.service.project("maps_text_search", raw, raw, saved);
        JsonNode parsed = JSON.readTree(projected);
        assertTrue(projected.length() <= 1024);
        assertTrue(parsed.path("summary").isObject());
        assertEquals("tool:call-a", parsed.path("resultRef").asText());
    }

    private static ObjectNode search() {
        ObjectNode root = JSON.createObjectNode(); var pois = root.putArray("pois");
        for (int i = 0; i < 20; i++) pois.addObject().put("id", "poi-" + i).put("name", "故宫博物院" + i)
                .put("address", "北京市东城区").put("location", "116.397,39.916").put("typecode", "110201")
                .put("photo", "https://example.com/" + "picture".repeat(12));
        return root;
    }
}
