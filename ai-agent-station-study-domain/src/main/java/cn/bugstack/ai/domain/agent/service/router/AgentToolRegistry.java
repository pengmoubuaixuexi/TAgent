package cn.bugstack.ai.domain.agent.service.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of tools that are actually mounted into each ChatClient.
 *
 * <p>Dynamic tool matching is intentionally handled by {@link McpToolCatalogService}
 * against the persistent MCP tool catalog table. This registry only describes
 * the current request-visible tools for prompts.
 */
@Slf4j
@Component
public class AgentToolRegistry {

    private final Map<String, List<ToolInfo>> clientTools = new ConcurrentHashMap<>();

    /** 装配阶段登记每个 client 的常驻工具回调本体，供 per-request 动态补工具时与之合并（见 {@link #combineWithResident}）。 */
    private final Map<String, List<ToolCallback>> clientCallbacks = new ConcurrentHashMap<>();

    @Value("${agent.mcp.tool-call.max-serial-rounds-per-client:3}")
    private int maxSerialToolRoundsPerClient;

    @Value("${agent.mcp.tool-call.parallel-enabled:true}")
    private boolean toolCallParallelEnabled;

    public void register(String clientId, ToolCallback[] callbacks) {
        if (clientId == null || clientId.isBlank()) return;
        if (callbacks == null || callbacks.length == 0) {
            clientTools.put(clientId, Collections.emptyList());
            clientCallbacks.put(clientId, Collections.emptyList());
            log.info("[ToolRegistry] register clientId={} tools=[] (no MCP tools)", clientId);
            return;
        }

        List<ToolInfo> list = new ArrayList<>(callbacks.length);
        List<ToolCallback> callbackList = new ArrayList<>(callbacks.length);
        Set<String> seen = new LinkedHashSet<>();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) continue;
            String name = safe(callback.getToolDefinition().name());
            if (name.isBlank() || !seen.add(name)) continue;
            String rawDescription = safe(callback.getToolDefinition().description());
            list.add(new ToolInfo(name, rawDescription));
            callbackList.add(callback);
        }
        clientTools.put(clientId, Collections.unmodifiableList(list));
        clientCallbacks.put(clientId, Collections.unmodifiableList(callbackList));
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

    /** 该 client 的常驻工具回调本体（装配阶段登记）。 */
    public List<ToolCallback> getToolCallbacks(String clientId) {
        if (clientId == null) return Collections.emptyList();
        return clientCallbacks.getOrDefault(clientId, Collections.emptyList());
    }

    /**
     * 把"路由动态补充的工具回调"与该 client 的常驻工具回调合并（常驻在前，按工具名去重）。
     *
     * <p>用于 per-request 的 {@code spec.options(OpenAiChatOptions.toolCallbacks(...))}：
     * Spring AI 的 {@code ToolCallingChatOptions.mergeToolCallbacks} 在 runtime 列表非空时会
     * <b>整体替换</b> default(常驻)工具而非取并集，所以 per-request 列表必须自带常驻工具，
     * 否则一旦补了动态工具，agent 的常驻工具会全部丢失。
     */
    public List<ToolCallback> combineWithResident(String clientId, List<ToolCallback> extra) {
        List<ToolCallback> resident = getToolCallbacks(clientId);
        if (extra == null || extra.isEmpty()) {
            return resident.isEmpty() ? Collections.emptyList() : new ArrayList<>(resident);
        }
        LinkedHashMap<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback cb : resident) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            String name = safe(cb.getToolDefinition().name());
            if (!name.isBlank()) byName.putIfAbsent(name, cb);
        }
        for (ToolCallback cb : extra) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            String name = safe(cb.getToolDefinition().name());
            if (!name.isBlank()) byName.putIfAbsent(name, cb);
        }
        return new ArrayList<>(byName.values());
    }

    public int totalRegisteredClients() {
        return clientTools.size();
    }

    public String describeToolsForPrompt(String clientId) {
        return describeToolsForPrompt(clientId, Collections.emptyList());
    }

    public String describeToolsForPrompt(String clientId, List<ToolCallback> extraCallbacks) {
        List<ToolInfo> baseTools = getTools(clientId);
        List<ToolInfo> extraTools = toToolInfo(extraCallbacks, baseTools);
        List<ToolInfo> allTools = new ArrayList<>(baseTools);
        allTools.addAll(extraTools);

        if (allTools.isEmpty()) {
            return "**当前 Agent (clientId=" + clientId + ") 没有配置任何 MCP 工具**。\n"
                    + "你没有任何外部工具可调用，请仅基于 LLM 自身知识回答用户问题，禁止虚构工具调用过程或返回数据。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**当前 Agent (clientId=").append(clientId)
                .append(") 实际可用的 MCP 工具列表（只能引用以下工具，禁止编造其他工具名）**\n");
        appendToolLines(sb, baseTools);

        if (!extraTools.isEmpty()) {
            sb.append("\n**本次路由动态补充的工具（仅本次请求临时可用）**\n");
            appendToolLines(sb, extraTools);
        }

        if (hasGithubRepositorySearchTool(allTools)) {
            sb.append("\n**GitHub 仓库搜索规则**\n");
            sb.append("- GitHub 搜索请优先使用英文技术关键词，不要把宽泛的中文自然语言问题直接作为 query。\n");
            sb.append("- 请把中文意图转换成精确的 GitHub 搜索语法，例如：`spring-boot learning language:Java stars:>500`、`spring-boot examples language:Java stars:>500`。\n");
            sb.append("- 除非用户明确要求只搜中文仓库，否则避免使用 `中文`、`教程`、`资料` 这类泛化关键词。\n");
            sb.append("- 只请求第一页和较小结果集即可；推荐 `page=1` 且 `perPage<=10`。\n");
        }

        if (maxSerialToolRoundsPerClient > 0) {
            sb.append("\n**工具调用轮次预算**\n");
            sb.append("- 本次 client 执行最多只能进行 ").append(maxSerialToolRoundsPerClient).append(" 轮工具调用。\n");
            sb.append("- 一轮指 assistant 一次性发起工具调用；同一轮里即使包含多个工具调用，也只算 1 轮。\n");
            sb.append("- 如果工具轮次已经用完，请停止继续调用工具，基于已有信息给出最佳答案；缺失的数据要明确列出，并给出保守兜底方案。\n");
        }
        if (maxSerialToolRoundsPerClient > 0 && toolCallParallelEnabled) {
            sb.append("\n**并行工具调用建议**\n");
            sb.append("- 如果多个工具调用互不依赖，请尽量在同一轮一次性发起，这样可以并行执行，并且只消耗 1 轮工具预算。\n");
        }
        return sb.toString();
    }

    private List<ToolInfo> toToolInfo(List<ToolCallback> callbacks, List<ToolInfo> baseTools) {
        if (callbacks == null || callbacks.isEmpty()) return Collections.emptyList();
        Set<String> seen = baseTools.stream().map(ToolInfo::name).collect(Collectors.toCollection(LinkedHashSet::new));
        List<ToolInfo> result = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) continue;
            String name = safe(callback.getToolDefinition().name());
            if (name.isBlank() || seen.contains(name)) continue;
            seen.add(name);
            String rawDescription = safe(callback.getToolDefinition().description());
            result.add(new ToolInfo(name, rawDescription));
        }
        return result;
    }

    private void appendToolLines(StringBuilder sb, List<ToolInfo> tools) {
        for (ToolInfo tool : tools) {
            sb.append("- `").append(tool.name()).append("`: ").append(tool.description()).append("\n");
        }
    }

    private boolean hasGithubRepositorySearchTool(List<ToolInfo> tools) {
        if (tools == null || tools.isEmpty()) return false;
        for (ToolInfo tool : tools) {
            if (tool != null && tool.name() != null
                    && tool.name().toLowerCase(Locale.ROOT).endsWith("search_repositories")) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ToolInfo(String name, String description) {}
}
