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

    /** P0-B2b-Step3：repair 专用 ToolContext key——置 {@code Boolean.TRUE} 时 resolveToolDefinitions 直接返回空定义。 */
    public static final String FORCE_NO_TOOLS_KEY = "agent.force_no_tools";
    private static final com.fasterxml.jackson.databind.ObjectMapper ASK_USER_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
    /** P1-A1 迁移期每个共享 manager 只报一次 missing-policy warn；逐请求频次由低基数 metric 统计。 */
    private final java.util.concurrent.atomic.AtomicBoolean missingPolicyWarned = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * D 段：{@code ask_user} 工具的人工补充 gate（可选，null → 功能关闭）。由 {@code AiClientModelNode}
     * 装配时 set，manager 经它读 enabled/maxAsks/timeout，
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
                    + "请把所有需要澄清的问题一次性放进 questions 数组问全，不要逐条反复追问；每条必须是一个具体、可直接回答的问题，不要写笼统含糊的话。"
                    + "先检查本轮跨节点共享的用户补充信息，已回答的问题直接复用；只有仍缺失或确有冲突的信息才继续询问。"
                    + "questions 必须直接传 JSON 数组，禁止先将整个数组序列化成字符串再传入。"
                    + "当问题存在明确的常见答案时，优先给出 2-4 个具体 options 作为快捷选择；默认为单选，只有选项可以同时成立、用户确实可选多项时才设置 multiple=true。"
                    + "保持 allowFreeText=true，让用户仍可输入选项之外的答案；"
                    + "若信息不足以可靠枚举选项，则不要猜测，可省略 options。选项只是帮助用户快速作答，不能代替用户最终以文本形式提交的回答。"
                    + "用户会以自由文本回答（可能直接回答、也可能补充信息或调整需求），其回答会作为本工具的结果返回给你，请据此继续完成任务。")
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"context\":{\"type\":\"string\",\"description\":\"可选。向用户说明你为什么需要这些信息，或你目前的理解，便于用户作答\"},"
                    + "\"questions\":{\"type\":\"array\",\"description\":\"必须直接传入 JSON 数组，禁止把整个数组序列化成字符串；数组内优先使用结构化问题，兼容单个字符串问题\",\"items\":{\"oneOf\":["
                    + "{\"type\":\"string\",\"description\":\"兼容旧格式：一个具体、可直接回答的问题\"},"
                    + "{\"type\":\"object\",\"properties\":{"
                    + "\"question\":{\"type\":\"string\",\"description\":\"一个具体、可直接回答的问题\"},"
                    + "\"options\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":4,\"description\":\"可选。仅在存在明确且互斥的常见答案时提供 2-4 个快捷选项\",\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"label\":{\"type\":\"string\",\"description\":\"展示给用户的简短选项名\"},"
                    + "\"value\":{\"type\":\"string\",\"description\":\"用户选择后写入最终文本回答的值；省略时等于 label\"},"
                    + "\"description\":{\"type\":\"string\",\"description\":\"可选。用一句话解释该选项的影响或区别\"}"
                    + "},\"required\":[\"label\"]}},"
                    + "\"allowFreeText\":{\"type\":\"boolean\",\"description\":\"是否允许用户输入选项之外的文本；默认并优先设为 true\",\"default\":true},"
                    + "\"multiple\":{\"type\":\"boolean\",\"description\":\"该问题是否允许同时选择多个 options；默认 false，只有真正的多选题才设为 true\",\"default\":false}"
                    + "},\"required\":[\"question\"]}"
                    + "]}}"
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

    private volatile ToolResultReferenceService toolResultReferenceService;
    private volatile RunArtifactService runArtifactService;
    private volatile cn.bugstack.ai.domain.agent.service.attachment.IChatFileService chatFileService;
    private volatile cn.bugstack.ai.domain.agent.service.attachment.RunAttachmentRegistry runAttachments;
    private volatile ToolCallLedger toolCallLedger;

    /** 单次模型调用（含其内部工具循环）最多读取附件的次数，防止逐行读大文件耗尽预算。 */
    static final int MAX_ATTACHMENT_READS_PER_CALL = 40;

    public void setRunArtifactService(RunArtifactService service) { this.runArtifactService = service; }

    /** read_attachment：只在本次运行或本会话确有附件时广播（见 {@link cn.bugstack.ai.domain.agent.service.attachment.RunAttachmentRegistry}）。 */
    public void setChatFileService(cn.bugstack.ai.domain.agent.service.attachment.IChatFileService service,
                                   cn.bugstack.ai.domain.agent.service.attachment.RunAttachmentRegistry registry) {
        this.chatFileService = service;
        this.runAttachments = registry;
    }

    /** Auto 质检取证：read_attachment 不走 MeteredToolCallback，由本 manager 记账。 */
    public void setToolCallLedger(ToolCallLedger ledger) { this.toolCallLedger = ledger; }

    private volatile cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService sandboxService;

    /** run_python：只在执行步骤（RUN_CODE）、沙箱可用且节点没有关闭代码运行时广播。 */
    public void setSandboxService(cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService service) {
        this.sandboxService = service;
    }

    public void setToolResultReferenceService(ToolResultReferenceService service) {
        this.toolResultReferenceService = service;
    }

    private static final ToolDefinition INSPECT_RESULT_DEFINITION = ToolDefinition.builder()
            .name(ToolResultReferenceService.TOOL_NAME)
            .description("读取本次运行中工具返回的 resultRef 原始结果，不重复执行原工具。可按 JSON Pointer 选取字段，"
                    + "再用 offset/limit 按字符分页；只读取确实需要的部分。空 jsonPointer 读取原文。")
            .inputSchema("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                    + "\"resultRef\":{\"type\":\"string\"},\"jsonPointer\":{\"type\":\"string\"},"
                    + "\"offset\":{\"type\":\"integer\",\"minimum\":0},"
                    + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4000}},\"required\":[\"resultRef\"]}")
            .build();

    /** request_tool 工具保留名（大小写不敏感匹配）。 */
    public static final String REQUEST_TOOL_TOOL_NAME = "request_tool";

    /**
     * request_tool 工具定义。模型执行中途发现缺工具能力时调用，在 need 里描述所缺能力；
     * manager 在 {@link #handleMetaToolCalls} 内语义匹配并 appendNeed 到会话级 store；仅当当前 profile 同时允许
     * {@link ToolCapability#BUSINESS_TOOL} 时注入当前 options，否则只为后续执行阶段登记。不进 delegate 的 toolCallbacks。
     */
    private static final ToolDefinition REQUEST_TOOL_DEFINITION = ToolDefinition.builder()
            .name(REQUEST_TOOL_TOOL_NAME)
            .description("当你发现完成任务需要某些当前工具列表里没有的能力时，调用本工具：在 needs 数组里列出你需要的能力，"
                    + "每条用一句话描述（如「读取本地文件内容」「查询股票实时价格」「发送邮件」）。"
                    + "如果一次需要多个能力，请一次性在 needs 里把它们全部列出，不要逐个反复调用。"
                    + "系统会按每条描述分别语义匹配、一并为你装载真实工具；装载成功后你可在后续直接调用被装载的工具完成任务。"
                    + "仅整理现有回答、排版 Markdown 不需要装配工具；保存文档、表格或代码文件请直接使用已提供的 create_artifact。"
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

    private cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy workspaceNodePolicy;
    public void setWorkspaceNodePolicy(cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy policy) {
        this.workspaceNodePolicy=policy;
    }
    private void restrictNodeOptions(ToolCallingChatOptions options) {
        if (workspaceNodePolicy==null || options==null) return;
        options.setToolCallbacks(new ArrayList<>(workspaceNodePolicy.filter(options.getToolCallbacks())));
        // A name-only resolver must not bypass the connection + tool identity ceiling.
        options.setToolNames(Set.of());
    }
    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        restrictNodeOptions(options);
        // P0-B2b-Step3：请求级强制无工具（repair 专用，类型化 flag，模型不可伪造）——
        // 必须在 delegate / 非执行步 gate / ask_user / request_tool 之前返回空（连元工具也不广播）。
        if (isForceNoTools(options)) {
            return new ArrayList<>();
        }
        ToolCapabilityProfile policy = effectivePolicy(options);
        List<ToolDefinition> base = policy.allows(ToolCapability.BUSINESS_TOOL)
                ? new ArrayList<>(delegate.resolveToolDefinitions(options))
                : new ArrayList<>();
        // Reserved platform name cannot be shadowed by a remote callback.
        base.removeIf(definition -> isReservedMetaToolName(definition.name()));
        if (policy.allows(ToolCapability.CREATE_ARTIFACT) && runArtifactService != null && !artifactRecoveryBlocked(options)) {
            base.add(runArtifactService.definition());
        }
        if (policy.allows(ToolCapability.BUSINESS_TOOL) && toolResultReferenceService != null) {
            base.add(INSPECT_RESULT_DEFINITION);
        }
        if (policy.allows(ToolCapability.READ_ATTACHMENT) && attachmentsReadable(options)) {
            base.add(cn.bugstack.ai.domain.agent.service.attachment.ReadAttachmentTool.DEFINITION);
        }
        if (policy.allows(ToolCapability.RUN_CODE) && codeExecutionAllowed()) {
            base.add(cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService.DEFINITION);
        }
        // policy ceiling 与运行期 availability 取交集；NONE/BUSINESS_ONLY 不得再泄出元工具。
        if (policy.allows(ToolCapability.ASK_USER) && askUserAvailable(options)) {
            base.add(ASK_USER_DEFINITION);
        }
        if (policy.allows(ToolCapability.REQUEST_TOOL) && requestToolAvailable()) {
            base.add(REQUEST_TOOL_DEFINITION);
        }
        return base;
    }

    /**
     * P1-A1 policy 解析：显式值优先；非法显式值 fail-closed；缺失才精确复现旧开关+stepLabel 行为。
     */
    private ToolCapabilityProfile effectivePolicy(ToolCallingChatOptions options) {
        Map<String, Object> context = options != null ? options.getToolContext() : null;
        ToolCapabilities.Parsed parsed = ToolCapabilities.parse(context);
        if (parsed.state() == ToolCapabilities.State.EXPLICIT) {
            if (metrics != null) metrics.recordToolPolicyResolution("explicit");
            return parsed.profile();
        }
        if (parsed.state() == ToolCapabilities.State.INVALID) {
            if (metrics != null) metrics.recordToolPolicyResolution("invalid");
            log.warn("[ToolPolicy] invalid explicit policy value '{}', fail-closed to NONE", parsed.rawValue());
            return ToolCapabilityProfile.NONE;
        }
        if (metrics != null) metrics.recordToolPolicyResolution("missing");
        if (missingPolicyWarned.compareAndSet(false, true)) {
            log.warn("[ToolPolicy] missing explicit tool-capabilities policy; fail-closed to NONE (warn once per manager). "
                    + "P1-B removed the legacy stepLabel/global-switch fallback — every call prototype must inject an explicit profile");
        }
        // P1-B：legacy stepLabel/global-switch fallback 已删除。缺失显式 policy 一律 fail-closed 到 NONE，
        // 不再依据 stepLabel 猜执行步。主链所有调用原型已显式注入(P1-A1 + §63 smoke 证 missing=0)；
        // 若此处仍触发，说明存在未迁移/漏注入路径——由 policy.resolution{state=missing} metric 暴露并补注入，绝不放行工具。
        return ToolCapabilityProfile.NONE;
    }

    private ToolCapabilityProfile effectivePolicy(Prompt prompt) {
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions options) {
            return effectivePolicy(options);
        }
        return effectivePolicy((ToolCallingChatOptions) null);
    }

    private boolean isForceNoTools(Prompt prompt) {
        return prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions options && isForceNoTools(options);
    }

    /** Prefer request-local ToolContext over MDC, which may be absent on Reactor workers. */
    private boolean askUserAvailable(ToolCallingChatOptions options) {
        if (userInputGate == null || !userInputGate.isEnabled()) return false;
        return userInputGate.remainingFor(toolIdentity(options, "sessionId")) > 0;
    }

    private boolean codeExecutionAllowed() {
        var sandbox = this.sandboxService;
        return sandbox != null && sandbox.available()
                && (workspaceNodePolicy == null || workspaceNodePolicy.codeExecution());
    }

    private boolean attachmentsReadable(ToolCallingChatOptions options) {
        return chatFileService != null && runAttachments != null && options != null
                && runAttachments.readable(options.getToolContext());
    }

    /** request_tool 当前是否可广播：开关开且匹配服务在。预算在执行处限，不在此 gate。 */
    private boolean requestToolAvailable() {
        return requestToolEnabled && mcpToolCatalogService != null;
    }

    /** P0-B2b-Step3：repair 请求级强制无工具开关（类型化、模型不可伪造，来自应用侧 ToolContext）。 */
    private boolean isForceNoTools(ToolCallingChatOptions options) {
        if (options != null && options.getToolContext() != null) {
            return Boolean.TRUE.equals(options.getToolContext().get(FORCE_NO_TOOLS_KEY));
        }
        return false;
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        StreamingActivityTracker.Activity activity = activityFor(prompt);
        StreamingActivityTracker.checkActive(activity);
        try (StreamingActivityTracker.Scope ignored = StreamingActivityTracker.scope(activity)) {
            if (activity != null) activity.markToolActivity();
            ToolExecutionResult result = executeActiveToolCalls(prompt, chatResponse);
            StreamingActivityTracker.checkActive(activity);
            if (activity != null) activity.markDecodedResponse();
            return result;
        }
    }

    private StreamingActivityTracker.Activity activityFor(Prompt prompt) {
        return StreamingActivityTracker.from(prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions options
                ? options.getToolContext() : null);
    }

    /** Guard every callback, including the second callback in the framework's serial batch. */
    private ToolExecutionResult executeDelegate(Prompt prompt, ChatResponse response) {
        StreamingActivityTracker.Activity activity = activityFor(prompt);
        StreamingActivityTracker.checkActive(activity);
        if (activity == null || prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return delegate.executeToolCalls(prompt, response);
        }
        ToolCallingChatOptions scoped = (ToolCallingChatOptions) options.copy();
        if (options.getToolCallbacks() != null) {
            scoped.setToolCallbacks(options.getToolCallbacks().stream().map(callback -> (ToolCallback) new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
                @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() { return callback.getToolMetadata(); }
                @Override public String call(String input) { return call(input, null); }
                @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                    activity.markToolActivity();
                    try (StreamingActivityTracker.Scope ignored = StreamingActivityTracker.scope(activity)) {
                        String result = context == null ? callback.call(input) : callback.call(input, context);
                        activity.checkActive();
                        return result;
                    } finally {
                        activity.markDecodedResponse();
                    }
                }
            }).toList());
        }
        ToolExecutionResult result = delegate.executeToolCalls(new Prompt(prompt.getInstructions(), scoped), response);
        activity.checkActive();
        return result;
    }

    private ToolExecutionResult executeActiveToolCalls(Prompt prompt, ChatResponse chatResponse) {
        if (prompt == null || chatResponse == null) {
            return executeDelegate(prompt, chatResponse);
        }

        // definitions 是第一道门；这里是第二道门，防模型幻觉/旧上下文直接发起未授权调用。
        ToolCapabilityProfile policy = isForceNoTools(prompt) ? ToolCapabilityProfile.NONE : effectivePolicy(prompt);
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) restrictNodeOptions(options);
        // Derive aliases only after narrowing to this node's actual callbacks. Catalog original
        // names and SDK model names identify the same callback, not a permission to load others.
        Map<String, String> caseMap = buildLowerToOriginalNameMap(prompt);
        ChatResponse normalized = normalizeToolCallNames(chatResponse, caseMap);
        if (artifactRecoveryBlocked(prompt.getOptions() instanceof ToolCallingChatOptions o ? o : null)
                && hasToolCall(normalized, this::isCreateArtifactName)
                && normalized.getResults().stream().allMatch(g -> g.getOutput() == null
                    || g.getOutput().getToolCalls().stream().allMatch(c -> isCreateArtifactName(c.name())))) {
            return artifactUnavailableResult(prompt, normalized);
        }
        ToolExecutionResult invalidArguments = rejectMalformedArguments(prompt, normalized);
        if (invalidArguments != null) return invalidArguments;
        Set<String> denied = findPolicyDeniedToolNames(normalized, policy);
        if (workspaceNodePolicy!=null) {
            for (var generation:normalized.getResults()) {
                if (generation.getOutput()==null || !generation.getOutput().hasToolCalls()) continue;
                for (var call:generation.getOutput().getToolCalls()) {
                    if (!isReservedMetaToolName(call.name())
                            && !caseMap.containsKey(call.name().toLowerCase(java.util.Locale.ROOT))) denied.add(call.name());
                }
            }
        }
        if (!denied.isEmpty()) {
            log.warn("[ToolPolicy] denied tool calls at execution stage: {}", denied);
            return buildPolicyDeniedResult(prompt, normalized, denied);
        }

        // ask_user / request_tool 都不是注册 callback（不在 toolCallbacks 里）→ 必须在 caseMap / unknown-tool 检查之前
        // 由本 manager 自己消化，否则会被 findUnknownToolNames 当成幻觉工具误杀。
        boolean askUserActive = userInputGate != null && userInputGate.isEnabled() && hasAskUserCall(normalized);
        boolean requestToolActive = requestToolAvailable() && hasRequestToolCall(normalized);
        if (askUserActive || requestToolActive || hasToolCall(normalized, this::isInspectResultName)
                || hasToolCall(normalized, this::isCreateArtifactName)
                || hasToolCall(normalized, this::isReadAttachmentName)
                || hasToolCall(normalized, this::isRunPythonName)) {
            return handleMetaToolCalls(prompt, normalized);
        }

        // 拿 prompt 自带的真实工具名（注入到 ChatClient.defaultToolCallbacks 的那一组）
        if (caseMap.isEmpty() && (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty())) {
            return executeDelegate(prompt, normalized);
        }

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

        return executeDelegate(prompt, normalized);
    }

    private Set<String> findPolicyDeniedToolNames(ChatResponse response, ToolCapabilityProfile policy) {
        Set<String> denied = new java.util.LinkedHashSet<>();
        if (response == null || response.getResults() == null) return denied;
        for (Generation gen : response.getResults()) {
            if (gen.getOutput() == null || !gen.getOutput().hasToolCalls()) continue;
            for (AssistantMessage.ToolCall tc : gen.getOutput().getToolCalls()) {
                ToolCapability needed = isAskUserName(tc.name()) ? ToolCapability.ASK_USER
                        : isRequestToolName(tc.name()) ? ToolCapability.REQUEST_TOOL
                        : isCreateArtifactName(tc.name()) ? ToolCapability.CREATE_ARTIFACT
                        : isReadAttachmentName(tc.name()) ? ToolCapability.READ_ATTACHMENT
                        : isRunPythonName(tc.name()) ? ToolCapability.RUN_CODE
                        : ToolCapability.BUSINESS_TOOL;
                if (!policy.allows(needed)) denied.add(tc.name());
            }
        }
        return denied;
    }

    /** 越权批次整体不执行，逐 tool_call_id 返回可恢复错误，确保 delegate 收不到任何一项。 */
    private ToolExecutionResult buildPolicyDeniedResult(Prompt prompt, ChatResponse response, Set<String> denied) {
        Generation toolGen = firstGenerationWithToolCalls(response);
        if (toolGen == null || toolGen.getOutput() == null) {
            return ToolExecutionResult.builder().conversationHistory(prompt.getInstructions()).returnDirect(false).build();
        }
        AssistantMessage assistant = toolGen.getOutput();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            String text = denied.contains(tc.name())
                    ? "当前阶段未授权调用该工具，请不要重试；请在本阶段允许的能力范围内继续。"
                    : "同批次包含未授权工具，本批次未执行；请重新规划后再调用。";
            responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), text));
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistant);
        history.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
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
            if (!isAskUserName(r.name()) && !isRequestToolName(r.name()) && !isInspectResultName(r.name())
                    && !isReadAttachmentName(r.name())) return false;
        }
        return true;
    }

    private boolean isAskUserName(String name) {
        return name != null && ASK_USER_TOOL_NAME.equalsIgnoreCase(name);
    }

    private boolean isRequestToolName(String name) {
        return name != null && REQUEST_TOOL_TOOL_NAME.equalsIgnoreCase(name);
    }

    private boolean isInspectResultName(String name) {
        return name != null && ToolResultReferenceService.TOOL_NAME.equalsIgnoreCase(name);
    }

    private boolean isReadAttachmentName(String name) {
        return cn.bugstack.ai.domain.agent.service.attachment.ReadAttachmentTool.isName(name);
    }

    private boolean isRunPythonName(String name) {
        return cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService.isName(name);
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
            return executeDelegate(prompt, normalized);
        }
        AssistantMessage fullAm = toolGen.getOutput();
        List<AssistantMessage.ToolCall> calls = fullAm.getToolCalls();
        Set<String> realNames = new HashSet<>(caseMap.values());

        boolean askUserActive = userInputGate != null && userInputGate.isEnabled();
        boolean requestToolActive = requestToolAvailable();
        String sessionId = toolIdentity(prompt.getOptions() instanceof ToolCallingChatOptions options ? options : null,
                "sessionId");
        String stepLabel = currentStepLabel(prompt);

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        boolean artifactFailed = false, artifactSucceeded = false, artifactDecided = false;
        // Platform tools handled here (business tools trace themselves in MeteredToolCallback).
        Map<String, long[]> metaTimes = new HashMap<>();
        for (AssistantMessage.ToolCall tc : calls) {
            StreamingActivityTracker.checkActive(activityFor(prompt));
            long callStarted = System.currentTimeMillis();
            if (!realNames.contains(tc.name()) || isReservedMetaToolName(tc.name())) metaTimes.put(tc.id(), new long[]{callStarted, 0});
            if (askUserActive && isAskUserName(tc.name())) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), runAskUser(prompt, sessionId, tc, stepLabel)));
            } else if (requestToolActive && isRequestToolName(tc.name())) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), runRequestTool(prompt, tc, sessionId, stepLabel)));
            } else if (isInspectResultName(tc.name())) {
                Map<String, Object> scope = prompt.getOptions() instanceof ToolCallingChatOptions options
                        && options.getToolContext() != null ? options.getToolContext() : Map.of();
                RunBudgetRegistry.State budget = RunBudgetRegistry.find(scope);
                if (budget != null) budget.checkActive();
                String result = toolResultReferenceService == null
                        ? "{\"ok\":false,\"error\":\"RESULT_NOT_AVAILABLE\",\"message\":\"结果读取未启用\"}"
                        : toolResultReferenceService.inspect(tc.arguments(), scope);
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
            } else if (isReadAttachmentName(tc.name())) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        runReadAttachment(prompt, tc, sessionId, stepLabel)));
            } else if (isRunPythonName(tc.name())) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        runPython(prompt, tc, sessionId, stepLabel)));
            } else if (isCreateArtifactName(tc.name())) {
                Map<String, Object> scope = prompt.getOptions() instanceof ToolCallingChatOptions options
                        && options.getToolContext() != null ? options.getToolContext() : Map.of();
                boolean blocked = artifactRecoveryBlocked(prompt.getOptions() instanceof ToolCallingChatOptions o ? o : null);
                if (!blocked) artifactProgress(prompt, "saving", "正在保存产物", 0);
                String result = blocked ? artifactError("ARTIFACT_RECOVERY_EXHAUSTED", artifactFallbackMessage(), false, null)
                        : runArtifactService == null
                        ? "{\"ok\":false,\"message\":\"当前运行不能保存产物，请直接提供正文，不要声称已生成下载文件\"}"
                        : runArtifactService.create(tc.id(), tc.arguments(), scope);
                try {
                    var body = ASK_USER_JSON.readTree(result);
                    if (body.path("ok").asBoolean(false)) artifactSucceeded = true;
                    // A version conflict is not malformed arguments: report it, do not count a repair attempt.
                    else if (body.hasNonNull("nextAction")) artifactDecided = true;
                    else artifactFailed = true;
                } catch (Exception invalidResult) { artifactFailed = true; }
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
            } else if (!realNames.contains(tc.name())) {
                log.warn("[RobustToolMgr] unknown tool '{}' in meta-tool batch", tc.name());
                if (metrics != null) metrics.recordUnknownToolName(tc.name());
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        "工具不存在（tool not found）：" + tc.name() + "。禁止编造工具名，只能使用已注册的真实工具。"));
            } else {
                try {
                    responses.add(executeSingleToolCall(prompt, normalized, toolGen, tc));
                } catch (Exception e) {
                    StreamingActivityTracker.rethrowCancellation(e);
                    StreamingActivityTracker.checkActive(activityFor(prompt));
                    log.warn("[RobustToolMgr] tool '{}' failed in meta-tool batch: {}", tc.name(), e.getMessage());
                    responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                            "工具执行失败（tool execution failed）：" + e.getMessage()
                                    + "。请不要重复同样的无效调用，可基于已有信息回答。"));
                }
            }
            long[] time = metaTimes.get(tc.id());
            if (time != null) time[1] = System.currentTimeMillis();
        }

        if (artifactFailed) {
            boolean retryable = recordArtifactFailure(prompt);
            List<ToolResponseMessage.ToolResponse> updated = new ArrayList<>();
            for (var result : responses) {
                if (isCreateArtifactName(result.name())) {
                    try {
                        var body = ASK_USER_JSON.readTree(result.responseData());
                        if (body.isObject() && !body.path("ok").asBoolean(false)) {
                            var error = (com.fasterxml.jackson.databind.node.ObjectNode) body;
                            if (body.hasNonNull("nextAction")) {
                                // The service already knows the right next step (e.g. a version conflict).
                                updated.add(result);
                                continue;
                            }
                            error.put("retryable", retryable);
                            error.put("nextAction", retryable ? "按字段错误修正参数后再调用一次；不要改变正文事实。" : artifactFallbackMessage());
                            updated.add(new ToolResponseMessage.ToolResponse(result.id(), result.name(), error.toString()));
                            continue;
                        }
                    } catch (Exception ignored) { }
                }
                updated.add(result);
            }
            responses = updated;
        } else if (artifactSucceeded) {
            var state = ArtifactToolRecovery.find(prompt.getOptions() instanceof ToolCallingChatOptions o ? o : null);
            if (state != null) state.succeeded();
            var activity = activityFor(prompt);
            if (activity != null) activity.clearArtifactPhase();
        } else if (artifactDecided) {
            var activity = activityFor(prompt);
            if (activity != null) activity.clearArtifactPhase();
        }

        traceMetaCalls(prompt, calls, responses, metaTimes, stepLabel);
        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        conversationHistory.add(fullAm);
        conversationHistory.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());
        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(false)
                .build();
    }

    /** Run trace for platform tools: the arguments the model sent and the complete result it received. */
    private void traceMetaCalls(Prompt prompt, List<AssistantMessage.ToolCall> calls,
                                List<ToolResponseMessage.ToolResponse> responses, Map<String, long[]> times, String stepLabel) {
        if (times.isEmpty() || !cn.bugstack.ai.domain.agent.service.trace.RunTrace.enabled()) return;
        Map<String, Object> scope = prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null ? options.getToolContext() : Map.of();
        RunBudgetRegistry.State budget = RunBudgetRegistry.find(scope);
        if (budget == null) return;
        StreamingActivityTracker.Activity activity = activityFor(prompt);
        for (AssistantMessage.ToolCall call : calls) {
            long[] time = times.get(call.id());
            if (time == null) continue;
            String result = responses.stream().filter(r -> java.util.Objects.equals(r.id(), call.id()))
                    .map(ToolResponseMessage.ToolResponse::responseData).findFirst().orElse(null);
            String status = "success";
            try {
                var body = ASK_USER_JSON.readTree(result == null ? "" : result);
                if (body != null && body.isObject() && body.has("ok") && !body.path("ok").asBoolean(true)) status = "error";
            } catch (Exception plainText) { /* a text result has no status field */ }
            cn.bugstack.ai.domain.agent.service.trace.RunTrace.tool(new cn.bugstack.ai.domain.agent.service.trace.RunTrace.ToolExchange(
                    budget.runId(), budget.ownerUserId(), activity != null ? activity.callLabel() : stepLabel, stepLabel,
                    call.id(), call.name(), call.arguments(), result, result == null ? -1 : result.length(), status, null,
                    time[0], Math.max(0, (time[1] == 0 ? System.currentTimeMillis() : time[1]) - time[0]), "META"));
        }
    }

    /**
     * request_tool 命中：按 need 语义匹配装载工具。
     * <ol>
     *   <li>预算硬限：数 prompt 历史里已有 request_tool 次数，超 {@link #requestToolMaxCalls} 直接拒绝（无状态）；</li>
     *   <li>{@link cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService#resolveDynamicToolCallbacks} 物化真实回调（带 inputSchema）；</li>
     *   <li>当前 profile 允许 BUSINESS_TOOL 时才由 {@link #injectIntoOptions} 并入当前 options；DISCOVERY_ONLY 只登记后续；</li>
     *   <li>{@code appendNeed(sessionId, need)} 写会话级 store → 后续 step 的 resolve 也带上。</li>
     * </ol>
     * 返回给模型的文本按 profile 区分“当前可直接调用”与“仅供后续执行阶段”。
     */
    private String runRequestTool(Prompt prompt, AssistantMessage.ToolCall tc, String sessionId, String stepLabel) {
        StreamingActivityTracker.Activity activity = activityFor(prompt);
        try (StreamingActivityTracker.Scope ignored = activity == null ? () -> {} : activity.executingTool(REQUEST_TOOL_TOOL_NAME)) {
            return runActiveRequestTool(prompt, tc, sessionId, stepLabel);
        }
    }

    private String runActiveRequestTool(Prompt prompt, AssistantMessage.ToolCall tc, String sessionId, String stepLabel) {
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
        String priorUser = org.slf4j.MDC.get("userId");
        String priorAgent = org.slf4j.MDC.get("agentId");
        String priorRun = org.slf4j.MDC.get("runId");
        String priorAgentRun = org.slf4j.MDC.get("agent.run_id");
        try {
            if (prompt.getOptions() instanceof ToolCallingChatOptions scopedOptions
                    && scopedOptions.getToolContext() != null) {
                Object owner = scopedOptions.getToolContext().get("userId");
                if (owner != null && !owner.toString().isBlank()) org.slf4j.MDC.put("userId", owner.toString());
                Object agent = scopedOptions.getToolContext().get("agentId");
                if (agent != null && !agent.toString().isBlank()) org.slf4j.MDC.put("agentId", agent.toString());
                // Embedding uses a synchronous HTTP interceptor, so explicitly bridge the
                // trusted per-run ToolContext over the Reactor hop. Never mint another budget.
                RunBudgetRegistry.State activeBudget = RunBudgetRegistry.find(scopedOptions.getToolContext());
                if (activeBudget != null) {
                    activeBudget.checkActive();
                    org.slf4j.MDC.put("runId", activeBudget.runId());
                    org.slf4j.MDC.put("agent.run_id", activeBudget.runId());
                } else {
                    // An unknown explicit run must not borrow a worker thread's previous run.
                    org.slf4j.MDC.remove("runId");
                    org.slf4j.MDC.remove("agent.run_id");
                }
            }
            // needs 多条换行连接；resolveDynamicToolCallbacks 内部 splitNeeds 拆开、各取 top-k 再并集。
            // currentTools 传空：去重在 injectIntoOptions 按工具名统一做；clientId/query 仅供匹配器日志。
            resolved = mcpToolCatalogService.resolveDynamicToolCallbacks(currentRunId(prompt), sessionId,
                    workspaceNodePolicy==null ? REQUEST_TOOL_TOOL_NAME : workspaceNodePolicy.clientId(), needs, needs, java.util.List.of());
        } catch (Exception e) {
            StreamingActivityTracker.rethrowCancellation(e);
            StreamingActivityTracker.checkActive(activityFor(prompt));
            log.warn("[RequestTool] resolve failed needs='{}': {}", needs, e.getMessage());
            return requestToolEnd(sessionId, stepLabel, "error", "匹配出错：" + e.getMessage(),
                    "工具匹配出错：" + e.getMessage() + "。可换一种能力描述再试，或基于现有工具完成任务。");
        } finally {
            if (priorUser == null) org.slf4j.MDC.remove("userId");
            else org.slf4j.MDC.put("userId", priorUser);
            if (priorAgent == null) org.slf4j.MDC.remove("agentId");
            else org.slf4j.MDC.put("agentId", priorAgent);
            if (priorRun == null) org.slf4j.MDC.remove("runId");
            else org.slf4j.MDC.put("runId", priorRun);
            if (priorAgentRun == null) org.slf4j.MDC.remove("agent.run_id");
            else org.slf4j.MDC.put("agent.run_id", priorAgentRun);
        }
        StreamingActivityTracker.checkActive(activityFor(prompt));
        if (resolved == null || resolved.isEmpty()) {
            return requestToolEnd(sessionId, stepLabel, "error", "未匹配到「" + needs.replace("\n", " / ") + "」对应的工具",
                    "没有匹配到「" + needs.replace("\n", " / ") + "」对应的可用 MCP 工具，未装载任何工具。"
                            + "request_tool 只搜索 MCP 目录，不负责装载询问用户或生成产物等内置能力。"
                            + "请先检查当前阶段已提供的工具；若已有 ask_user、create_artifact 或表格产物工具，应按其定义直接使用。"
                            + "当前阶段未提供的能力不能调用，可留给后续执行阶段；仍无可用能力时明确说明限制，不要用无关工具替代。");
        }
        ToolCapabilityProfile policy = effectivePolicy(prompt);
        boolean businessAllowedNow = policy.allows(ToolCapability.BUSINESS_TOOL);
        int added = businessAllowedNow ? injectIntoOptions(prompt, resolved) : 0;
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
        String displayNames = ToolCapabilitySummary.boundedNames(names);
        String modelText = businessAllowedNow
                ? "已为你装载工具：" + displayNames + "。现在可以直接调用上述工具完成任务（同样的能力不要再次 request_tool）。"
                : "已登记并为后续执行阶段准备工具：" + displayNames
                    + "。当前阶段没有业务工具执行权限，请继续完成本阶段职责，不要在本阶段调用上述工具。";
        modelText += ToolCapabilitySummary.render(resolved);
        return requestToolEnd(sessionId, stepLabel, "success", String.join("\n", names), modelText);
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
        if (workspaceNodePolicy!=null) resolved=workspaceNodePolicy.filter(resolved);
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
     * 仅接受完整 JSON 对象。兼容旧版 need/capability 字符串，但不把异常参数当搜索词。
     */
    private String parseRequestToolNeeds(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode obj = ASK_USER_JSON.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(argsJson);
            if (obj != null && obj.isObject()) {
                com.fasterxml.jackson.databind.JsonNode arr = obj.get("needs");
                if (arr != null) {
                    if (!arr.isArray() || arr.isEmpty() || arr.size() > 12) return null;
                    List<String> lines = new ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isTextual()) return null;
                        String s = arr.get(i).asText().trim();
                        if (s.isBlank() || s.length() > 2000) return null;
                        lines.add(s);
                    }
                    if (!lines.isEmpty()) return String.join("\n", lines);
                }
                var legacy = obj.has("need") ? obj.get("need") : obj.get("capability");
                if (legacy != null && legacy.isTextual() && !legacy.asText().isBlank()
                        && legacy.asText().length() <= 2000) return legacy.asText().trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 调 gate 阻塞提问，把状态翻译成给 LLM 的工具结果文本；同时发 ask_user 元工具观察卡片。 */
    /** read_attachment：只读，由本 manager 执行；归属只取请求自带的 ToolContext，不信任模型参数。 */
    private String runReadAttachment(Prompt prompt, AssistantMessage.ToolCall tc, String sessionId, String stepLabel) {
        Map<String, Object> scope = prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null ? options.getToolContext() : Map.of();
        RunBudgetRegistry.State budget = RunBudgetRegistry.find(scope);
        if (budget != null) budget.checkActive();
        if (chatFileService == null || runAttachments == null || !runAttachments.readable(scope)) {
            return "{\"ok\":false,\"error\":\"ATTACHMENT_NOT_AVAILABLE\",\"message\":\"本次任务没有可读取的附件\"}";
        }
        if (countPriorResponses(prompt, this::isReadAttachmentName) >= MAX_ATTACHMENT_READS_PER_CALL) {
            return "{\"ok\":false,\"error\":\"ATTACHMENT_READ_LIMIT\",\"message\":\"本步骤读取附件的次数已达上限，"
                    + "请基于已经读到的内容完成任务，并告诉用户哪些部分没有读取\"}";
        }
        ToolCallProgressEmitter progress = this.toolCallProgressEmitter;
        long started = System.currentTimeMillis();
        String tool = cn.bugstack.ai.domain.agent.service.attachment.ReadAttachmentTool.TOOL_NAME;
        if (progress != null) progress.emitStart(sessionId, tool, tc.arguments(), stepLabel, tc.id());
        String result;
        try {
            result = chatFileService.read(scope, tc.arguments());
        } catch (RuntimeException failure) {
            StreamingActivityTracker.rethrowCancellation(failure);
            if (failure instanceof RunBudgetExceededException) throw failure;
            log.warn("[RobustToolMgr] read_attachment failed: {}", failure.toString());
            result = "{\"ok\":false,\"error\":\"ATTACHMENT_READ_FAILED\",\"message\":\"附件暂时无法读取，请稍后重试；仍失败时如实告诉用户\"}";
        }
        boolean ok;
        try { ok = ASK_USER_JSON.readTree(result).path("ok").asBoolean(false); }
        catch (Exception invalid) { ok = false; }
        if (progress != null) progress.emitEnd(sessionId, tool, ok ? "success" : "error",
                System.currentTimeMillis() - started, result.length(), stepLabel, tc.id());
        ToolCallLedger ledger = this.toolCallLedger;
        if (ledger != null && Boolean.TRUE.equals(scope.get(ToolCallLedger.CTX_ENABLED_KEY))
                && scope.get("agent.run_id") instanceof String runId && !runId.isBlank()) {
            ledger.record(runId, tool, ok ? ToolCallLedger.STATUS_SUCCESS : "error", result.length(), result);
        }
        return result;
    }

    /** run_python：计入本次任务的工具调用预算；排队位置和运行时长通过 tool_call_progress 推给页面。 */
    private String runPython(Prompt prompt, AssistantMessage.ToolCall tc, String sessionId, String stepLabel) {
        Map<String, Object> scope = prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null ? options.getToolContext() : Map.of();
        if (!codeExecutionAllowed()) {
            return "{\"ok\":false,\"error\":\"SANDBOX_UNAVAILABLE\",\"message\":\"当前不能运行代码"
                    + "（沙箱未启用或本节点关闭了代码运行），请改用其他方式完成或如实告诉用户\"}";
        }
        RunBudgetRegistry.State budget = RunBudgetRegistry.find(scope);
        if (budget != null) budget.reserveTool();
        String tool = cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService.TOOL_NAME;
        ToolCallProgressEmitter progress = this.toolCallProgressEmitter;
        long started = System.currentTimeMillis();
        if (progress != null) progress.emitStart(sessionId, tool, tc.arguments(), stepLabel, tc.id());
        long[] lastRunningReport = {Long.MIN_VALUE};
        int[] lastQueued = {-1};
        cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService.Progress status =
                new cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService.Progress() {
                    @Override public void queued(int ahead) {
                        if (progress == null || ahead == lastQueued[0]) return;
                        lastQueued[0] = ahead;
                        progress.emitProgress(sessionId, tool, tc.id(), ahead > 0 ? "排队中，前面还有 " + ahead + " 个" : "排队中，马上开始");
                    }
                    @Override public void running(long elapsedMs) {
                        long seconds = elapsedMs / 1000;
                        if (progress == null || seconds - lastRunningReport[0] < 5 && lastRunningReport[0] != Long.MIN_VALUE) return;
                        lastRunningReport[0] = seconds;
                        progress.emitProgress(sessionId, tool, tc.id(), "运行中 " + seconds + " 秒");
                    }
                };
        String result;
        try {
            result = sandboxService.run(tc.id(), tc.arguments(), scope, status);
        } catch (RuntimeException failure) {
            StreamingActivityTracker.rethrowCancellation(failure);
            if (failure instanceof RunBudgetExceededException) throw failure;
            log.warn("[RobustToolMgr] run_python failed: {}", failure.toString());
            result = "{\"ok\":false,\"error\":\"SANDBOX_FAILED\",\"message\":\"代码运行服务暂时不可用，请稍后重试；仍失败时如实告诉用户\"}";
        }
        boolean ok;
        try { ok = ASK_USER_JSON.readTree(result).path("ok").asBoolean(false); }
        catch (Exception invalid) { ok = false; }
        if (progress != null) progress.emitEnd(sessionId, tool, ok ? "success" : "error",
                System.currentTimeMillis() - started, result.length(), stepLabel, tc.id());
        ToolCallLedger ledger = this.toolCallLedger;
        if (ledger != null && Boolean.TRUE.equals(scope.get(ToolCallLedger.CTX_ENABLED_KEY))
                && scope.get("agent.run_id") instanceof String runId && !runId.isBlank()) {
            ledger.record(runId, tool, ok ? ToolCallLedger.STATUS_SUCCESS : "error", result.length(), result);
        }
        return result;
    }

    private int countPriorResponses(Prompt prompt, java.util.function.Predicate<String> nameMatch) {
        int count = 0;
        if (prompt == null || prompt.getInstructions() == null) return 0;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage trm && trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
                    if (nameMatch.test(response.name())) count++;
                }
            }
        }
        return count;
    }

    private String runAskUser(Prompt prompt, String sessionId, AssistantMessage.ToolCall tc, String stepLabel) {
        ToolCallProgressEmitter p = this.toolCallProgressEmitter;
        if (p != null) {
            p.emitMetaStart(sessionId, ASK_USER_TOOL_NAME, askUserPreview(tc.arguments()), stepLabel);
        }
        StreamingActivityTracker.Activity activity = activityFor(prompt);
        cn.bugstack.ai.domain.agent.service.security.UserInputGate.Result r;
        try (StreamingActivityTracker.Scope ignored = activity == null ? () -> {} :
                activity.awaitingUser(java.time.Duration.ofSeconds(Math.max(1, userInputGate.getTimeoutSeconds()) + 5L))) {
            r = userInputGate.requestUserInput(sessionId, tc.arguments(), stepLabel, currentRunId(prompt),
                    toolIdentity(prompt.getOptions() instanceof ToolCallingChatOptions options ? options : null, "userId"));
        }
        StreamingActivityTracker.checkActive(activity);
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

    /** 解析 ask_user 入参里的 context + questions，兼容旧字符串问题和结构化问题，拼成卡片预览。 */
    private String askUserPreview(String argsJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode o = ASK_USER_JSON.readTree(argsJson);
            if (o != null && o.isObject()) {
                StringBuilder sb = new StringBuilder();
                String ctx = o.hasNonNull("context") ? o.get("context").asText() : null;
                if (ctx != null && !ctx.isBlank()) sb.append(ctx.trim()).append("\n");
                com.fasterxml.jackson.databind.JsonNode qs = AskUserQuestionNormalizer.normalize(o.get("questions"));
                if (qs != null && !qs.isArray() && (qs.isTextual() || qs.isObject())) {
                    qs = ASK_USER_JSON.createArrayNode().add(qs);
                }
                if (qs != null && qs.isArray()) {
                    for (int i = 0; i < qs.size(); i++) {
                        com.fasterxml.jackson.databind.JsonNode rawQuestion = qs.get(i);
                        String question = null;
                        com.fasterxml.jackson.databind.JsonNode options = null;
                        if (rawQuestion.isTextual()) {
                            question = rawQuestion.asText();
                        } else if (rawQuestion.isObject()) {
                            question = rawQuestion.hasNonNull("question") ? rawQuestion.get("question").asText() : null;
                            options = rawQuestion.get("options");
                        }
                        if (question == null || question.isBlank()) continue;
                        sb.append(i + 1).append(". ").append(question.trim());
                        if (options != null && options.isArray() && !options.isEmpty()) {
                            List<String> labels = new ArrayList<>();
                            for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                                com.fasterxml.jackson.databind.JsonNode rawOption = options.get(optionIndex);
                                if (rawOption.isTextual() && !rawOption.asText().isBlank()) {
                                    labels.add(rawOption.asText().trim());
                                } else if (rawOption.isObject()) {
                                    String label = rawOption.hasNonNull("label") ? rawOption.get("label").asText() : null;
                                    if (label != null && !label.isBlank()) labels.add(label.trim());
                                }
                            }
                            if (!labels.isEmpty()) sb.append(" [").append(String.join(" / ", labels)).append("]");
                        }
                        sb.append("\n");
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

    /** 取本轮 runId：优先 ToolContext（跨 Reactor 线程稳定），退 MDC。 */
    private String currentRunId(Prompt prompt) {
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions tco && tco.getToolContext() != null) {
            Object v = tco.getToolContext().get("agent.run_id");
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        String fromMdc = org.slf4j.MDC.get("agent.run_id");
        return fromMdc != null && !fromMdc.isBlank() ? fromMdc : null;
    }

    private String toolIdentity(ToolCallingChatOptions options, String key) {
        if (options != null && options.getToolContext() != null && options.getToolContext().containsKey(key)) {
            Object value = options.getToolContext().get(key);
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        }
        return org.slf4j.MDC.get(key);
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
            groups.computeIfAbsent(resolveMcpGroupKey(prompt, calls.get(i).name()), k -> new ArrayList<>()).add(i);
        }

        // 全在同一个 MCP server（或无法识别 mcpId）→ 没有可安全并行的，退回 delegate 原生串行（零风险、零自定义合并）。
        if (groups.size() <= 1) {
            log.debug("[RobustToolMgr] {} tool calls in one MCP group, executing serially via delegate", calls.size());
            return executeDelegate(prompt, normalized);
        }

        // 组间并行：每个 group 一个 task，task 内部按原顺序串行跑该连接的工具；结果写回原始 index 槽位。
        ToolResponseMessage.ToolResponse[] ordered = new ToolResponseMessage.ToolResponse[calls.size()];
        List<CompletableFuture<Void>> futures = new ArrayList<>(groups.size());
        StreamingActivityTracker.Activity activity = activityFor(prompt);
        for (List<Integer> indices : groups.values()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try (StreamingActivityTracker.Scope ignored = StreamingActivityTracker.scope(activity)) {
                    for (int idx : indices) {
                        StreamingActivityTracker.checkActive(activity);
                        AssistantMessage.ToolCall tc = calls.get(idx);
                        try {
                            ordered[idx] = executeSingleToolCall(prompt, normalized, toolGen, tc);
                        } catch (Exception e) {
                            StreamingActivityTracker.rethrowCancellation(e);
                            StreamingActivityTracker.checkActive(activity);
                            log.warn("[RobustToolMgr] tool '{}' failed: {}", tc.name(), e.getMessage());
                            ordered[idx] = new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(), "工具执行失败（tool execution failed）：" + e.getMessage()
                                    + "。请不要重复同样的无效调用，可基于已有信息回答，或改用其它真实可用工具。");
                        }
                    }
                }
            }, toolExecutor));
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (Exception e) {
                if (activity != null && activity.isCancelled()) {
                    futures.forEach(future -> future.cancel(true));
                }
                StreamingActivityTracker.rethrowCancellation(e);
                StreamingActivityTracker.checkActive(activity);
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

    /** Resolve only from this request's authorized callbacks, never the global tool-name index. */
    private String resolveMcpGroupKey(Prompt prompt, String toolName) {
        if (toolName != null && prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            String selected = null;
            for (ToolCallback callback : options.getToolCallbacks()) {
                if (callback.getToolDefinition() == null || !toolName.equals(callback.getToolDefinition().name())) continue;
                String mcpId = callback.getToolMetadata() instanceof McpToolCallMetadata metadata ? metadata.mcpId()
                        : callback instanceof MeteredToolCallback metered ? metered.getMcpId() : null;
                if (mcpId == null || mcpId.isBlank()) return "__no_mcp_id__";
                if (selected != null && !selected.equals(mcpId)) return "__no_mcp_id__";
                selected = mcpId;
            }
            if (selected != null) return selected;
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

        ToolExecutionResult result = executeDelegate(prompt, single);
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
        Map<String, ToolCallback> owners = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions tco) {
            List<ToolCallback> callbacks = tco.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback cb : callbacks) {
                    if (cb == null || cb.getToolDefinition() == null) continue;
                    String name = cb.getToolDefinition().name();
                    if (name == null || name.isBlank() || isReservedMetaToolName(name)) continue;
                    for (String alias : new String[]{name, McpClientRegistry.originalToolName(cb)}) {
                        if (alias == null || alias.isBlank() || isReservedMetaToolName(alias)) continue;
                        String key = alias.toLowerCase(java.util.Locale.ROOT);
                        if (ambiguous.contains(key)) continue;
                        ToolCallback previous = owners.putIfAbsent(key, cb);
                        if (previous != null && previous != cb) {
                            ambiguous.add(key);
                            map.remove(key);
                        } else {
                            map.put(key, name);
                        }
                    }
                }
            }
        }
        // An original alias must not shadow another callback's canonical name. Remove every
        // route to an ambiguous canonical name, including routes through an otherwise unique alias.
        map.entrySet().removeIf(entry -> ambiguous.contains(entry.getValue().toLowerCase(java.util.Locale.ROOT)));
        return map;
    }

    private boolean isReservedMetaToolName(String name) {
        return isAskUserName(name) || isRequestToolName(name) || isInspectResultName(name) || isCreateArtifactName(name)
                || isReadAttachmentName(name) || isRunPythonName(name);
    }

    private boolean isCreateArtifactName(String name) { return "create_artifact".equalsIgnoreCase(name); }

    private boolean artifactRecoveryBlocked(ToolCallingChatOptions options) {
        var state = ArtifactToolRecovery.find(options);
        return state != null && state.blocked();
    }

    private String artifactFallbackMessage() {
        return "本次产物参数修复仍未成功，停止调用 create_artifact，直接输出完整正文或代码，并明确说明文件未保存。不得声称保存成功或编造下载链接。";
    }

    private boolean recordArtifactFailure(Prompt prompt) {
        int attempt = 2;
        boolean retryable = false;
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            var recovery = ArtifactToolRecovery.get(options);
            attempt = recovery.failed();
            retryable = !recovery.blocked();
        }
        artifactProgress(prompt, retryable ? "repairing" : "failed",
                retryable ? "产物参数格式有误，正在修复后保存" : "产物未保存，正在保留回答内容", attempt);
        return retryable;
    }

    private void artifactProgress(Prompt prompt, String phase, String message, int attempt) {
        var activity = activityFor(prompt);
        if (activity != null) activity.markArtifactPhase("artifact_" + switch (phase) {
            case "saving" -> "saving";
            case "repairing" -> "repairing";
            default -> "fallback";
        });
        if (toolCallProgressEmitter != null && prompt.getOptions() instanceof ToolCallingChatOptions options)
            toolCallProgressEmitter.emitArtifactProgress(options.getToolContext(), phase, message, attempt);
    }

    private String artifactError(String code, String message, boolean retryable, com.fasterxml.jackson.core.JsonLocation location) {
        var error = ASK_USER_JSON.createObjectNode().put("ok", false).put("error", code).put("message", message).put("retryable", retryable);
        if (location != null) { error.put("line", location.getLineNr()); error.put("column", location.getColumnNr()); }
        error.put("expected", "content 必须是对象。MARKDOWN: {\"markdown\":\"正文\"}；TABLE: {\"columns\":[\"列名\"],\"rows\":[[\"值\"]]}；CODE: {\"filename\":\"main.py\",\"language\":\"python\",\"code\":\"print(1)\\n\"}。字符串用双引号，正文换行/引号须 JSON 转义。");
        return error.toString();
    }

    /** A provider ignoring the removed definition must not restart an unbounded tool loop. */
    private ToolExecutionResult artifactUnavailableResult(Prompt prompt, ChatResponse response) {
        var original = firstGenerationWithToolCalls(response).getOutput();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (var call : original.getToolCalls()) {
            calls.add(new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), "{}"));
            results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    "文件未保存：模型连续返回无法解析的产物参数，自动修复未成功。本次停止重复生成；已有任务结果仍保留。"));
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(AssistantMessage.builder().content(original.getText()).properties(original.getMetadata()).toolCalls(calls).build());
        history.add(ToolResponseMessage.builder().responses(results).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(true).build();
    }

    /** Never execute a partial argument batch or resend invalid JSON in assistant tool_calls. */
    private ToolExecutionResult rejectMalformedArguments(Prompt prompt, ChatResponse response) {
        Generation generation = firstGenerationWithToolCalls(response);
        if (generation == null || generation.getOutput() == null) return null;
        AssistantMessage original = generation.getOutput();
        Set<String> invalid = new HashSet<>();
        Map<String, com.fasterxml.jackson.core.JsonLocation> locations = new HashMap<>();
        for (var call : original.getToolCalls()) {
            try {
                var json = ASK_USER_JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(call.arguments());
                if (json == null || !json.isObject()) invalid.add(call.id());
            } catch (Exception malformed) {
                invalid.add(call.id());
                if (malformed instanceof com.fasterxml.jackson.core.JsonProcessingException jsonError)
                    locations.put(call.id(), jsonError.getLocation());
                log.warn("[ToolArguments] invalid tool={} chars={} errorType={}", call.name(),
                        call.arguments() == null ? 0 : call.arguments().length(), malformed.getClass().getSimpleName());
            }
        }
        if (invalid.isEmpty()) return null;
        boolean artifactInvalid = original.getToolCalls().stream().anyMatch(c -> invalid.contains(c.id()) && isCreateArtifactName(c.name()));
        boolean artifactRetryable = !artifactInvalid || recordArtifactFailure(prompt);
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (var call : original.getToolCalls()) {
            calls.add(invalid.contains(call.id())
                    ? new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), "{}") : call);
            String result;
            if (invalid.contains(call.id()) && isCreateArtifactName(call.name())) {
                result = artifactError("INVALID_TOOL_ARGUMENTS", artifactRetryable
                        ? "参数必须为单个合法 JSON 对象，本批未执行。请按 expected 修正参数后再调用一次；不要把 Markdown 或代码直接写在 content: 后。"
                        : artifactFallbackMessage(), artifactRetryable, locations.get(call.id()));
            } else result = invalid.contains(call.id())
                    ? "{\"ok\":false,\"error\":\"INVALID_TOOL_ARGUMENTS\",\"message\":\"工具参数不是完整 JSON 对象，本批工具均未执行。请按工具定义重新提供完整参数。\"}"
                    : "{\"ok\":false,\"error\":\"BATCH_NOT_EXECUTED\",\"message\":\"同批工具含不完整参数，本批均未执行。\"}";
            results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(AssistantMessage.builder().content(original.getText()).properties(original.getMetadata())
                .media(original.getMedia()).toolCalls(calls).build());
        history.add(ToolResponseMessage.builder().responses(results).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
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
                if (origName == null || isReservedMetaToolName(origName)) {
                    // 已经是真实 name，原样保留
                    normCalls.add(tc);
                    continue;
                }
                String mapped = caseMap.get(origName.toLowerCase(java.util.Locale.ROOT));
                if (origName.equals(mapped)) {
                    normCalls.add(tc);
                } else if (mapped != null) {
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
