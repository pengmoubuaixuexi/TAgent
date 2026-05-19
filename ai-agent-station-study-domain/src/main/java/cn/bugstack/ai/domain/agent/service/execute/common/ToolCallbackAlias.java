package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 工具名别名 wrapper：把同一个 ToolCallback 用另一个 name 暴露给 LLM。
 * <p>
 * 解决 LLM 工具名幻觉问题：模型常把 {@code JavaSDKMCPClient_AIsearch} 写成
 * {@code JavaSDKMCPClient_AISEARCH}（大写化），Spring AI 的 {@code DefaultToolCallingManager}
 * 用 toolName.equals(...) 精确匹配 → 找不到 → 抛 IllegalStateException → Flux 链炸 →
 * 用户看到 "Stream processing failed" ERROR。
 * <p>
 * 装配时给每个原工具补 2 个 alias（全大写 + 全小写），LLM 不管怎么大小写化都能命中。
 * <p>
 * 拼写错误（AIsearch → AIsite）覆盖不了，那是 LLM 幻觉边界，需要更强的 fuzzy 匹配
 * 或者把 "tool not found" 喂回 LLM 让它自纠（更大改造，暂不做）。
 */
public class ToolCallbackAlias implements ToolCallback {

    private final ToolCallback delegate;
    private final String aliasName;
    private final ToolDefinition aliasDefinition;

    public ToolCallbackAlias(ToolCallback delegate, String aliasName) {
        this.delegate = delegate;
        this.aliasName = aliasName;
        ToolDefinition orig = delegate.getToolDefinition();
        // 用原 definition 的 description / inputSchema，仅替换 name
        this.aliasDefinition = ToolDefinition.builder()
                .name(aliasName)
                .description(orig == null ? "" : orig.description())
                .inputSchema(orig == null ? "{}" : orig.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return aliasDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    public String getAliasName() {
        return aliasName;
    }
}
