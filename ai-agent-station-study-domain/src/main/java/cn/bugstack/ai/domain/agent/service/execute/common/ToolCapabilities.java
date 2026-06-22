package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.Map;

/** 请求级 ToolContext 编解码的唯一入口。 */
public final class ToolCapabilities {

    public static final String TOOL_CONTEXT_KEY = "agent.tool_capabilities";

    private ToolCapabilities() {
    }

    public enum State { EXPLICIT, MISSING, INVALID }

    public record Parsed(State state, ToolCapabilityProfile profile, Object rawValue) {
        public boolean isExplicit() {
            return state == State.EXPLICIT;
        }
    }

    /**
     * 只接受应用侧 enum 或 profile 名字符串；缺失与非法严格区分，非法值由调用方 fail-closed。
     */
    public static Parsed parse(Map<String, Object> toolContext) {
        if (toolContext == null || !toolContext.containsKey(TOOL_CONTEXT_KEY)) {
            return new Parsed(State.MISSING, null, null);
        }
        Object raw = toolContext.get(TOOL_CONTEXT_KEY);
        if (raw instanceof ToolCapabilityProfile profile) {
            return new Parsed(State.EXPLICIT, profile, raw);
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return new Parsed(State.EXPLICIT,
                        ToolCapabilityProfile.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT)), raw);
            } catch (IllegalArgumentException ignored) {
                // fall through to INVALID
            }
        }
        return new Parsed(State.INVALID, ToolCapabilityProfile.NONE, raw);
    }

    /** ToolContext 内使用稳定字符串，避免把可变对象或 manager 级状态带入共享模型。 */
    public static void put(Map<String, Object> toolContext, ToolCapabilityProfile profile) {
        if (toolContext != null && profile != null) {
            toolContext.put(TOOL_CONTEXT_KEY, profile.name());
        }
    }
}
