package cn.bugstack.ai.domain.agent.service.memory;

/**
 * ChatMemory 跨层传递的上下文（ThreadLocal）。
 * <p>
 * domain 层的 ExecuteStrategy 设置 agentId，infrastructure 层的 ChatMemoryRepository 读取并写入 DB。
 * 避免 domain → infrastructure 的反向依赖。
 */
public final class ChatMemoryContext {

    private static final ThreadLocal<String> AGENT_ID = new ThreadLocal<>();

    private ChatMemoryContext() {}

    public static void setAgentId(String agentId) {
        AGENT_ID.set(agentId);
    }

    public static String getAgentId() {
        return AGENT_ID.get();
    }

    public static void clear() {
        AGENT_ID.remove();
    }
}
