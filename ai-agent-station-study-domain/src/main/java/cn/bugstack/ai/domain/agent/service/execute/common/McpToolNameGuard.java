package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Validate one Agent/client tool set, never the global connection registry. */
public final class McpToolNameGuard {
    private McpToolNameGuard() {}

    public record Binding(String mcpId, String toolName) {}

    public static void requireUnique(Collection<Binding> bindings) {
        Map<String, String> owners = new HashMap<>();
        for (Binding binding : bindings) {
            if (binding.toolName() == null || binding.toolName().isBlank()) continue;
            String previous = owners.putIfAbsent(binding.toolName().toLowerCase(Locale.ROOT), binding.mcpId());
            if (previous != null && !previous.equals(binding.mcpId())) {
                throw new CollisionException("所选 MCP 工具名称冲突：" + binding.toolName()
                        + "（连接 " + previous + "、" + binding.mcpId() + "），请在 Agent 配置中取消其中一个连接");
            }
        }
    }

    public static final class CollisionException extends IllegalArgumentException {
        private CollisionException(String message) { super(message); }
    }
}
