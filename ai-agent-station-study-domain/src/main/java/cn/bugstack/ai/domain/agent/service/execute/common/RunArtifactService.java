package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.Map;

/** Built-in output persistence. Identity and invocation ID are supplied by the runtime, never arguments. */
public interface RunArtifactService {
    String TOOL_NAME = "create_artifact";
    ToolDefinition definition();
    String create(String toolCallId, String arguments, Map<String, Object> trustedContext);
}
