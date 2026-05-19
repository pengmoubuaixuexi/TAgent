package cn.bugstack.ai.domain.agent.service.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 工具注册表（2026-05-07）。
 * <p>
 * 配套：{@link ModelTierRegistry} 是 clientId → tier 的反查；本表是 clientId → 实际工具列表的反查。
 * <p>
 * <b>为什么需要</b>：
 * Spring AI 装配时通过 {@code ChatClient.builder().defaultToolCallbacks(tools)} 把工具回调注入到 ChatClient 内部，
 * 这只让"LLM 输出 tool_use 时能找到 callback 真执行"——但 prompt 构造代码（Step1/Step4）<b>看不到</b>这份工具列表。
 * 没有这份信息，prompt 只能写"基于实际工具规划"却无数据可塞，LLM 只好脑补"web_search"等不存在的工具，
 * 导致演戏式调用 + 编造结果。
 * <p>
 * <b>使用方式</b>：
 * <ul>
 *   <li>装配阶段（{@code AiClientNode}）：调 {@link #register(String, ToolCallback[])} 把 clientId 对应的工具元信息存下来</li>
 *   <li>运行时（{@code Step1McpToolsAnalysisNode} / {@code Step4ExecuteStepsNode}）：调
 *       {@link #describeToolsForPrompt(String)} 拿到可直接拼进 prompt 的工具说明文本</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentToolRegistry {

    /** clientId → 这个 client 实际注册的工具元信息 */
    private final Map<String, List<ToolInfo>> clientTools = new ConcurrentHashMap<>();

    /**
     * 装配阶段调用：把 ChatClient 的工具列表登记进来。
     * 重复注册同 clientId 时覆盖（支持 armory 重新装配场景）。
     */
    public void register(String clientId, ToolCallback[] callbacks) {
        if (clientId == null || clientId.isBlank()) return;
        if (callbacks == null || callbacks.length == 0) {
            clientTools.put(clientId, Collections.emptyList());
            log.info("[ToolRegistry] register clientId={} tools=[] (no MCP tools)", clientId);
            return;
        }
        List<ToolInfo> list = new ArrayList<>(callbacks.length);
        for (ToolCallback cb : callbacks) {
            if (cb.getToolDefinition() == null) continue;
            String name = cb.getToolDefinition().name();
            String desc = cb.getToolDefinition().description();
            list.add(new ToolInfo(name == null ? "" : name, desc == null ? "" : desc));
        }
        clientTools.put(clientId, Collections.unmodifiableList(list));
        log.info("[ToolRegistry] register clientId={} tools={}", clientId,
                list.stream().map(ToolInfo::name).collect(Collectors.toList()));
    }

    public List<ToolInfo> getTools(String clientId) {
        if (clientId == null) return Collections.emptyList();
        return clientTools.getOrDefault(clientId, Collections.emptyList());
    }

    public boolean hasAnyTools(String clientId) {
        return !getTools(clientId).isEmpty();
    }

    /**
     * 生成可直接拼进 LLM prompt 的工具说明 Markdown 文本。
     * <ul>
     *   <li>有工具：列出每个工具的 name + description，并提示"只能引用这些"</li>
     *   <li>无工具：明确告知"无工具可用，仅基于知识回答"</li>
     * </ul>
     */
    public String describeToolsForPrompt(String clientId) {
        List<ToolInfo> tools = getTools(clientId);
        if (tools.isEmpty()) {
            return "**当前 Agent (clientId=" + clientId + ") 没有配置任何 MCP 工具**。\n"
                    + "你**没有任何外部工具可调用**，请仅基于 LLM 自身知识回答用户问题；"
                    + "**禁止**虚构工具调用过程或返回数据。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**当前 Agent (clientId=").append(clientId).append(") 实际可用的 MCP 工具列表（只能引用以下工具，禁止编造其他工具名）:**\n");
        for (ToolInfo t : tools) {
            sb.append("- `").append(t.name()).append("`: ").append(t.description()).append("\n");
        }
        if (hasGithubRepositorySearchTool(tools)) {
            sb.append("\n**GitHub repository search rules**\n");
            sb.append("- Prefer English technical GitHub queries; do not pass broad Chinese natural-language phrases directly as the query.\n");
            sb.append("- Translate Chinese intent into precise GitHub search syntax, for example: `spring-boot learning language:Java stars:>500`, `spring-boot examples language:Java stars:>500`, `spring-boot tutorial language:Java stars:>500`.\n");
            sb.append("- Avoid generic Chinese poisoned keywords such as `中文`, `教程`, `资料` unless the user explicitly asks to search only Chinese repositories.\n");
            sb.append("- Request only the first page and a small result set; `page=1` and `perPage<=10` are enough for recommendations.\n");
        }
        return sb.toString();
    }

    private boolean hasGithubRepositorySearchTool(List<ToolInfo> tools) {
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        for (ToolInfo tool : tools) {
            if (tool != null && tool.name() != null
                    && tool.name().toLowerCase().endsWith("search_repositories")) {
                return true;
            }
        }
        return false;
    }

    public int totalRegisteredClients() {
        return clientTools.size();
    }

    /** 工具元信息（轻量，不含 callback 本体） */
    public record ToolInfo(String name, String description) {}
}
