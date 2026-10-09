package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.armory.node.AiClientToolMcpNode;
import cn.bugstack.ai.infrastructure.adapter.repository.AgentRepository;
import cn.bugstack.ai.infrastructure.dao.IAiClientToolMcpDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientToolMcp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Reproducible optional configuration; no Spring context or saved credentials. */
public class ParallelSearchMcpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_AGENT = "TAgent/parallel-search-example";

    private String fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/dev-ops/parallel-search/transport.json"))) {
            root = root.getParent();
        }
        assertNotNull("Run from the repository or a module directory", root);
        return Files.readString(root.resolve("docs/dev-ops/parallel-search/transport.json"));
    }

    private AiClientToolMcpVO load(String config) {
        IAiClientToolMcpDao dao = mock(IAiClientToolMcpDao.class);
        when(dao.queryByMcpId("parallel-example")).thenReturn(AiClientToolMcp.builder()
                .mcpId("parallel-example").mcpName("parallel-search").transportType("streamable-http")
                .transportConfig(config).requestTimeout(180).status(1).build());
        AgentRepository repository = new AgentRepository();
        ReflectionTestUtils.setField(repository, "aiClientToolMcpDao", dao);
        AiClientToolMcpVO configVO = repository.queryAiClientToolMcpVOByMcpId("parallel-example");
        assertNotNull(configVO);
        assertEquals(Map.of("User-Agent", USER_AGENT), configVO.getTransportConfigStreamableHttp().getHeaders());
        return configVO;
    }

    private void execute(McpSyncClient client) throws Exception {
        List<String> tools = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
        assertTrue(tools.containsAll(List.of("web_search", "web_fetch")));
        String session = UUID.randomUUID().toString();
        assertUseful(client.callTool(new McpSchema.CallToolRequest("web_search", Map.of(
                "objective", "Find the official Spring AI MCP client documentation",
                "search_queries", List.of("Spring AI MCP client documentation"), "session_id", session))));
        assertUseful(client.callTool(new McpSchema.CallToolRequest("web_fetch", Map.of(
                "urls", List.of("https://docs.parallel.ai/integrations/mcp/search-mcp"),
                "objective", "Find anonymous MCP connection instructions", "session_id", session))));
    }

    private void assertUseful(McpSchema.CallToolResult result) throws Exception {
        assertFalse("Tool returned an error: " + result, Boolean.TRUE.equals(result.isError()));
        String text = result.content().stream().filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text)
                .findFirst().orElseThrow();
        JsonNode results = JSON.readTree(text).path("results");
        assertTrue("Expected results: " + text, results.isArray() && !results.isEmpty());
        assertTrue(results.get(0).path("url").asText().startsWith("https://"));
        assertFalse(results.get(0).path("excerpts").get(0).asText().isBlank());
    }

    @Test
    public void loaderSendsAnonymousRequestsWithProjectHeaders() throws Exception {
        JsonNode fixture = JSON.readTree(fixture());
        assertEquals("https://search.parallel.ai/mcp", fixture.path("url").asText());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> methods = new CopyOnWriteArrayList<>();
        List<String> failures = new CopyOnWriteArrayList<>();
        server.createContext("/mcp", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if (!"/mcp".equals(exchange.getRequestURI().toString())) failures.add("Wrong endpoint");
            if (!USER_AGENT.equals(exchange.getRequestHeaders().getFirst("User-Agent"))) failures.add("Missing User-Agent");
            if (exchange.getRequestHeaders().containsKey("Authorization") || exchange.getRequestHeaders().containsKey("x-api-key")) failures.add("Unexpected credentials");
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            String result;
            switch (method) {
                case "initialize" -> result = "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                case "tools/list" -> result = "{\"tools\":[{\"name\":\"web_search\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},{\"name\":\"web_fetch\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
                case "tools/call" -> {
                    methods.add(request.path("params").path("name").asText());
                    String output = "{\"results\":[{\"url\":\"https://example.org/docs\",\"excerpts\":[\"MCP documentation excerpt\"]}]}";
                    result = JSON.writeValueAsString(Map.of("content", List.of(Map.of("type", "text", "text", output)), "isError", false));
                }
                default -> result = "{}";
            }
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
            } else {
                byte[] bytes = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            var config = (com.fasterxml.jackson.databind.node.ObjectNode) fixture;
            config.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            McpSyncClient client = new AiClientToolMcpNode().createMcpSyncClient(load(config.toString()));
            try { execute(client); } finally { client.closeGracefully(); }
            assertEquals(List.of(), failures);
            assertTrue(methods.containsAll(List.of("initialize", "tools/list", "web_search", "web_fetch")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void liveAnonymousSearchAndFetch() throws Exception {
        Assume.assumeTrue("Enable with -Dparallel.search.live=true", Boolean.getBoolean("parallel.search.live"));
        McpSyncClient client = new AiClientToolMcpNode().createMcpSyncClient(load(fixture()));
        try { execute(client); } finally { client.closeGracefully(); }
    }
}
