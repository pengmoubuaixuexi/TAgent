package cn.bugstack.ai.domain.agent.service.execute.common;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Runtime identity and retry hints retained across MCP callback decorators. */
public record McpToolCallMetadata(String mcpId, Boolean readOnlyHint, Boolean destructiveHint,
                                  Boolean idempotentHint, boolean returnDirect) implements ToolMetadata {

    public static McpToolCallMetadata from(String mcpId, McpSchema.ToolAnnotations annotations,
                                           boolean returnDirect) {
        return new McpToolCallMetadata(mcpId,
                annotations == null ? null : annotations.readOnlyHint(),
                annotations == null ? null : annotations.destructiveHint(),
                annotations == null ? null : annotations.idempotentHint(), returnDirect);
    }

    /** Unknown tools are not inferred to be read-only from their names. */
    public boolean retrySafe() {
        if (Boolean.TRUE.equals(destructiveHint)) return false;
        if (Boolean.TRUE.equals(readOnlyHint)) return true;
        // MCP destructiveHint defaults to true for write tools. Omission is not a safety declaration.
        return Boolean.FALSE.equals(destructiveHint) && Boolean.TRUE.equals(idempotentHint);
    }
}
