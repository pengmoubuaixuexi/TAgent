package cn.bugstack.ai.domain.agent.service.execute.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 健壮版 {@link ToolCallingManager}：解决 LLM 工具名大小写幻觉问题。
 * <p>
 * <b>问题背景</b>：LLM 调用工具时常把名字大小写化（{@code AIsearch → AISEARCH}）或全小写化，
 * Spring AI 默认的 {@code DefaultToolCallingManager} 用 {@code equals(...)} 精确匹配 →
 * 找不到 → 抛 {@code IllegalStateException("No ToolCallback found for tool name: X")} →
 * Flux 链炸 → 用户看到 "Stream processing failed"。
 * <p>
 * <b>解决方案</b>：在调用 delegate 之前，把 {@link ChatResponse} 里 {@code AssistantMessage.toolCalls}
 * 的 name 字段做一次 case-insensitive 匹配——查 prompt 自带的 toolCallbacks 列表，找到大小写不敏感
 * 匹配的真实 name → 替换。delegate 收到的就是已 normalize 的 ChatResponse，原样精确匹配能通过。
 * <p>
 * 优于 alias 方案（每工具补 UPPER 别名翻倍 prompt 工具区）：
 * <ul>
 *   <li>0 prompt token 增长：LLM 仍然只看到原始工具描述</li>
 *   <li>覆盖全部大小写变体（不只是大写化，还有 mixed/lower）</li>
 *   <li>不影响 metric / observation（delegate 仍是 DefaultToolCallingManager，链路一致）</li>
 * </ul>
 * <p>
 * 解决不了的：拼写错误（{@code AIsearch → AIsite}），那是更深的 LLM 幻觉，需要 fuzzy 匹配
 * 或者把 "tool not found" 喂回 LLM 让它自纠（更大改造，暂不做）。
 */
@Slf4j
public class RobustToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    public RobustToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        if (prompt == null || chatResponse == null) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        // 拿 prompt 自带的真实工具名（注入到 ChatClient.defaultToolCallbacks 的那一组）
        Map<String, String> caseMap = buildLowerToOriginalNameMap(prompt);
        if (caseMap.isEmpty()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        // 重建 ChatResponse，把 ToolCall.name 按 case-insensitive 校正
        ChatResponse normalized = normalizeToolCallNames(chatResponse, caseMap);

        // 校正后再次检查：是否还有找不到的工具名（拼写错误 / 幻觉工具）
        Set<String> unknownNames = findUnknownToolNames(normalized, caseMap);
        if (!unknownNames.isEmpty()) {
            log.warn("[RobustToolMgr] unknown tools after normalization: {}, returning error with available tools", unknownNames);
            return buildUnknownToolErrorResult(prompt, normalized, unknownNames, caseMap);
        }

        return delegate.executeToolCalls(prompt, normalized);
    }

    /**
     * 从 prompt options 拿真实工具名列表，建立 lowerCase -> originalCase 映射。
     * 拿不到（options 不是 ToolCallingChatOptions / 工具空）时返回空 map，调用方退化到 delegate 原行为。
     */
    /**
     * 校正后检查：哪些 ToolCall.name 在 caseMap（真实工具列表）里仍然找不到。
     * 拼写错误 / 幻觉工具会残留。
     */
    private Set<String> findUnknownToolNames(ChatResponse chatResponse, Map<String, String> caseMap) {
        Set<String> unknown = new HashSet<>();
        Set<String> realNames = new HashSet<>(caseMap.values());
        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() == null || !gen.getOutput().hasToolCalls()) continue;
            for (AssistantMessage.ToolCall tc : gen.getOutput().getToolCalls()) {
                if (tc.name() != null && !realNames.contains(tc.name())) {
                    unknown.add(tc.name());
                }
            }
        }
        return unknown;
    }

    /**
     * 工具名不存在时，构建 ToolExecutionResult 把错误 + 可用工具清单返回给 LLM，让它自纠。
     */
    private ToolExecutionResult buildUnknownToolErrorResult(
            Prompt prompt, ChatResponse chatResponse, Set<String> unknownNames, Map<String, String> caseMap) {

        // 拼可用工具列表
        StringBuilder toolList = new StringBuilder();
        for (Map.Entry<String, String> e : caseMap.entrySet()) {
            toolList.append("- ").append(e.getValue()).append("\n");
        }
        String errorData = "Error: Tool(s) not found: " + unknownNames
                + ". You must ONLY use tools from the registered list below. Do NOT fabricate tool names.\n"
                + "Available tools:\n" + toolList;

        // 取 AssistantMessage（含 toolCalls）用于构建 conversationHistory
        AssistantMessage assistantMessage = null;
        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() != null && gen.getOutput().hasToolCalls()) {
                assistantMessage = gen.getOutput();
                break;
            }
        }

        // 为每个未知工具构建 error ToolResponse
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (assistantMessage != null) {
            for (AssistantMessage.ToolCall tc : assistantMessage.getToolCalls()) {
                String responseData = unknownNames.contains(tc.name()) ? errorData : "";
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), responseData));
            }
        }

        // 构建 conversationHistory：prompt 原始消息 + assistant + toolResponse
        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        if (assistantMessage != null) {
            conversationHistory.add(assistantMessage);
        }
        conversationHistory.add(new ToolResponseMessage(responses));

        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(false)
                .build();
    }

    private Map<String, String> buildLowerToOriginalNameMap(Prompt prompt) {
        Map<String, String> map = new HashMap<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions tco) {
            List<ToolCallback> callbacks = tco.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback cb : callbacks) {
                    if (cb == null || cb.getToolDefinition() == null) continue;
                    String name = cb.getToolDefinition().name();
                    if (name == null || name.isBlank()) continue;
                    // 同名 lower 撞车（理论不会，工具名唯一）保留先注册的
                    map.putIfAbsent(name.toLowerCase(), name);
                }
            }
        }
        return map;
    }

    /**
     * 重建 ChatResponse，对每个 Generation 内 AssistantMessage 的 toolCalls 做 name 校正。
     * 完全不变更 text/metadata/media，只替换 ToolCall.name。
     */
    private ChatResponse normalizeToolCallNames(ChatResponse original, Map<String, String> caseMap) {
        List<Generation> origGenerations = original.getResults();
        if (origGenerations == null || origGenerations.isEmpty()) return original;

        List<Generation> rebuilt = new ArrayList<>(origGenerations.size());
        boolean anyChanged = false;
        for (Generation gen : origGenerations) {
            AssistantMessage am = gen.getOutput();
            if (am == null || !am.hasToolCalls()) {
                rebuilt.add(gen);
                continue;
            }
            List<AssistantMessage.ToolCall> normCalls = new ArrayList<>(am.getToolCalls().size());
            boolean genChanged = false;
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                String origName = tc.name();
                if (origName == null || caseMap.containsValue(origName)) {
                    // 已经是真实 name，原样保留
                    normCalls.add(tc);
                    continue;
                }
                String mapped = caseMap.get(origName.toLowerCase());
                if (mapped != null) {
                    log.info("[RobustToolMgr] normalize tool name '{}' -> '{}'", origName, mapped);
                    normCalls.add(new AssistantMessage.ToolCall(tc.id(), tc.type(), mapped, tc.arguments()));
                    genChanged = true;
                } else {
                    // 真的找不到（拼写错），保留原样让 delegate 抛 IllegalStateException
                    log.warn("[RobustToolMgr] cannot resolve tool name '{}' (no case-insensitive match)", origName);
                    normCalls.add(tc);
                }
            }
            if (genChanged) {
                AssistantMessage normAm = new AssistantMessage(
                        am.getText(),
                        am.getMetadata(),
                        normCalls,
                        am.getMedia()
                );
                rebuilt.add(new Generation(normAm, gen.getMetadata()));
                anyChanged = true;
            } else {
                rebuilt.add(gen);
            }
        }
        if (!anyChanged) return original;
        return new ChatResponse(rebuilt, original.getMetadata());
    }
}
