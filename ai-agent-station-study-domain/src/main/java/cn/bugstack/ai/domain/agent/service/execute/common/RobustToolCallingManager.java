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

    /**
     * D 段：{@code ask_user} 工具的人工补充 gate（可选，null → 功能关闭）。由 {@code AiClientModelNode}
     * 装配时 set（和 {@link #disableToolsOnNonExecStep} 同处），manager 经它读 enabled/maxAsks/timeout，
     * 自身无需任何 @Value。
     */
    private volatile cn.bugstack.ai.domain.agent.service.security.UserInputGate userInputGate;

    public void setUserInputGate(cn.bugstack.ai.domain.agent.service.security.UserInputGate g) {
        this.userInputGate = g;
    }

    /** ask_user 工具保留名（大小写不敏感匹配）。 */
    public static final String ASK_USER_TOOL_NAME = "ask_user";

    /**
     * ask_user 工具定义。模型缺关键信息 / 存在多种合理理解需用户拍板时调用；要求一次把所有问题问全。
     * 不进 delegate 的 toolCallbacks —— 执行由本 manager 在 {@link #handleAskUser} 内拦截消化。
     */
    private static final ToolDefinition ASK_USER_DEFINITION = ToolDefinition.builder()
            .name(ASK_USER_TOOL_NAME)
            .description("当你缺少完成任务所必需的关键信息，或对用户意图存在多种合理理解、需要用户拍板时，调用本工具向用户提问。"
                    + "请把所有需要澄清的问题一次性放进 questions 数组问全，不要逐条反复追问；questions 里每条必须是一个具体、可直接回答的问题，不要写笼统含糊的话。"
                    + "用户会以自由文本回答（可能直接回答、也可能补充信息或调整需求），其回答会作为本工具的结果返回给你，请据此继续完成任务。")
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"context\":{\"type\":\"string\",\"description\":\"可选。向用户说明你为什么需要这些信息，或你目前的理解，便于用户作答\"},"
                    + "\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"要问用户的问题列表，每条是一个具体、可直接回答的问题，尽量一次问全\"}"
                    + "},\"required\":[\"questions\"]}")
            .build();

    /**
     * reactive 动态补工具：{@code request_tool} 让模型在执行中途发现缺能力时自助装载工具（语义匹配走
     * {@link cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService}）。null / 关 → 不广播、不拦截，行为同现状。
     * 由 {@code AiClientModelNode} 装配时 set（@Lazy 注入避免启动循环依赖）。
     */
    private volatile cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService mcpToolCatalogService;
    private volatile boolean requestToolEnabled = false;
    /** 单次执行最多允许 request_tool 几次（数 prompt 历史里已有 request_tool 响应数判定，无状态、免 sessionId map）。<=0 不限。 */
    private volatile int requestToolMaxCalls = 3;

    public void setMcpToolCatalogService(cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService s) {
        this.mcpToolCatalogService = s;
    }

    public void setRequestToolEnabled(boolean v) {
        this.requestToolEnabled = v;
    }

    public void setRequestToolMaxCalls(int v) {
        this.requestToolMaxCalls = v;
    }

    /**
     * 元工具(ask_user / request_tool)观察卡片 emitter（可选，null → 不发卡片，行为不变）。
     * 这俩不走 {@link MeteredToolCallback}，进度事件得由本 manager 直接发，否则前端静默。
     * 由 {@code AiClientModelNode} 装配时 set。
     */
    private volatile ToolCallProgressEmitter toolCallProgressEmitter;

    public void setToolCallProgressEmitter(ToolCallProgressEmitter e) {
        this.toolCallProgressEmitter = e;
    }

    /** request_tool 工具保留名（大小写不敏感匹配）。 */
    public static final String REQUEST_TOOL_TOOL_NAME = "request_tool";

    /**
     * request_tool 工具定义。模型执行中途发现缺工具能力时调用，在 need 里描述所缺能力；
     * manager 在 {@link #handleMetaToolCalls} 内语义匹配 → 物化真实工具(带 inputSchema)注入到当前 options，
     * 并 appendNeed 到会话级 store 让后续 step 也带上。不进 delegate 的 toolCallbacks。
     */
    private static final ToolDefinition REQUEST_TOOL_DEFINITION = ToolDefinition.builder()
            .name(REQUEST_TOOL_TOOL_NAME)
            .description("当你发现完成任务需要某些当前工具列表里没有的能力时，调用本工具：在 needs 数组里列出你需要的能力，"
                    + "每条用一句话描述（如「读取本地文件内容」「查询股票实时价格」「发送邮件」）。"
                    + "如果一次需要多个能力，请一次性在 needs 里把它们全部列出，不要逐个反复调用。"
                    + "系统会按每条描述分别语义匹配、一并为你装载真实工具；装载成功后你可在后续直接调用被装载的工具完成任务。"
                    + "禁止凭空编造工具名；同一能力不要重复 request。")
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"needs\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                    + "\"description\":\"你需要的工具能力列表，每条一句话、越具体越好；需要多个能力就一次全列出\"}"
                    + "},\"required\":[\"needs\"]}")
            .build();

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
        List<ToolDefinition> base;
        if (disableToolsOnNonExecStep && isNonExecStep(options)) {
            // 非执行步：不暴露真实工具定义 → 请求无真实 tools → 模型不会调真实工具（从源头掐掉，非事后拦截）
            base = new ArrayList<>();
        } else {
            base = new ArrayList<>(delegate.resolveToolDefinitions(options));
        }
        // D 段：在「非执行步禁真实工具」之后再追加 ask_user —— 非执行步=[ask_user]，执行步=[真实工具…, ask_user]。
        // 追加不替换 → 不覆盖原工具；预算用尽 / 开关关 → 不追加，行为同现状。
        if (askUserAvailable()) {
            base.add(ASK_USER_DEFINITION);
        }
        // reactive 动态补工具：开关开 + 有匹配服务即广播 request_tool（非执行步也给——预装给后续执行步用；
        // 预算不在广播处限，只在 executeToolCalls 按 prompt 历史里已有 request_tool 次数硬限，避免再加 sessionId map）。
        if (requestToolAvailable()) {
            base.add(REQUEST_TOOL_DEFINITION);
        }
        return base;
    }

    /** ask_user 当前是否可广播：gate 存在且开启，且本次执行预算未用尽。sessionId 取自 MDC。 */
    private boolean askUserAvailable() {
        if (userInputGate == null || !userInputGate.isEnabled()) return false;
        return userInputGate.remainingFor(org.slf4j.MDC.get("sessionId")) > 0;
    }

    /** request_tool 当前是否可广播：开关开且匹配服务在。预算在执行处限，不在此 gate。 */
    private boolean requestToolAvailable() {
        return requestToolEnabled && mcpToolCatalogService != null;
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

        // ask_user / request_tool 都不是注册 callback（不在 toolCallbacks 里）→ 必须在 caseMap / unknown-tool 检查之前
        // 由本 manager 自己消化，否则会被 findUnknownToolNames 当成幻觉工具误杀。
        boolean askUserActive = userInputGate != null && userInputGate.isEnabled() && hasAskUserCall(chatResponse);
        boolean requestToolActive = requestToolAvailable() && hasRequestToolCall(chatResponse);
        if (askUserActive || requestToolActive) {
            return handleMetaToolCalls(prompt, chatResponse);
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

    /**
     * 统计当前 client 执行链路已经走过多少个串行工具轮次；一条 ToolResponseMessage 代表一轮，可含多条并行响应。
     * <p>
     * D 段：纯 ask_user 的 ToolResponseMessage 不计工具轮（用户拍板：单独问用户不消耗工具预算；
     * 与真实工具并行的混合批含真实响应 → 仍计 1 轮）。
     */
    private int countPriorToolRounds(Prompt prompt) {
        int count = 0;
        if (prompt == null || prompt.getInstructions() == null) return 0;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage trm && trm.getResponses() != null && !trm.getResponses().isEmpty()) {
                if (isMetaToolOnlyResponses(trm.getResponses())) continue;
                count++;
            }
        }
        return count;
    }

    /** 该批响应是否「全是元工具(ask_user / request_tool)」——纯元工具轮不计工具预算。 */
    private boolean isMetaToolOnlyResponses(List<ToolResponseMessage.ToolResponse> responses) {
        for (ToolResponseMessage.ToolResponse r : responses) {
            if (!isAskUserName(r.name()) && !isRequestToolName(r.name())) return false;
        }
        return true;
    }

    private boolean isAskUserName(String name) {
        return name != null && ASK_USER_TOOL_NAME.equalsIgnoreCase(name);
    }

    private boolean isRequestToolName(String name) {
        return name != null && REQUEST_TOOL_TOOL_NAME.equalsIgnoreCase(name);
    }

    private boolean hasAskUserCall(ChatResponse chatResponse) {
        return hasToolCall(chatResponse, this::isAskUserName);
    }

    private boolean hasRequestToolCall(ChatResponse chatResponse) {
        return hasToolCall(chatResponse, this::isRequestToolName);
    }

    private boolean hasToolCall(ChatResponse chatResponse, java.util.function.Predicate<String> nameMatch) {
        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() == null || !gen.getOutput().hasToolCalls()) continue;
            for (AssistantMessage.ToolCall tc : gen.getOutput().getToolCalls()) {
                if (nameMatch.test(tc.name())) return true;
            }
        }
        return false;
    }

    /** 数 prompt 历史里已经有过多少次 request_tool 响应（一条响应里含 request_tool 即计 1），用于无状态预算硬限。 */
    private int countPriorRequestToolCalls(Prompt prompt) {
        int c = 0;
        if (prompt == null || prompt.getInstructions() == null) return 0;
        for (Message m : prompt.getInstructions()) {
            if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (isRequestToolName(r.name())) { c++; break; }
                }
            }
        }
        return c;
    }

    /**
     * 消化含元工具(ask_user / request_tool)的这一批 tool_calls。逐个 call 构建响应：
     * <ul>
     *   <li>ask_user → 调 {@link cn.bugstack.ai.domain.agent.service.security.UserInputGate} 阻塞拿用户回答；</li>
     *   <li>request_tool → 语义匹配装载工具（{@link #runRequestTool}：注入当前 options 让本步下一轮可调 + appendNeed 让后续 step 也带上）；</li>
     *   <li>真实工具（混合批场景）→ 复用 {@link #executeSingleToolCall} 委托 delegate 单工具执行；</li>
     *   <li>未知工具 / 未启用的元工具 → 返「工具不存在」。</li>
     * </ul>
     * 组装成<b>单条</b> ToolResponseMessage（覆盖原 assistant message 里全部 tool_call_id），保证回填对齐。
     * 纯元工具批不计工具轮（见 {@link #countPriorToolRounds}），与真实工具混合时其响应在下一轮计 1 轮。
     */
    private ToolExecutionResult handleMetaToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Map<String, String> caseMap = buildLowerToOriginalNameMap(prompt);
        ChatResponse normalized = caseMap.isEmpty() ? chatResponse : normalizeToolCallNames(chatResponse, caseMap);
        Generation toolGen = firstGenerationWithToolCalls(normalized);
        if (toolGen == null || toolGen.getOutput() == null) {
            return delegate.executeToolCalls(prompt, normalized);
        }
        AssistantMessage fullAm = toolGen.getOutput();
        List<AssistantMessage.ToolCall> calls = fullAm.getToolCalls();
        Set<String> realNames = new HashSet<>(caseMap.values());

        boolean askUserActive = userInputGate != null && userInputGate.isEnabled();
        boolean requestToolActive = requestToolAvailable();
        String sessionId = org.slf4j.MDC.get("sessionId");
        String stepLabel = currentStepLabel(prompt);

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (AssistantMessage.ToolCall tc : calls) {
            if (askUserActive && isAskUserName(tc.name())) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), runAskUser(sessionId, tc, stepLabel)));
            } else if (requestToolActive && isRequestToolName(tc.name())) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), runRequestTool(prompt, tc, sessionId, stepLabel)));
            } else if (!realNames.contains(tc.name())) {
                log.warn("[RobustToolMgr] unknown tool '{}' in meta-tool batch", tc.name());
                if (metrics != null) metrics.recordUnknownToolName(tc.name());
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        "工具不存在（tool not found）：" + tc.name() + "。禁止编造工具名，只能使用已注册的真实工具。"));
            } else {
                try {
                    responses.add(executeSingleToolCall(prompt, normalized, toolGen, tc));
                } catch (Exception e) {
                    log.warn("[RobustToolMgr] tool '{}' failed in meta-tool batch: {}", tc.name(), e.getMessage());
                    responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                            "工具执行失败（tool execution failed）：" + e.getMessage()
                                    + "。请不要重复同样的无效调用，可基于已有信息回答。"));
                }
            }
        }

        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        conversationHistory.add(fullAm);
        conversationHistory.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());
        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(false)
                .build();
    }

    /**
     * request_tool 命中：按 need 语义匹配装载工具。
     * <ol>
     *   <li>预算硬限：数 prompt 历史里已有 request_tool 次数，超 {@link #requestToolMaxCalls} 直接拒绝（无状态）；</li>
     *   <li>{@link cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService#resolveDynamicToolCallbacks} 物化真实回调（带 inputSchema）；</li>
     *   <li>{@link #injectIntoOptions} 并入当前 {@code prompt.getOptions().toolCallbacks} → 框架下一轮即广播+可执行；</li>
     *   <li>{@code appendNeed(sessionId, need)} 写会话级 store → 后续 step 的 resolve 也带上。</li>
     * </ol>
     * 返回给模型的文本说明装载结果，引导其下一轮直接调用被装载工具。
     */
    private String runRequestTool(Prompt prompt, AssistantMessage.ToolCall tc, String sessionId, String stepLabel) {
        String needs = parseRequestToolNeeds(tc.arguments());
        // 观察卡片：开始（展示模型所列的能力描述 needs，让"装载工具"不再静默）
        ToolCallProgressEmitter p = this.toolCallProgressEmitter;
        if (p != null) {
            p.emitMetaStart(sessionId, REQUEST_TOOL_TOOL_NAME,
                    (needs == null || needs.isBlank()) ? "(未提供能力描述)" : needs, stepLabel);
        }
        int prior = countPriorRequestToolCalls(prompt);
        if (requestToolMaxCalls > 0 && prior >= requestToolMaxCalls) {
            return requestToolEnd(sessionId, stepLabel, "blocked", "装载次数已达上限(" + requestToolMaxCalls + ")",
                    "本次执行装载工具的次数已达上限(" + requestToolMaxCalls + ")，请不要再调用 request_tool，"
                            + "基于现有工具完成任务；若关键能力确实缺失，请在答案中说明。");
        }
        if (needs == null || needs.isBlank()) {
            return requestToolEnd(sessionId, stepLabel, "error", "未提供能力描述",
                    "未提供需要的能力描述。请在 needs 数组里用一句话描述你需要的工具能力（可一次多条）后重试。");
        }
        List<ToolCallback> resolved;
        try {
            // needs 多条换行连接；resolveDynamicToolCallbacks 内部 splitNeeds 拆开、各取 top-k 再并集。
            // currentTools 传空：去重在 injectIntoOptions 按工具名统一做；clientId/query 仅供匹配器日志。
            resolved = mcpToolCatalogService.resolveDynamicToolCallbacks(REQUEST_TOOL_TOOL_NAME, needs, needs, java.util.List.of());
        } catch (Exception e) {
            log.warn("[RequestTool] resolve failed needs='{}': {}", needs, e.getMessage());
            return requestToolEnd(sessionId, stepLabel, "error", "匹配出错：" + e.getMessage(),
                    "工具匹配出错：" + e.getMessage() + "。可换一种能力描述再试，或基于现有工具完成任务。");
        }
        if (resolved == null || resolved.isEmpty()) {
            return requestToolEnd(sessionId, stepLabel, "error", "未匹配到「" + needs.replace("\n", " / ") + "」对应的工具",
                    "没有匹配到「" + needs.replace("\n", " / ") + "」对应的可用工具。请基于现有工具完成任务，或换一种更具体的能力描述再试一次。");
        }
        int added = injectIntoOptions(prompt, resolved);
        if (sessionId != null && !sessionId.isBlank()) {
            // 逐条 append（appendNeed 按行去重），让后续 step 的 resolve 也带上每一条能力
            for (String n : needs.split("\\r?\\n")) {
                if (!n.isBlank()) mcpToolCatalogService.appendNeed(sessionId, n.trim());
            }
        }
        List<String> names = new ArrayList<>();
        for (ToolCallback cb : resolved) {
            if (cb.getToolDefinition() != null) names.add(cb.getToolDefinition().name());
        }
        log.info("[RequestTool] needs='{}' matched={} injected={}", needs, names, added);
        // detail 用"工具名按行排列"，前端按行拆成"装配的工具"列表 + 计数("装配了 N 个工具")；
        // 给模型的文本仍用友好句（modelText 与卡片 detail 解耦）。
        return requestToolEnd(sessionId, stepLabel, "success", String.join("\n", names),
                "已为你装载工具：" + names + "。现在可以直接调用上述工具完成任务（同样的能力不要再次 request_tool）。");
    }

    /** request_tool 终态卡片 + 返回给模型的文本一处收口（多 return 点共用，避免每处都写一遍 emit）。 */
    private String requestToolEnd(String sessionId, String step, String status, String detail, String modelText) {
        ToolCallProgressEmitter p = this.toolCallProgressEmitter;
        if (p != null) {
            p.emitMetaEnd(sessionId, REQUEST_TOOL_TOOL_NAME, status, detail, step);
        }
        return modelText;
    }

    /**
     * 把语义匹配到的回调并入 {@code prompt.getOptions().toolCallbacks}（按工具名去重），整列 setToolCallbacks 替换。
     * <p>用 set 整列替换而非裸 add：{@code OpenAiChatModel.buildRequestPrompt} 在无 runtime options 的兜底分支里
     * 会让 requestOptions.toolCallbacks 别名到全局 default options 的 list，裸 add 会污染该 model 后续所有请求；
     * 整列替换天然请求级隔离。框架下一轮 {@code new Prompt(history, prompt.getOptions())} 复用同一 options → 即生效。
     * @return 实际新增（去重后）的工具数
     */
    private int injectIntoOptions(Prompt prompt, List<ToolCallback> resolved) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions opts)) return 0;
        List<ToolCallback> existing = opts.getToolCallbacks();
        List<ToolCallback> union = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (existing != null) {
            for (ToolCallback cb : existing) {
                union.add(cb);
                if (cb != null && cb.getToolDefinition() != null) names.add(cb.getToolDefinition().name());
            }
        }
        int added = 0;
        for (ToolCallback cb : resolved) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            if (names.add(cb.getToolDefinition().name())) {
                union.add(cb);
                added++;
            }
        }
        opts.setToolCallbacks(union);
        return added;
    }

    /**
     * 解析 request_tool 入参里的能力描述，返回多条换行连接的串（喂给 resolveDynamicToolCallbacks 再 splitNeeds）。
     * 优先 JSON 的 {@code needs} 数组（多条）；退化 {@code need}/{@code capability} 单值；再退原始串。
     */
    private String parseRequestToolNeeds(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            com.alibaba.fastjson.JSONObject obj = com.alibaba.fastjson.JSON.parseObject(argsJson);
            if (obj != null) {
                com.alibaba.fastjson.JSONArray arr = obj.getJSONArray("needs");
                if (arr != null && !arr.isEmpty()) {
                    List<String> lines = new ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        String s = arr.getString(i);
                        if (s != null && !s.isBlank()) lines.add(s.trim());
                    }
                    if (!lines.isEmpty()) return String.join("\n", lines);
                }
                String need = obj.getString("need");
                if (need == null || need.isBlank()) need = obj.getString("capability");
                if (need != null && !need.isBlank()) return need.trim();
            }
        } catch (Exception ignored) {
        }
        return argsJson.trim();
    }

    /** 调 gate 阻塞提问，把状态翻译成给 LLM 的工具结果文本；同时发观察卡片（开始=问题列表，结束=用户回复/超时）。 */
    private String runAskUser(String sessionId, AssistantMessage.ToolCall tc, String stepLabel) {
        ToolCallProgressEmitter p = this.toolCallProgressEmitter;
        // 卡片开始：在阻塞等待之前发，让时间线先出现"正在向你提问（问题列表）"，模态框关掉后仍可回看问了什么
        if (p != null) {
            p.emitMetaStart(sessionId, ASK_USER_TOOL_NAME, askUserPreview(tc.arguments()), stepLabel);
        }
        cn.bugstack.ai.domain.agent.service.security.UserInputGate.Result r =
                userInputGate.requestUserInput(sessionId, tc.arguments(), stepLabel);
        String status;
        String detail;
        String modelText;
        switch (r.status) {
            case ANSWERED:
                if (r.answer == null || r.answer.isBlank()) {
                    status = "success";
                    detail = "用户未填写具体内容";
                    modelText = "用户未填写具体内容。请基于现有信息继续完成任务。";
                } else {
                    status = "success";
                    detail = "用户回复：" + r.answer;
                    modelText = "用户回复：" + r.answer;
                }
                break;
            case TIMEOUT:
                status = "approval_timeout";
                detail = "用户未在规定时间内回应";
                modelText = "用户未在规定时间内回应。请基于现有信息继续完成任务，必要时在答案中说明哪些信息缺失及你做出的假设。";
                break;
            case BUDGET_EXCEEDED:
                status = "blocked";
                detail = "提问次数已用完";
                modelText = "向用户提问的次数已用完，请不要再调用 ask_user。请基于已经掌握的信息给出最佳答案。";
                break;
            case UNAVAILABLE:
            default:
                status = "approval_unavailable";
                detail = "交互通道不可用";
                modelText = "当前无法向用户提问（交互通道不可用）。请基于现有信息继续完成任务。";
                break;
        }
        if (p != null) {
            p.emitMetaEnd(sessionId, ASK_USER_TOOL_NAME, status, detail, stepLabel);
        }
        return modelText;
    }

    /** 解析 ask_user 入参里的 context + questions，拼成给卡片看的预览（编号列表）；解析失败退原始串。 */
    private String askUserPreview(String argsJson) {
        try {
            com.alibaba.fastjson.JSONObject o = com.alibaba.fastjson.JSON.parseObject(argsJson);
            if (o != null) {
                StringBuilder sb = new StringBuilder();
                String ctx = o.getString("context");
                if (ctx != null && !ctx.isBlank()) sb.append(ctx.trim()).append("\n");
                com.alibaba.fastjson.JSONArray qs = o.getJSONArray("questions");
                if (qs != null) {
                    for (int i = 0; i < qs.size(); i++) {
                        String q = qs.getString(i);
                        if (q != null && !q.isBlank()) sb.append(i + 1).append(". ").append(q.trim()).append("\n");
                    }
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
        } catch (Exception ignored) {
        }
        return argsJson == null ? "" : argsJson.trim();
    }

    /** 取当前步骤标签：优先 ToolContext 的 stepLabel，退 MDC step；都没有返 null（前端不显示步骤行）。 */
    private String currentStepLabel(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions tco && tco.getToolContext() != null) {
            Object v = tco.getToolContext().get("stepLabel");
            if (v != null) return String.valueOf(v);
        }
        return org.slf4j.MDC.get("step");
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
