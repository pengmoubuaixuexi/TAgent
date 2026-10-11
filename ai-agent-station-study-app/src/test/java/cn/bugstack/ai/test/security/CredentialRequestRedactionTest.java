package cn.bugstack.ai.test.security;

import cn.bugstack.ai.api.dto.AiClientToolMcpRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

public class CredentialRequestRedactionTest {
    @Test public void mcpRequestLoggingDoesNotExposeRemoteOrStdioCredentials() {
        for (String config : new String[]{
                "{\"url\":\"https://mcp.example.test?key=synthetic-query-secret\",\"headers\":{\"Authorization\":\"Bearer synthetic-header-secret\"}}",
                "{\"command\":\"node\",\"env\":{\"API_KEY\":\"synthetic-env-secret\"}}"}) {
            var request = AiClientToolMcpRequestDTO.builder().mcpId("public-search")
                    .mcpName("Public search").transportConfig(config).build();
            String logged = request.toString();
            assertTrue(logged.contains("public-search"));
            assertFalse(logged.contains("transportConfig"));
            assertFalse(logged.contains("synthetic-"));
            assertEquals(config, request.getTransportConfig());
        }
    }

    @Test public void redactionDoesNotDiscardConfigurationDuringRequestDeserialization() throws Exception {
        var json = new ObjectMapper();
        var request = json.readValue("{\"mcpId\":\"public-search\",\"transportConfig\":\"synthetic-config-secret\"}",
                AiClientToolMcpRequestDTO.class);
        assertEquals("synthetic-config-secret", request.getTransportConfig());
        assertFalse(request.toString().contains("synthetic-config-secret"));
    }
}
