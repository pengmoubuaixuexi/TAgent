package cn.bugstack.ai.test.observe;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.trigger.http.ObserveController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No network/DB: exercise real Spring injection, registry, metering and MVC serialization. */
public class McpObserveReadPathTest {
    @org.junit.Before public void authenticateAdmin() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "unused",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @org.junit.After public void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    public void cloudMetricsConfigurationExportsTheSameCountersReadByPanel() throws Exception {
        var cloud = java.nio.file.Path.of("../docs/dev-ops/server/application-cloud.yml");
        if (!java.nio.file.Files.exists(cloud)) cloud = java.nio.file.Path.of("docs/dev-ops/server/application-cloud.yml");
        var properties = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("cloud-metrics", new org.springframework.core.io.FileSystemResource(cloud));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> {
                    for (var source : properties) context.getEnvironment().getPropertySources().addLast(source);
                })
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration.class))
                .withBean(McpToolMetrics.class).withBean(McpClientRegistry.class).withBean(ObserveController.class)
                .run(context -> {
                    assertTrue("Metrics context failed", context.getStartupFailure() == null);
                    var registry = context.getBean(McpClientRegistry.class);
                    registry.register("cloud-probe", AiClientToolMcpVO.builder().mcpId("cloud-probe")
                            .mcpName("Cloud fixture").build(), mock(McpSyncClient.class), config -> null);
                    registry.recordSuccess("cloud-probe");
                    context.getBean(McpToolMetrics.class).recordCall("cloud_tool", 12, true);
                    var prometheus = context.getBean(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
                    String scrape = prometheus.scrape();
                    assertTrue(scrape.contains("mcp_tool_call_seconds_count{"));
                    assertTrue(scrape.contains("mcp_client_consecutive_failures{"));
                    assertTrue(scrape.contains("application=\"ai-agent-station-study\""));
                    var mvc = MockMvcBuilders.standaloneSetup(context.getBean(ObserveController.class)).build();
                    mvc.perform(get("/api/v1/observe/mcp-client-health").param("scope", "all").accept(MediaType.APPLICATION_JSON))
                            .andExpect(jsonPath("$.data.summary.totalClients").value(1));
                    mvc.perform(get("/api/v1/observe/mcp-tools-status").param("scope", "all").accept(MediaType.APPLICATION_JSON))
                            .andExpect(jsonPath("$.data.summary.totalCalls").value(1));
                });
    }

    @Test
    public void registeredAndCalledToolAppearsInBothPanelEndpoints() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SimpleMeterRegistry.class);
            context.register(McpClientRegistry.class, McpToolMetrics.class, ObserveController.class);
            context.refresh();
            var registry = context.getBean(McpClientRegistry.class);
            var metrics = context.getBean(McpToolMetrics.class);
            ToolCallback tool = new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("probe_tool").description("Local fixture")
                            .inputSchema("{\"type\":\"object\"}").build();
                }
                public String call(String input) { return "ok"; }
            };
            registry.register("probe-mcp", AiClientToolMcpVO.builder().mcpId("probe-mcp")
                    .mcpName("Local fixture").build(), mock(McpSyncClient.class), config -> null);
            registry.registerCallbacks("probe-mcp", new ToolCallback[]{tool});
            registry.recordSuccess("probe-mcp");
            assertEquals("ok", new MeteredToolCallback(tool, metrics).call("{}"));

            MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean(ObserveController.class)).build();
            mvc.perform(get("/api/v1/observe/mcp-client-health").param("scope", "all").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summary.totalClients").value(1))
                    .andExpect(jsonPath("$.data.summary.totalRegisteredTools").value(1))
                    .andExpect(jsonPath("$.data.clients[0].mcpId").value("probe-mcp"));
            mvc.perform(get("/api/v1/observe/mcp-tools-status").param("scope", "all").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summary.totalCalls").value(1))
                    .andExpect(jsonPath("$.data.tools[0].tool").value("probe_tool"))
                    .andExpect(jsonPath("$.data.tools[0].mcpId").doesNotExist());
        }
    }
}
