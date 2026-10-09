package cn.bugstack.ai.test.observe;

import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.trigger.http.ObserveController;
import com.alibaba.fastjson.JSON;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Ownership is enforced at the read path, not only by hiding UI links. */
public class ObserveUserIsolationTest {
    private ObserveController controller;
    private MockMvc mvc;
    private SimpleMeterRegistry meters;
    private McpToolMetrics metrics;

    @Before public void setUp() {
        controller = new ObserveController();
        meters = new SimpleMeterRegistry();
        metrics = new McpToolMetrics(meters);
        ReflectionTestUtils.setField(controller, "meterRegistry", meters);
        ReflectionTestUtils.setField(controller, "mcpToolMetrics", metrics);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .defaultRequest(get("/").accept(org.springframework.http.MediaType.APPLICATION_JSON)).build();
        login("alice", false);
    }

    @After public void tearDown() {
        SecurityContextHolder.clearContext();
        org.slf4j.MDC.clear();
        meters.close();
    }

    private void login(String user, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "unused",
                List.of(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_USER"))));
    }

    @Test public void anonymousAndRegularUserCannotReadGlobalObservation() throws Exception {
        String[] endpoints = {"summary", "token-by-model", "calls-by-session-today", "mcp-client-health", "mcp-tools-status"};
        for (String endpoint : endpoints) {
            mvc.perform(get("/api/v1/observe/" + endpoint).param("scope", "all")).andExpect(status().isForbidden());
        }
        SecurityContextHolder.clearContext();
        for (String endpoint : endpoints) {
            mvc.perform(get("/api/v1/observe/" + endpoint)).andExpect(status().isUnauthorized());
        }
    }

    @Test public void everyElasticsearchAggregationFiltersAuthenticatedUser() throws Exception {
        RestClient es = mock(RestClient.class);
        org.elasticsearch.client.Response response = mock(org.elasticsearch.client.Response.class);
        String result = "{\"aggregations\":{\"models\":{\"buckets\":[],\"value\":0},\"sessions\":{\"buckets\":[],\"value\":0},"
                + "\"calls\":{\"value\":0},\"totalTokens\":{\"value\":0},\"promptTokens\":{\"value\":0},"
                + "\"completionTokens\":{\"value\":0},\"avgLatency\":{\"value\":0}}}";
        when(response.getEntity()).thenAnswer(inv -> new StringEntity(result, java.nio.charset.StandardCharsets.UTF_8));
        when(es.performRequest(any(Request.class))).thenReturn(response);
        ReflectionTestUtils.setField(controller, "restClient", es);
        for (String endpoint : List.of("summary", "token-by-model", "calls-by-session-today")) {
            mvc.perform(get("/api/v1/observe/" + endpoint).param("userId", "bob").header("X-User-Id", "bob"))
                    .andExpect(status().isOk());
        }
        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(es, times(3)).performRequest(captor.capture());
        for (Request request : captor.getAllValues()) {
            var filters = JSON.parseObject(EntityUtils.toString(request.getEntity())).getJSONObject("query")
                    .getJSONObject("bool").getJSONArray("filter");
            assertEquals("alice", filters.getJSONObject(filters.size() - 1).getJSONObject("term").getString("userId"));
        }
        clearInvocations(es);
        login("admin", true);
        mvc.perform(get("/api/v1/observe/summary").param("scope", "all")).andExpect(status().isOk());
        verify(es).performRequest(captor.capture());
        assertFalse(EntityUtils.toString(captor.getValue().getEntity()).contains("userId"));
    }

    @Test public void toolMetricsSeparateUsersAndSameNamedConnectionsAndHideSecrets() throws Exception {
        try (var ignored = metrics.scope("alice", "mcp-a")) {
            metrics.recordCall("search", 10, true);
            metrics.recordError("search", new IllegalStateException("Authorization Bearer TOP_SECRET"));
        }
        try (var ignored = metrics.scope("alice", "mcp-b")) { metrics.recordCall("search", 11, true); }
        try (var ignored = metrics.scope("bob", "mcp-c")) { metrics.recordCall("secret_tool", 12, true); }
        // Legacy metrics lacking user identity must not appear in a personal panel.
        meters.timer("mcp.tool.call", "tool", "legacy", "outcome", "success").record(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        String body = mvc.perform(get("/api/v1/observe/mcp-tools-status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.totalCalls").value(2))
                .andExpect(jsonPath("$.data.tools.length()").value(2))
                .andExpect(jsonPath("$.data.tools[0].mcpId").value("mcp-a"))
                .andExpect(jsonPath("$.data.tools[1].mcpId").value("mcp-b"))
                .andExpect(jsonPath("$.data.aggregation").value("PROCESS_LIFETIME"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("TOP_SECRET"));
        assertFalse(body.contains("secret_tool"));
        assertFalse(body.contains("legacy"));
        login("admin", true);
        mvc.perform(get("/api/v1/observe/mcp-tools-status").param("scope", "all"))
                .andExpect(jsonPath("$.data.summary.totalCalls").value(4));
    }

    @Test public void clientHealthFiltersBeforeAnyProbe() throws Exception {
        var clients = mock(McpClientRegistry.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("alice"))).thenReturn(List.of("mcp-a"));
        when(clients.snapshotSelected(Set.of("mcp-a"))).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "mcpClientRegistry", clients);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        mvc.perform(get("/api/v1/observe/mcp-client-health").param("userId", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.totalClients").value(0));
        verify(clients).snapshotSelected(Set.of("mcp-a"));
        verify(clients, never()).snapshotAll();
        verify(jdbc).queryForList(contains("s.is_public=1"), eq(String.class), eq("alice"));
    }

    @Test public void connectionNamesAreResolvedForAuthorizedHealthAndMetricRowsOnly() throws Exception {
        var clients = mock(McpClientRegistry.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("alice"))).thenReturn(List.of("mcp-a"));
        when(jdbc.queryForList(startsWith("SELECT mcp_id,mcp_name"), eq("mcp-a"), eq("alice")))
                .thenReturn(List.of(Map.of("mcp_id", "mcp-a", "mcp_name", "联网搜索")));
        when(clients.snapshotSelected(Set.of("mcp-a"))).thenReturn(List.of(new McpClientRegistry.ClientHealthSnapshot(
                "mcp-a", "alive", 0, null, 0, null, 0, null, null, null, List.of("search"))));
        ReflectionTestUtils.setField(controller, "mcpClientRegistry", clients);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        try (var ignored = metrics.scope("alice", "mcp-a")) { metrics.recordCall("search", 10, true); }
        try (var ignored = metrics.scope("bob", "mcp-b")) { metrics.recordCall("private_search", 10, true); }
        mvc.perform(get("/api/v1/observe/mcp-client-health"))
                .andExpect(jsonPath("$.data.clients[0].mcpName").value("联网搜索"))
                .andExpect(jsonPath("$.data.clients[0].mcpId").value("mcp-a"))
                .andExpect(jsonPath("$.data.summary.totalClients").value(1));
        mvc.perform(get("/api/v1/observe/mcp-tools-status"))
                .andExpect(jsonPath("$.data.tools[0].mcpName").value("联网搜索"))
                .andExpect(jsonPath("$.data.tools.length()").value(1));
        verify(jdbc, times(2)).queryForList(eq("SELECT mcp_id,mcp_name FROM ai_client_tool_mcp WHERE mcp_id IN (?) AND owner_user_id=?"),
                eq("mcp-a"), eq("alice"));
    }

    @Test public void deletedConnectionKeepsMetricsWithoutResolvingAnotherOwnersName() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        try (var ignored = metrics.scope("alice", "mcp-removed")) { metrics.recordCall("search", 10, true); }
        mvc.perform(get("/api/v1/observe/mcp-tools-status"))
                .andExpect(jsonPath("$.data.tools[0].mcpId").value("mcp-removed"))
                .andExpect(jsonPath("$.data.tools[0].mcpName").doesNotExist())
                .andExpect(jsonPath("$.data.summary.totalCalls").value(1));
        verify(jdbc).queryForList(contains("AND owner_user_id=?"), eq("mcp-removed"), eq("alice"));
        login("admin", true);
        when(jdbc.queryForList(anyString(), eq("mcp-removed")))
                .thenReturn(List.of(Map.of("mcp_id", "mcp-removed", "mcp_name", "历史连接")));
        mvc.perform(get("/api/v1/observe/mcp-tools-status").param("scope", "all"))
                .andExpect(jsonPath("$.data.tools[0].mcpName").value("历史连接"));
        verify(jdbc).queryForList(eq("SELECT mcp_id,mcp_name FROM ai_client_tool_mcp WHERE mcp_id IN (?)"), eq("mcp-removed"));
    }

    @Test public void callbackUsesToolContextIdentityAcrossThreadHopsAndRestoresScope() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name("echo").description("test")
                .inputSchema("{\"type\":\"object\"}").build());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");
        McpClientRegistry registry = mock(McpClientRegistry.class);
        when(registry.getCurrentCallback("mcp-a", "echo")).thenReturn(delegate);
        var callback = new MeteredToolCallback(delegate, metrics, false, false, 10, 20000, false,
                true, 1, 1, registry, "mcp-a");
        org.slf4j.MDC.put("userId", "stale-thread-user");
        assertEquals("ok", callback.call("{}", new ToolContext(Map.of("userId", "alice", "agentId", "agent-a"))));
        verify(registry).assertAccessible("mcp-a", "alice", "agent-a");
        assertEquals(1, meters.find("mcp.tool.call").tag("userId", "alice").tag("mcpId", "mcp-a").timer().count());
        assertNull(meters.find("mcp.tool.call").tag("userId", "stale-thread-user").timer());
        metrics.recordCall("after", 1, true);
        assertEquals(1, meters.find("mcp.tool.call").tag("tool", "after").tag("userId", "stale-thread-user").timer().count());
    }

    @Test public void callbackRejectsRevokedConnectionWithoutCallingStaleDelegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name("echo").description("test")
                .inputSchema("{\"type\":\"object\"}").build());
        var registry = mock(McpClientRegistry.class);
        doThrow(new SecurityException("MCP unavailable")).when(registry).assertAccessible("mcp-a", "bob", "agent-b");
        var callback = new MeteredToolCallback(delegate, metrics, false, false, 10, 20000, false,
                true, 1, 1, registry, "mcp-a");
        try {
            callback.call("{}", new ToolContext(Map.of("userId", "bob", "agentId", "agent-b")));
            fail("cross-user callback must fail closed");
        } catch (SecurityException expected) { }
        verify(delegate, never()).call(anyString(), any(ToolContext.class));
    }
}
