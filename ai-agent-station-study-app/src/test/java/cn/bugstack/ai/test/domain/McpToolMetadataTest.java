package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolCallMetadata;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class McpToolMetadataTest {
    @Test public void oneDiscoveryRetainsConnectionIdentityAndRetryAnnotationsThroughHintWrapper() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("test-client", "1"));
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("test-server", "1"));
        McpSchema.Tool tool = McpSchema.Tool.builder().name("lookup").description("lookup")
                .inputSchema(new McpSchema.JsonSchema("object", java.util.Map.of(), List.of(), false, null, null))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, null, true, false)).build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        ToolCallback[] callbacks = new McpClientRegistry().getToolCallbacksForAssembly("alice-mcp", client);
        assertEquals(1, callbacks.length);
        HintedToolCallback decorated = new HintedToolCallback(callbacks[0], "additional prompt hint");
        assertTrue(decorated.getToolMetadata() instanceof McpToolCallMetadata);
        McpToolCallMetadata metadata = (McpToolCallMetadata) decorated.getToolMetadata();
        assertEquals("alice-mcp", metadata.mcpId());
        assertTrue(metadata.retrySafe());
        verify(client, times(1)).listTools();
    }

    @Test public void missingAnnotationsAreConservative() {
        assertFalse(McpToolCallMetadata.from("mcp", null, false).retrySafe());
        assertFalse(new McpToolCallMetadata("mcp", false, false, null, false).retrySafe());
        assertFalse(new McpToolCallMetadata("mcp", false, null, true, false).retrySafe());
    }
}
