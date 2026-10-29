package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regressions from the real Flow recording: false shared coordinates and WMO 55 misread as fog. */
public class MapAndWeatherGroundingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "maps_schema_personal_map";
    private static final String ARGS = "{\"lineList\":[{\"pointInfoList\":[{\"poiId\":\"park\",\"lon\":120.061159,\"lat\":30.255079},{\"poiId\":\"dock\",\"lon\":120.062,\"lat\":30.256}]}]}";
    @After public void clear() { MDC.clear(); }

    private ToolResultReferenceServiceTest.Store store() {
        var s = new ToolResultReferenceServiceTest.Store();
        s.service.store(ToolResultReferenceServiceTest.SCOPE, "park-call", "maps_search_detail", "{}",
                "[{\"text\":\"{\\\"id\\\":\\\"park\\\",\\\"name\\\":\\\"Park\\\",\\\"location\\\":\\\"120.061159,30.255079\\\"}\"}]", "step", 1);
        s.service.store(ToolResultReferenceServiceTest.SCOPE, "dock-call", "amap__maps_search_detail", "{}",
                "{\"id\":\"dock\",\"name\":\"Dock\",\"location\":\"120.062,30.256\"}", "step", 1);
        return s;
    }

    @Test public void acceptsKnownCoordinatesAndEquivalentDecimalRepresentations() {
        var s=store();
        assertNull(s.service.validateMapArguments(TOOL,ARGS,ToolResultReferenceServiceTest.SCOPE));
        assertNull(s.service.validateMapArguments("amap__"+TOOL,ARGS.replace("120.062","\"120.0620000\""),ToolResultReferenceServiceTest.SCOPE));
    }
    @Test public void blocksParkCenterCopiedToAnotherPoiAndUnknownIds() throws Exception {
        var s=store();
        for(String args: new String[]{ARGS.replace("120.062","120.061159").replace("30.256","30.255079"),
                ARGS.replace("dock","unknown"),ARGS.replace("120.062","\"1e-2147483647\""),"{}","null","{"})
            assertEquals("POI_COORDINATES_NOT_VERIFIED",JSON.readTree(s.service.validateMapArguments(TOOL,args,ToolResultReferenceServiceTest.SCOPE)).path("error").asText());
    }
    @Test public void legitimatelyColocatedPoisAreAllowedWhenBothHaveProviderEvidence() {
        var s=store();
        s.service.store(ToolResultReferenceServiceTest.SCOPE,"colocated","maps_search_detail","{}",
                "{\"id\":\"dock\",\"name\":\"Dock\",\"location\":\"120.061159,30.255079\"}","step",1);
        assertNull(s.service.validateMapArguments(TOOL,ARGS.replace("120.062","120.061159").replace("30.256","30.255079"),ToolResultReferenceServiceTest.SCOPE));
    }
    @Test public void unrelatedToolsAreNotRestrictedAndEvidenceCannotCrossOwnerRunOrTtl() {
        var s=store();
        assertNull(s.service.validateMapArguments("maps_schema_navi", "{}", Map.of()));
        for(var scope:java.util.List.of(Map.<String,Object>of("agent.run_id","run-a","userId","bob"),
                Map.<String,Object>of("agent.run_id","run-b","userId","alice"),Map.<String,Object>of()))
            assertNotNull(s.service.validateMapArguments(TOOL,ARGS,scope));
        s.snapshot.setExpiresAt(1L);
        assertNotNull(s.service.validateMapArguments(TOOL,ARGS,ToolResultReferenceServiceTest.SCOPE));
    }
    @Test public void businessErrorOrAnotherToolCannotProvideCoordinateProof() {
        var s=new ToolResultReferenceServiceTest.Store();
        String poi="{\"id\":\"park\",\"name\":\"Park\",\"location\":\"120.061159,30.255079\",\"status\":\"0\"}";
        s.service.store(ToolResultReferenceServiceTest.SCOPE,"error","maps_search_detail","{}",poi,"step",1);
        s.service.store(ToolResultReferenceServiceTest.SCOPE,"echo","echo","{}",poi.replace("\"0\"","\"1\""),"step",1);
        assertNotNull(s.service.validateMapArguments(TOOL,ARGS,ToolResultReferenceServiceTest.SCOPE));
    }
    @Test public void callbackNeverInvokesProviderForUnverifiedMapButForwardsVerifiedMap() {
        var s=store();ToolCallback delegate=mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name(TOOL).description("map").inputSchema("{}").build());
        when(delegate.call(anyString(),any(ToolContext.class))).thenReturn("map-result");
        var callback=new MeteredToolCallback(delegate,new McpToolMetrics(new SimpleMeterRegistry()),true);
        callback.setToolResultReferenceService(s.service);
        ToolContext context=new ToolContext(ToolResultReferenceServiceTest.SCOPE);
        assertTrue(callback.call(ARGS.replace("dock","unknown"),context).contains("POI_COORDINATES_NOT_VERIFIED"));
        verify(delegate,never()).call(anyString(),any(ToolContext.class));
        assertEquals("map-result",callback.call(ARGS,context));
        verify(delegate,times(1)).call(eq(ARGS),any(ToolContext.class));
    }
    @Test public void weatherLabelsDistinguishFogFromDenseDrizzleWithoutChangingSourceNumbers() throws Exception {
        var s=new ToolResultReferenceServiceTest.Store();
        String inner="{\"daily\":{\"time\":[\"2026-10-31\",\"2026-11-01\"],\"weather_code\":[45,55],\"temperature_2m_max\":[24.4,29.1]}}";
        String raw=JSON.createArrayNode().add(JSON.createObjectNode().put("text",inner)).toString();
        var saved=s.service.store(ToolResultReferenceServiceTest.SCOPE,"weather","weather_forecast","{}",raw,"step",1);
        JsonNode projected=JSON.readTree(s.service.project("weather_forecast",raw,raw,saved));
        JsonNode summary=JSON.readTree(projected.path("summary").asText());
        assertEquals("雾 / Fog",summary.path("weather_code_legend").path("45").asText());
        assertEquals("毛毛雨（强） / Dense drizzle",summary.path("weather_code_legend").path("55").asText());
        assertEquals(JSON.readTree(inner),summary.get("forecast"));
        assertEquals(raw,s.snapshot.getToolEvidences().get(0).getOutput());
        assertEquals("tool:weather",projected.path("resultRef").asText());
        assertTrue(projected.toString().length()<=6000);
    }
    @Test public void unknownCodesErrorsAndOtherToolsAreNeverInventedOrRelabelled() throws Exception {
        var s=new ToolResultReferenceServiceTest.Store();
        for(String raw:java.util.List.of("{\"daily\":{\"weather_code\":[1000]}}","{\"ok\":false,\"daily\":{\"weather_code\":[55]}}","not-json"))
            assertEquals(raw,s.service.project("weather_forecast",raw,raw,null));
        String raw="{\"daily\":{\"weather_code\":[55]}}";
        assertEquals(raw,s.service.project("other_forecast",raw,raw,null));
        JsonNode missingRef=JSON.readTree(s.service.project("weather_forecast",raw,raw,null));
        assertFalse(missingRef.path("resultAvailable").asBoolean(true));
        assertFalse(missingRef.has("resultRef"));
    }
}
