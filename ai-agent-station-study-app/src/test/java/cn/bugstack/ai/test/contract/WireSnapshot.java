package cn.bugstack.ai.test.contract;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Immutable, sanitized view of the request that reached the ChatModel boundary. */
public final class WireSnapshot {

    public record MessageView(String role, String text, String sha256) {}
    public record ToolView(String name, String descriptionSha256, String schemaSha256) {}

    private final String stableId;
    private final List<MessageView> messages;
    private final String model;
    private final Integer maxTokens;
    private final List<ToolView> tools;
    private final Boolean internalToolExecutionEnabled;
    private final Map<String, Object> toolContext;

    private WireSnapshot(String stableId, List<MessageView> messages, String model,
                         Integer maxTokens, List<ToolView> tools,
                         Boolean internalToolExecutionEnabled, Map<String, Object> toolContext) {
        this.stableId = stableId;
        this.messages = List.copyOf(messages);
        this.model = model;
        this.maxTokens = maxTokens;
        this.tools = List.copyOf(tools);
        this.internalToolExecutionEnabled = internalToolExecutionEnabled;
        this.toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
    }

    public static WireSnapshot from(String stableId, Prompt prompt) {
        List<MessageView> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            String text = normalize(message.getText());
            messages.add(new MessageView(message.getMessageType().name(), text, sha256(text)));
        }

        ChatOptions options = prompt.getOptions();
        List<ToolView> tools = new ArrayList<>();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback callback : callbacks) {
                    var definition = callback.getToolDefinition();
                    tools.add(new ToolView(definition.name(), sha256(definition.description()),
                            sha256(definition.inputSchema())));
                }
            }
        }
        tools.sort(java.util.Comparator.comparing(ToolView::name));
        Boolean internalToolExecutionEnabled = null;
        Map<String, Object> toolContext = Map.of();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            internalToolExecutionEnabled = toolOptions.getInternalToolExecutionEnabled();
            toolContext = toolOptions.getToolContext();
        }
        return new WireSnapshot(stableId, messages,
                options == null ? null : options.getModel(),
                options == null ? null : options.getMaxTokens(), tools,
                internalToolExecutionEnabled, toolContext);
    }

    public String stableId() { return stableId; }
    public List<MessageView> messages() { return Collections.unmodifiableList(messages); }
    public String model() { return model; }
    public Integer maxTokens() { return maxTokens; }
    public List<ToolView> tools() { return Collections.unmodifiableList(tools); }
    public Boolean internalToolExecutionEnabled() { return internalToolExecutionEnabled; }
    public Map<String, Object> toolContext() { return toolContext; }

    public List<String> roles() {
        return messages.stream().map(MessageView::role).toList();
    }

    public List<String> toolNames() {
        return tools.stream().map(ToolView::name).toList();
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ").trim();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
