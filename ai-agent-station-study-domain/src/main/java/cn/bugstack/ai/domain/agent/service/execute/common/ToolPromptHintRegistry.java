package cn.bugstack.ai.domain.agent.service.execute.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * T10：工具调用提示（prompt hint）注册表。
 * <p>
 * <b>目的</b>：LLM 在调 MCP 工具时经常因为工具描述（MCP server 给的 description）不够具体而踩坑，
 * 比如不知道 GitHub search_repositories 的 per_page 上限、不知道 calculate 的 precision 字段
 * 是 number 而非字符串。这里把"踩坑提醒"以 hint 形式追加到工具 description 后，让 LLM 在
 * function calling schema 阶段就看到，降低工具调用失败率。
 * <p>
 * <b>来源优先级</b>：
 * <ol>
 *   <li>application.yml 的 {@code agent.mcp.tool-hint.<toolName>: "..."} 配置（运维可改）</li>
 *   <li>{@link #initDefaults()} 内置的已知踩坑工具默认值（兜底）</li>
 * </ol>
 * yml 配置优先于默认值（{@code putIfAbsent} 不会覆盖运维设置）。
 * <p>
 * <b>匹配策略</b>：MCP 工具名可能带 Spring AI 前缀（如 {@code spring_ai_mcp_client_github_search_repositories}），
 * 所以先做完整匹配，找不到则按 suffix 匹配。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "agent.mcp")
public class ToolPromptHintRegistry {

    /**
     * toolName → hint 文本。可由 yml {@code agent.mcp.tool-hint.<toolName>: "..."} 注入。
     * key 用 toolName 短名（如 {@code search_repositories}），匹配时支持 suffix。
     */
    private Map<String, String> toolHint = new HashMap<>();

    @PostConstruct
    public void initDefaults() {
        // 已知踩坑工具默认 hint。yml 已配置同 key 时不会覆盖。
        // 实际踩坑总结来自 MeteredToolCallback.normalize* 已经在做的修正——hint 让 LLM 提前避坑，
        // 避免 normalize 兜底吃成本。
        toolHint.putIfAbsent("search_repositories",
                "GitHub 仓库搜索请使用 per_page <= 10（服务端会硬限制更大的值）。参数名优先使用驼峰 perPage，不要写 snake_case per_page。"
                        + "从 page=1 开始；不要把 page>5 和较大的 perPage 组合使用，否则后续结果可能被截断。");
        toolHint.putIfAbsent("search_code",
                "代码搜索请使用 per_page <= 10，并提供精确 query；过宽泛的 query 容易触发 GitHub 限流（未认证约 60 次/小时）。");
        toolHint.putIfAbsent("search_issues",
                "Issue/PR 搜索请使用 per_page <= 10，并结合 `is:issue` 或 `is:pr`、`state:open|closed` 缩小结果范围。");
        toolHint.putIfAbsent("calculate",
                "`precision` 字段只接受数字（整数或小数）或空字符串 \"\"，不要传 \"high\"、\"default\" 这类非数字字符串；不需要精度控制时请省略该字段。");
        toolHint.putIfAbsent("aisearch",
                "不要传 `model`、`instruction`、`temperature`，这些是服务端 LLM 控制参数，会被剥离；这里只需要提供用户查询内容。");
        log.info("[ToolPromptHintRegistry] initialized with {} hints (yml-config + built-in defaults)", toolHint.size());
    }

    /**
     * 给定工具名查 hint。先完整匹配，未命中则按 suffix（去 MCP 前缀）匹配。
     */
    public String getHint(String toolName) {
        if (toolName == null || toolName.isBlank() || toolHint.isEmpty()) return null;
        String exact = toolHint.get(toolName);
        if (exact != null && !exact.isBlank()) return exact;
        String lower = toolName.toLowerCase();
        String bestHint = null;
        int bestKeyLength = -1;
        for (Map.Entry<String, String> e : toolHint.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            if (lower.endsWith(key.toLowerCase()) && e.getValue() != null && !e.getValue().isBlank()) {
                int keyLength = key.length();
                if (keyLength > bestKeyLength) {
                    bestKeyLength = keyLength;
                    bestHint = e.getValue();
                }
            }
        }
        return bestHint;
    }

    // ConfigurationProperties 需要 setter
    public Map<String, String> getToolHint() {
        return toolHint;
    }

    public void setToolHint(Map<String, String> toolHint) {
        this.toolHint = toolHint == null ? new HashMap<>() : toolHint;
    }
}
