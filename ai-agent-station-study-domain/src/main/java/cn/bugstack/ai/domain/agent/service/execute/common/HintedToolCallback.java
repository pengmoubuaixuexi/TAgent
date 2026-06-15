package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * T10：在原 {@link ToolCallback} 之外包一层，把 {@link ToolPromptHintRegistry} 提供的
 * hint 文本追加到 {@link ToolDefinition#description()} 后面。
 * <p>
 * <b>放在装配链的哪一层</b>：在 raw MCP ToolCallback 外层、{@link MeteredToolCallback} 内层。
 * 这样 LLM 看到的 function calling schema description 含 hint；MeteredToolCallback 的 metric / retry / normalize
 * 不受影响（它们都通过 {@code delegate.getToolDefinition().name()} 取名字，name 没改）。
 * <p>
 * 当 hint 为空时，{@link #getToolDefinition()} 直接返回原 ToolDefinition——零开销 wrap。
 */
public class HintedToolCallback implements ToolCallback {

    private static final String HINT_PREFIX = "\n\n提示: ";

    private final ToolCallback delegate;
    private final String hint;
    /** 缓存合成后的 ToolDefinition，避免每次 LLM 拉 schema 都重建 */
    private final ToolDefinition decoratedDefinition;

    public HintedToolCallback(ToolCallback delegate, String hint) {
        this.delegate = delegate;
        this.hint = hint;
        this.decoratedDefinition = (hint == null || hint.isBlank()) ? null : buildDecorated();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return decoratedDefinition != null ? decoratedDefinition : delegate.getToolDefinition();
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

    private ToolDefinition buildDecorated() {
        ToolDefinition orig = delegate.getToolDefinition();
        if (orig == null) return null;
        String origDesc = orig.description();
        String composed = (origDesc == null || origDesc.isBlank()) ? hint : origDesc + HINT_PREFIX + hint;
        // 直接 new ToolDefinition 匿名实现，避免依赖具体 DefaultToolDefinition 类型（Spring AI 内部可能变）
        return new ToolDefinition() {
            @Override public String name() { return orig.name(); }
            @Override public String description() { return composed; }
            @Override public String inputSchema() { return orig.inputSchema(); }
        };
    }
}
