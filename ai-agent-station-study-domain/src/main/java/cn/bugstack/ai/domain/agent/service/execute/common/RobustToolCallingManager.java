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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

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
    private final McpToolMetrics metrics;
    /** 并行执行用的 IO 线程池（dagExecutor，execute() 自动 ContextSnapshot.wrap 接力 MDC）；null → 退化串行 */
    private final ThreadPoolExecutor toolExecutor;
    /** 并行开关；false 或 toolExecutor==null → 一律委托 delegate 串行执行 */
    private final boolean parallelEnabled;
    /** 单个 client 执行链路内最多允许的串行工具轮数；<=0 表示不限制。并行的一批 tool call 算 1 轮。 */
    private final int maxSerialToolRoundsPerClient;
    /**
     * 方案A：toolName → mcpId 映射，用于把并行批次按 MCP server 分组——同一个 McpSyncClient 连接不是并发安全的
     * （Reactor Sinks 要求串行发送，并发会 "Failed to enqueue message" / "Unexpected response for unknown id"），
     * 所以同组（同连接）串行、不同组（不同连接）并行。null → 全部归一组，退化串行。
     */
    private final McpClientRegistry mcpClientRegistry;

    /**
     * true：非执行步（分析/规划/质检/汇总）不向模型暴露任何工具定义——{@link #resolveToolDefinitions} 直接返回空，
     * 模型请求体里就<b>没有</b> {@code tools} 字段，<b>模型从源头不会发起 tool_call</b>（不是"调用后拦截"）。执行步不受影响。
     * 由 {@code agent.mcp.disable-tools-on-nonexec-steps} 控制，{@code AiClientModelNode} 装配时 set。
     */
    private volatile boolean disableToolsOnNonExecStep = false;

    public void setDisableToolsOnNonExecStep(boolean v) {
        this.disableToolsOnNonExecStep = v;
    }

    public RobustToolCallingManager(ToolCallingManager delegate) {
        this(delegate, null, null, false, null, 0);
    }

    public RobustToolCallingManager(ToolCallingManager delegate, McpToolMetrics metrics) {
        this(delegate, metrics, null, false, null, 0);
    }

    public RobustToolCallingManager(ToolCallingManager delegate, McpToolMetrics metrics,
                                    ThreadPoolExecutor toolExecutor, boolean parallelEnabled) {
        this(delegate, metrics, toolExecutor, parallelEnabled, null, 0);
    }

    public RobustToolCallingManager(ToolCallingManager delegate, McpToolMetrics metrics,
                                    ThreadPoolExecutor toolExecutor, boolean parallelEnabled,
                                    McpClientRegistry mcpClientRegistry) {
        this(delegate, metrics, toolExecutor, parallelEnabled, mcpClientRegistry, 0);
    }

    public RobustToolCallingManager(ToolCallingManager delegate, McpToolMetrics metrics,
                                    ThreadPoolExecutor toolExecutor, boolean parallelEnabled,
                                    McpClientRegistry mcpClientRegistry, int maxSerialToolRoundsPerClient) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.toolExecutor = toolExecutor;
        this.parallelEnabled = parallelEnabled;
        this.mcpClientRegistry = mcpClientRegistry;
        this.maxSerialToolRoundsPerClient = maxSerialToolRoundsPerClient;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        if (disableToolsOnNonExecStep && isNonExecStep(options)) {
            // 非执行步：不暴露工具定义 → 请求无 tools 字段 → 模型根本不会 tool_call（从源头掐掉，非事后拦截）
            return List.of();
        }
        return delegate.resolveToolDefinitions(options);
    }

    /**
     * 当前请求是否属于"非执行步"（分析/规划/质检/汇总）。判定依据：{@code callStepWithStreaming} 注入到
     * ToolContext 的 {@code stepLabel}(=各步中文 displayName，如"需求分析/质量评审/步骤规划/最终总结/MCP 工具分析")；
     * 取不到再退 MDC {@code "step"}(stepId)；都取不到 → 保守按执行步放行（识别不出时不误伤执行步/fixed）。
     * 执行步含"执行/回答/最终合成"或对应 stepId 片段，其余即非执行步。与 E2E earlyStepToolCallCount 口径一致。
     */
    private boolean isNonExecStep(ToolCallingChatOptions options) {
        String label = null;
        if (options != null && options.getToolContext() != null) {
            Object v = options.getToolContext().get("stepLabel");
            if (v != null) label = String.valueOf(v);
        }
        if (label == null || label.isBlank()) {
            label = org.slf4j.MDC.get("step");
        }
        if (label == null || label.isBlank()) {
            return false;
        }
        String s = label.toLowerCase();
        boolean executor = s.contains("执行") || s.contains("回答") || s.contains("最终合成")
                || s.contains("precision_executor") || s.contains("execute_step")
                || s.contains("execute_steps") || s.contains("final_synthesis")
                || s.contains("fixed") || s.contains("response");
        return !executor;
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

        if (maxSerialToolRoundsPerClient > 0) {
            Generation toolGen = firstGenerationWithToolCalls(normalized);
            int priorRounds = countPriorToolRounds(prompt);
            int currentCalls = toolGen != null && toolGen.getOutput() != null
                    ? toolGen.getOutput().getToolCalls().size() : 0;
            // 当前这批 tool calls 无论有几个，都是同一轮 assistant response；并行批量调用只算 1 个串行轮次。
            int nextRound = currentCalls > 0 ? 1 : 0;
            if (priorRounds + nextRound > maxSerialToolRoundsPerClient) {
                log.warn("[RobustToolMgr] serial tool round limit exceeded priorRounds={} currentCalls={} maxRounds={}, returning cap error",
                        priorRounds, currentCalls, maxSerialToolRoundsPerClient);
                return buildToolCallRoundLimitResult(prompt, normalized, priorRounds, currentCalls, maxSerialToolRoundsPerClient);
            }
        }

        // 并行执行：同一 assistant message 含 ≥2 个 tool call 时 fan-out 到 toolExecutor。
        // N≤1 / 开关关 / 无池 → 走 delegate 串行（覆盖绝大多数逐工具调用，零改动零风险）。
        if (parallelEnabled && toolExecutor != null) {
            Generation toolGen = firstGenerationWithToolCalls(normalized);
            if (toolGen != null && toolGen.getOutput() != null
                    && toolGen.getOutput().getToolCalls().size() >= 2) {
                return executeInParallel(prompt, normalized, toolGen);
            }
        }

        return delegate.executeToolCalls(prompt, normalized);
    }

    /** 统计当前 client 执行链路已经走过多少个串行工具轮次；一条 ToolResponseMessage 代表一轮，可含多条并行响应。 */
    private int countPriorToolRounds(Prompt prompt) {
        int count = 0;
        if (prompt == null || prompt.getInstructions() == null) return 0;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage trm && trm.getResponses() != null && !trm.getResponses().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 取第一个含 tool call 的 Generation（与 buildUnknownToolErrorResult 取法一致，通常只有一个）。 */
    private Generation firstGenerationWithToolCalls(ChatResponse chatResponse) {
        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() != null && gen.getOutput().hasToolCalls()) {
                return gen;
            }
        }
        return null;
    }

    /**
     * 并行版执行：把 N 个 tool call 切成 N 个单工具 ChatResponse，各自委托 {@link #delegate}（复用其
     * ToolContext 构建 / callback 查找 / 错误处理 / metric），并行跑在 toolExecutor 上，再按原序合并成
     * 一个 {@link ToolResponseMessage}。组装结构照搬 {@link #buildUnknownToolErrorResult}。
     * <p>
     * MDC（sessionId / userId / stepLabel）靠 toolExecutor(dagExecutor) 的 execute() ContextSnapshot.wrap
     * 自动接力到 worker 线程——审批 gate / metric / Reactor 上下文才不丢。
     * <p>
     * returnDirect 固定 false：本 manager 仅装配给 MCP 工具模型，MCP 工具恒非 returnDirect。
     */
    private ToolExecutionResult executeInParallel(Prompt prompt, ChatResponse normalized, Generation toolGen) {
        AssistantMessage fullAm = toolGen.getOutput();
        List<AssistantMessage.ToolCall> calls = fullAm.getToolCalls();

        // 方案A：按 MCP server 分组。同一连接非并发安全 → 同组串行；不同连接 → 组间并行。
        java.util.LinkedHashMap<String, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < calls.size(); i++) {
            groups.computeIfAbsent(resolveMcpGroupKey(calls.get(i).name()), k -> new ArrayList<>()).add(i);
        }

        // 全在同一个 MCP server（或无法识别 mcpId）→ 没有可安全并行的，退回 delegate 原生串行（零风险、零自定义合并）。
        if (groups.size() <= 1) {
            log.debug("[RobustToolMgr] {} tool calls in one MCP group, executing serially via delegate", calls.size());
            return delegate.executeToolCalls(prompt, normalized);
        }

        // 组间并行：每个 group 一个 task，task 内部按原顺序串行跑该连接的工具；结果写回原始 index 槽位。
        ToolResponseMessage.ToolResponse[] ordered = new ToolResponseMessage.ToolResponse[calls.size()];
        List<CompletableFuture<Void>> futures = new ArrayList<>(groups.size());
        for (List<Integer> indices : groups.values()) {
            futures.add(CompletableFuture.runAsync(() -> {
                for (int idx : indices) {
                    AssistantMessage.ToolCall tc = calls.get(idx);
                    try {
                        ordered[idx] = executeSingleToolCall(prompt, normalized, toolGen, tc);
                    } catch (Exception e) {
                        log.warn("[RobustToolMgr] tool '{}' failed: {}", tc.name(), e.getMessage());
                        ordered[idx] = new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(), "工具执行失败（tool execution failed）：" + e.getMessage()
                                + "。请不要重复同样的无效调用，可基于已有信息回答，或改用其它真实可用工具。");
                    }
                }
            }, toolExecutor));
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (Exception e) {
                log.warn("[RobustToolMgr] tool group join failed: {}", e.getMessage());
            }
        }

        // 按原 tool call 顺序组装（模型靠 tool_call_id 匹配，顺序稳定更稳）
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            responses.add(ordered[i] != null ? ordered[i]
                    : new ToolResponseMessage.ToolResponse(calls.get(i).id(), calls.get(i).name(), ""));
        }

        log.info("[RobustToolMgr] parallel executed {} tool calls across {} MCP groups", calls.size(), groups.size());

        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        conversationHistory.add(fullAm);
        conversationHistory.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());
        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(false)
                .build();
    }

    /** toolName → 分组键：优先用 mcpId（同连接一组）；拿不到时归到统一兜底组（保守串行）。 */
    private String resolveMcpGroupKey(String toolName) {
        if (mcpClientRegistry != null && toolName != null) {
            try {
                String mcpId = mcpClientRegistry.getMcpIdForTool(toolName);
                if (mcpId != null && !mcpId.isBlank()) return mcpId;
            } catch (Exception ignored) {
            }
        }
        return "__no_mcp_id__";
    }

    /**
     * 执行单个 tool call：构建只含该 ToolCall 的 ChatResponse，委托 delegate 执行，
     * 从结果 conversationHistory 末尾的 ToolResponseMessage 取出对应那条响应。
     */
    private ToolResponseMessage.ToolResponse executeSingleToolCall(
            Prompt prompt, ChatResponse normalized, Generation toolGen, AssistantMessage.ToolCall tc) {
        AssistantMessage fullAm = toolGen.getOutput();
        AssistantMessage singleAm = AssistantMessage.builder()
                .content(fullAm.getText())
                .properties(fullAm.getMetadata())
                .toolCalls(List.of(tc))
                .media(fullAm.getMedia())
                .build();
        Generation singleGen = new Generation(singleAm, toolGen.getMetadata());
        ChatResponse single = new ChatResponse(List.of(singleGen), normalized.getMetadata());

        ToolExecutionResult result = delegate.executeToolCalls(prompt, single);
        return extractToolResponse(result, tc);
    }

    /** 从 delegate 结果的 conversationHistory 末尾 ToolResponseMessage 取出 tc 对应（按 id 匹配，退化取首条）的响应。 */
    private ToolResponseMessage.ToolResponse extractToolResponse(ToolExecutionResult result, AssistantMessage.ToolCall tc) {
        if (result != null && result.conversationHistory() != null) {
            List<Message> history = result.conversationHistory();
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) instanceof ToolResponseMessage trm) {
                    List<ToolResponseMessage.ToolResponse> rs = trm.getResponses();
                    if (rs != null && !rs.isEmpty()) {
                        if (tc.id() != null) {
                            for (ToolResponseMessage.ToolResponse r : rs) {
                                if (tc.id().equals(r.id())) return r;
                            }
                        }
                        return rs.get(0);
                    }
                    break;
                }
            }
        }
        log.warn("[RobustToolMgr] no tool response extracted for '{}', returning empty", tc.name());
        return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "");
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
                    if (unknown.add(tc.name()) && metrics != null) {
                        metrics.recordUnknownToolName(tc.name());
                    }
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
        String errorData = "工具不存在（tool not found）：" + unknownNames
                + "。你只能使用下面已注册的真实工具名，禁止编造工具名。\n"
                + "可用工具：\n" + toolList;

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
        conversationHistory.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());

        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(false)
                .build();
    }

    /**
     * 工具调用次数超过上限时，不再真实执行工具，而是把结构化错误返回给模型，要求其基于已有信息收束。
     */
    private ToolExecutionResult buildToolCallRoundLimitResult(
            Prompt prompt, ChatResponse chatResponse, int priorRounds, int currentCalls, int maxRounds) {
        AssistantMessage assistantMessage = null;
        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() != null && gen.getOutput().hasToolCalls()) {
                assistantMessage = gen.getOutput();
                break;
            }
        }

        String errorData = "工具调用轮次预算已用完（tool call budget exceeded）。"
                + "本次 client 执行已经使用 " + priorRounds + " 轮工具调用，"
                + "当前请求又尝试在新一轮中调用 " + currentCalls + " 个工具，"
                + "但最多只允许 " + maxRounds + " 轮。"
                + "请立刻停止继续调用工具，基于已经收集到的信息给出最佳答案。"
                + "如果关键数据缺失，请明确列出缺失字段，并提供保守兜底方案。";

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (assistantMessage != null) {
            for (AssistantMessage.ToolCall tc : assistantMessage.getToolCalls()) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), errorData));
            }
        }

        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        if (assistantMessage != null) {
            conversationHistory.add(assistantMessage);
        }
        conversationHistory.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());

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
                    if (metrics != null) {
                        metrics.recordNameNormalized(origName, mapped);
                    }
                    normCalls.add(new AssistantMessage.ToolCall(tc.id(), tc.type(), mapped, tc.arguments()));
                    genChanged = true;
                } else {
                    // 真的找不到（拼写错），保留原样让 delegate 抛 IllegalStateException
                    log.warn("[RobustToolMgr] cannot resolve tool name '{}' (no case-insensitive match)", origName);
                    normCalls.add(tc);
                }
            }
            if (genChanged) {
                AssistantMessage normAm = AssistantMessage.builder()
                        .content(am.getText())
                        .properties(am.getMetadata())
                        .toolCalls(normCalls)
                        .media(am.getMedia())
                        .build();
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
