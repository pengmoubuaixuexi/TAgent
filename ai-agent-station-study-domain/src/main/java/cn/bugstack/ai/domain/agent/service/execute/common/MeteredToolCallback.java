package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.security.HumanApprovalGate;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool callback decorator for MCP metrics, logging and guardrails.
 */
@Slf4j
public class MeteredToolCallback implements ToolCallback, McpClientRegistry.OriginalToolName {

    /** Server-authored request scope used by the isolated node debugger. */
    public static final String DEBUG_READ_ONLY_KEY = "agent.debug_read_only";

    private static final int GITHUB_SEARCH_DEFAULT_PAGE = 1;
    private static final int DEFAULT_GITHUB_SEARCH_MAX_PER_PAGE = 10;
    private static final int DEFAULT_GITHUB_SEARCH_MAX_RESULT_CHARS = 20_000;
    private static final int GITHUB_SEARCH_MAX_DESCRIPTION_CHARS = 320;
    private static final String SESSION_REJECTION_PREFIX = "Sending message failed with a non-OK HTTP code: 400 - ";
    private static final String SESSION_REJECTION_MESSAGE = "Session ID not found, Please reconnect";

    private final AtomicReference<ToolCallback> delegate;
    /**
     * T10 修复：构造时缓存（含 hint 的）ToolDefinition。
     * <p>运行期 delegate 会被 {@link #refreshDelegateFromRegistry} / 重连切换成 registry 里的 raw callback，
     * 而 T10 的 prompt hint 只活在 description 里。若 {@link #getToolDefinition()} 实时读 delegate，
     * 切到 raw 后 hint 就丢了（LLM 之后看不到避坑提示，又开始填错参数）。工具 name/inputSchema/hint
     * 跨重连都稳定，故构造时缓存一次、恒返回它，让「LLM 看到的 schema（稳定含 hint）」与
     * 「执行用哪条连接（可切换）」解耦。
     */
    private final ToolDefinition cachedDefinition;
    private final String originalToolName;
    private final McpToolMetrics metrics;
    private final boolean returnErrorOnFailure;
    private final boolean githubWriteEnabled;
    private final int githubSearchMaxPerPage;
    private final int githubSearchMaxResultChars;
    private final boolean githubSearchCompactResultEnabled;
    private final boolean aiSearchStripServerLlm;
    private final int mcpToolCallMaxAttempts;
    private final long mcpToolCallRetryDelayMs;
    private final McpClientRegistry registry;
    private final String mcpId;
    public String getMcpId() { return mcpId; }

    @Override
    public String getOriginalToolName() {
        return McpClientRegistry.originalToolName(delegate.get());
    }

    /**
     * G1-C：Human Approval Gate（可选）。用 setter 注入避免改 6 层构造函数链。
     * <p>
     * 为 null → no-op 放行；非 null 才在 {@link #invoke} 前调 {@code requestApproval}。
     * 装配点（AiClientModelNode / AiClientNode）在 new 实例后调 {@link #setHumanApprovalGate} 注入。
     */
    private volatile HumanApprovalGate humanApprovalGate;

    public void setHumanApprovalGate(HumanApprovalGate gate) {
        this.humanApprovalGate = gate;
    }

    /**
     * H3-A：流式工具调用进度 emitter（可选）。setter 注入同 humanApprovalGate 模式。
     * 为 null → 不 emit 进度事件，工具调用行为完全不变。
     */
    private volatile ToolCallProgressEmitter toolCallProgressEmitter;

    public void setToolCallProgressEmitter(ToolCallProgressEmitter emitter) {
        this.toolCallProgressEmitter = emitter;
    }

    /**
     * P0（Codex #2）工具调用实证台账（可选）。setter 注入同 humanApprovalGate 模式。
     * 为 null → 不记账，工具调用行为完全不变。仅用于让 Step3 质检/Step4 汇总看到"本轮真实调了什么、返回了什么"，
     * 消除"查无工具记录→反咬编造"的误判。
     */
    private volatile ToolCallLedger toolCallLedger;

    public void setToolCallLedger(ToolCallLedger ledger) {
        this.toolCallLedger = ledger;
    }

    private volatile ToolResultReferenceService toolResultReferenceService;

    public void setToolResultReferenceService(ToolResultReferenceService service) {
        this.toolResultReferenceService = service;
    }

    /** H3-A：工具调用进度 SSE 的 status 枚举。 */
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_APPROVAL_DENIED = "approval_denied";
    private static final String STATUS_APPROVAL_TIMEOUT = "approval_timeout";
    private static final String STATUS_APPROVAL_UNAVAILABLE = "approval_unavailable";
    private static final String STATUS_ERROR = "error";

    private static String approvalDecisionToStatus(HumanApprovalGate.Decision decision) {
        return switch (decision) {
            case REJECTED -> STATUS_APPROVAL_DENIED;
            case TIMEOUT -> STATUS_APPROVAL_TIMEOUT;
            case APPROVAL_UNAVAILABLE -> STATUS_APPROVAL_UNAVAILABLE;
            default -> STATUS_SUCCESS;
        };
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics) {
        this(delegate, metrics, false);
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics, boolean returnErrorOnFailure) {
        this(delegate, metrics, returnErrorOnFailure, false);
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics,
                               boolean returnErrorOnFailure, boolean githubWriteEnabled) {
        this(delegate, metrics, returnErrorOnFailure, githubWriteEnabled,
                DEFAULT_GITHUB_SEARCH_MAX_PER_PAGE, DEFAULT_GITHUB_SEARCH_MAX_RESULT_CHARS, false);
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics,
                               boolean returnErrorOnFailure, boolean githubWriteEnabled,
                               int githubSearchMaxPerPage, int githubSearchMaxResultChars,
                               boolean githubSearchCompactResultEnabled) {
        this(delegate, metrics, returnErrorOnFailure, githubWriteEnabled,
                githubSearchMaxPerPage, githubSearchMaxResultChars, githubSearchCompactResultEnabled, true);
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics,
                               boolean returnErrorOnFailure, boolean githubWriteEnabled,
                               int githubSearchMaxPerPage, int githubSearchMaxResultChars,
                               boolean githubSearchCompactResultEnabled,
                               boolean aiSearchStripServerLlm) {
        this(delegate, metrics, returnErrorOnFailure, githubWriteEnabled,
                githubSearchMaxPerPage, githubSearchMaxResultChars, githubSearchCompactResultEnabled,
                aiSearchStripServerLlm, 2, 1000);
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics,
                               boolean returnErrorOnFailure, boolean githubWriteEnabled,
                               int githubSearchMaxPerPage, int githubSearchMaxResultChars,
                               boolean githubSearchCompactResultEnabled,
                               boolean aiSearchStripServerLlm,
                               int mcpToolCallMaxAttempts, long mcpToolCallRetryDelayMs) {
        this(delegate, metrics, returnErrorOnFailure, githubWriteEnabled,
                githubSearchMaxPerPage, githubSearchMaxResultChars, githubSearchCompactResultEnabled,
                aiSearchStripServerLlm, mcpToolCallMaxAttempts, mcpToolCallRetryDelayMs, null, null);
    }

    public MeteredToolCallback(ToolCallback delegate, McpToolMetrics metrics,
                               boolean returnErrorOnFailure, boolean githubWriteEnabled,
                               int githubSearchMaxPerPage, int githubSearchMaxResultChars,
                               boolean githubSearchCompactResultEnabled,
                               boolean aiSearchStripServerLlm,
                               int mcpToolCallMaxAttempts, long mcpToolCallRetryDelayMs,
                               McpClientRegistry registry, String mcpId) {
        this.delegate = new AtomicReference<>(delegate);
        this.cachedDefinition = captureDefinition(delegate);
        this.originalToolName = McpClientRegistry.originalToolName(delegate);
        this.metrics = metrics;
        this.returnErrorOnFailure = returnErrorOnFailure;
        this.githubWriteEnabled = githubWriteEnabled;
        this.githubSearchMaxPerPage = githubSearchMaxPerPage > 0 ? githubSearchMaxPerPage : DEFAULT_GITHUB_SEARCH_MAX_PER_PAGE;
        this.githubSearchMaxResultChars = githubSearchMaxResultChars > 0 ? githubSearchMaxResultChars : DEFAULT_GITHUB_SEARCH_MAX_RESULT_CHARS;
        this.githubSearchCompactResultEnabled = githubSearchCompactResultEnabled;
        this.aiSearchStripServerLlm = aiSearchStripServerLlm;
        this.mcpToolCallMaxAttempts = mcpToolCallMaxAttempts > 0 ? mcpToolCallMaxAttempts : 2;
        this.mcpToolCallRetryDelayMs = mcpToolCallRetryDelayMs > 0 ? mcpToolCallRetryDelayMs : 1000;
        this.registry = registry;
        this.mcpId = mcpId;
    }

    /** 恒返回构造时缓存的（含 hint）定义；delegate 切到 raw 不影响 LLM 看到的 description（见 {@link #cachedDefinition}）。 */
    @Override
    public ToolDefinition getToolDefinition() {
        return cachedDefinition != null ? cachedDefinition : delegate.get().getToolDefinition();
    }

    private static ToolDefinition captureDefinition(ToolCallback delegate) {
        try {
            return delegate != null ? delegate.getToolDefinition() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public ToolMetadata getToolMetadata() {
        ToolMetadata metadata = delegate.get().getToolMetadata();
        if (mcpId == null || mcpId.isBlank()) return metadata;
        if (metadata instanceof McpToolCallMetadata mcp) {
            return new McpToolCallMetadata(mcpId, mcp.readOnlyHint(), mcp.destructiveHint(), mcp.idempotentHint(), mcp.returnDirect());
        }
        return McpToolCallMetadata.from(mcpId, null, metadata.returnDirect());
    }

    @Override
    public String call(String toolInput) {
        StreamingActivityTracker.checkActive(StreamingActivityTracker.current());
        if (registry != null && mcpId != null) registry.assertAccessible(mcpId, MDC.get("userId"), MDC.get("agentId"));
        try (McpToolMetrics.Scope ignored = metrics.scope(MDC.get("userId"), mcpId)) {
            String name = safeName();
            String effectiveInput = normalizeToolInput(name, toolInput);
            return invoke(name, toolInput, effectiveInput, resolveSessionId(null), resolveStepLabel(null),
                    null, java.util.Map.of(), RunBudgetRegistry.find(java.util.Map.of()), target -> target.call(effectiveInput));
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.from(
                toolContext == null ? null : toolContext.getContext());
        StreamingActivityTracker.checkActive(activity);
        String userId = readContext(toolContext, "userId");
        if (userId == null) userId = MDC.get("userId");
        String agentId = readContext(toolContext, "agentId");
        if (agentId == null) agentId = MDC.get("agentId");
        if (registry != null && mcpId != null) registry.assertAccessible(mcpId, userId, agentId);
        // ToolContext is populated by the server, not model arguments. It survives Reactor thread hops.
        try (StreamingActivityTracker.Scope attemptScope = StreamingActivityTracker.scope(activity);
             McpToolMetrics.Scope ignored = metrics.scope(userId != null ? userId : MDC.get("userId"), mcpId)) {
            String name = safeName();
            String effectiveInput = normalizeToolInput(name, toolInput);
            // 工具调用事实摘要台账：仅当 ToolContext 带开关 agent.tool_ledger_enabled=true（只有 Auto 质检路径注入）时才记账，
            // 且需拿到 buildToolContext 注入的 agent.run_id（按 runId 隔离）。Fixed/Flow 不注入开关 → 不记 → 不会"只记不清"。
            boolean ledgerOn = "true".equals(readContext(toolContext, ToolCallLedger.CTX_ENABLED_KEY));
            String ledgerRunId = ledgerOn ? readContext(toolContext, "agent.run_id") : null;
            ToolContext mcpSafeContext = withoutToolCallHistory(toolContext);
            return invoke(name, toolInput, effectiveInput, resolveSessionId(toolContext), resolveStepLabel(toolContext),
                    ledgerRunId, toolContext == null ? java.util.Map.of() : toolContext.getContext(),
                    RunBudgetRegistry.find(toolContext == null ? java.util.Map.of() : toolContext.getContext()),
                    target -> target.call(effectiveInput, mcpSafeContext));
        }
    }

    /**
     * Spring AI 1.1.x appends the complete conversation to
     * {@link ToolContext#TOOL_CALL_HISTORY}. The MCP converter copies that map
     * into JSON-RPC {@code _meta}. URL-backed Media stores a URI rather than a
     * byte array, so Jackson fails on Media#getDataAsByteArray before the MCP
     * server sees the request. Binary media would also make every call huge.
     *
     * Remove framework-generated history and private runtime state from the MCP-bound copy.
     * Session, step, approval and ledger context stay intact; the original
     * prompt and ChatMemory are not changed.
     */
    private ToolContext withoutToolCallHistory(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null
                || (!toolContext.getContext().containsKey(ToolContext.TOOL_CALL_HISTORY)
                && !toolContext.getContext().containsKey(StreamingActivityTracker.CONTEXT_KEY)
                && !toolContext.getContext().containsKey(ArtifactToolRecovery.CONTEXT_KEY))) {
            return toolContext;
        }
        LinkedHashMap<String, Object> safe = new LinkedHashMap<>(toolContext.getContext());
        safe.remove(ToolContext.TOOL_CALL_HISTORY);
        safe.remove(StreamingActivityTracker.CONTEXT_KEY);
        safe.remove(ArtifactToolRecovery.CONTEXT_KEY);
        log.debug("[MeteredToolCallback] stripped private runtime context from MCP metadata tool={}", safeName());
        return new ToolContext(safe);
    }

    /**
     * 解析当前调用的 sessionId。
     * <p>
     * 工具调用常跑在 Reactor 流式线程 / DAG 池线程上，MDC（ThreadLocal）拿不到 sessionId
     * （项目未开 {@code Hooks.enableAutomaticContextPropagation()}）。因此**优先从 {@link ToolContext}
     * 读**——它是方法参数，跨任何线程模型都带得过来；调用方在 {@code spec.toolContext(Map.of("sessionId", ...))}
     * 注入。MDC 仅作单线程同步场景的兜底。
     * <p>
     * sessionId 拿不到会让人工审批门退化为 {@code APPROVAL_UNAVAILABLE}（查不到 SSE 通道），高危工具被静默放行/拦截，
     * 这正是审批弹窗不出现的根因，故这里必须可靠。
     */
    private String resolveSessionId(ToolContext toolContext) {
        String fromCtx = readContext(toolContext, "sessionId");
        String resolved = fromCtx != null ? fromCtx : MDC.get("sessionId");
        if (resolved == null) {
            // 定向诊断：sessionId 解析失败时打出 toolContext 实际 key，避免再盲猜
            // （区分 toolContext 为 null / 空 / key 不符 / 走了无 context 的 call 重载）
            String keys = (toolContext == null) ? "NO_TOOLCONTEXT(call without context)"
                    : (toolContext.getContext() == null ? "CTX_MAP_NULL" : String.valueOf(toolContext.getContext().keySet()));
            log.warn("[MeteredToolCallback] sessionId resolve = null; toolContext keys={}, mdcSession={}", keys, MDC.get("sessionId"));
        }
        return resolved;
    }

    /**
     * 解析"这次工具调用属于哪个步骤"的人类可读标签（如"精准执行"）。
     * <p>
     * DAG 并行执行时多个步骤会同时发起高危工具审批，前端要靠这个标签区分"是哪个步骤要的许可"。
     * 同 sessionId，优先 ToolContext（调用方注入 {@code stepLabel}），MDC.get("step") 兜底，都没有返 null。
     */
    private String resolveStepLabel(ToolContext toolContext) {
        String fromCtx = readContext(toolContext, "stepLabel");
        return fromCtx != null ? fromCtx : MDC.get("step");
    }

    private String readContext(ToolContext toolContext, String key) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object v = toolContext.getContext().get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private String invoke(String name, String originalInput, String effectiveInput,
                          String sessionId, String stepLabel, String runId, java.util.Map<String, Object> resultScope,
                          RunBudgetRegistry.State budget, Invocation inv) {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.current();
        if (budget != null) budget.checkActive();
        if (activity != null) activity.markToolActivity();
        long start = System.currentTimeMillis();
        boolean success = false;
        RuntimeException failure = null;
        int rawResultChars = -1;
        int returnedResultChars = -1;
        int inputChars = effectiveInput == null ? 0 : effectiveInput.length();
        boolean inputChanged = !stringEquals(originalInput, effectiveInput);
        boolean resultLimited = false;
        if (inputChanged) {
            String kind = normalizeKindFor(name);
            if (kind != null) {
                metrics.recordNormalizeApplied(name, kind);
            }
        }
        // H3-A：sessionId 已在 call() 入口经 resolveSessionId(toolContext) 解析（ToolContext 优先、MDC 兜底），
        // 不再直接读 MDC——流式/DAG 线程上 MDC 为空会让进度事件和审批门一起失效。null/blank → emitter 内部跳过。
        ToolCallProgressEmitter progress = this.toolCallProgressEmitter;
        boolean progressStarted = false;
        long progressStartedAt = start;
        // H3-A：finally 阶段用此字段决定 emit end 的 status；catch 改写为 error。
        String progressStatus = STATUS_SUCCESS;
        String callId = java.util.UUID.randomUUID().toString();
        // 2026-05-20：把 rawResult 提到 finally 之外，让 ES trace 拿到未经 normalize/truncate 的真实响应
        String rawResultForTrace = null;
        // Evidence Map stores exactly what the model received after normalization, not the provider's raw envelope.
        String returnedResultForEvidence = null;
        boolean originalEvidenceHandled = false;
        try {
            if (isBlockedGithubWriteTool(name)) {
                if (progress != null) {
                    progressStartedAt = System.currentTimeMillis();
                    progress.emitStart(sessionId, name, effectiveInput, stepLabel, callId);
                    progressStarted = true;
                }
                String result = toolBlockedResult(name);
                rawResultChars = result.length();
                returnedResultChars = result.length();
                rawResultForTrace = result;
                returnedResultForEvidence = result;
                success = true;
                progressStatus = STATUS_BLOCKED;
                return result;
            }

            if (resultScope != null && Boolean.TRUE.equals(resultScope.get(DEBUG_READ_ONLY_KEY)) && !debugReadOnlyAllowed(name, delegate.get())) {
                String result = debugBlockedResult();
                rawResultChars = returnedResultChars = result.length();
                rawResultForTrace = returnedResultForEvidence = result;
                success = true; progressStatus = STATUS_BLOCKED;
                return result;
            }

            // G1-C：高风险工具人工审批 hook。gate==null 时 no-op 放行（保护机制），
            // 装配点未注入 gate 不应影响现有 agent 运行。审批拒绝/超时/通道缺失 → 结构化错误返 LLM 不抛异常，
            // Agent 流程继续，LLM 可据此说明原因或换路径。
            HumanApprovalGate gate = this.humanApprovalGate;
            if (gate != null && gate.isEnabled()
                    && gate.getHighRiskToolRegistry() != null
                    && gate.getHighRiskToolRegistry().isHighRisk(name)) {
                HumanApprovalGate.Decision decision;
                try (StreamingActivityTracker.Scope ignored = activity == null ? () -> {} :
                        activity.awaitingUser(java.time.Duration.ofSeconds(Math.max(1, gate.getTimeoutSeconds()) + 5L))) {
                    decision = gate.requestApproval(sessionId, name, effectiveInput, stepLabel);
                }
                StreamingActivityTracker.checkActive(activity);
                if (decision != HumanApprovalGate.Decision.APPROVED) {
                    metrics.recordApprovalDenied(name, decision.name());
                    String result = toolApprovalResult(name, decision);
                    log.info("mcp.tool.approval.result tool={} decision={} result={}", name, decision.name(), result);
                    rawResultChars = result.length();
                    returnedResultChars = result.length();
                    rawResultForTrace = result;
                    returnedResultForEvidence = result;
                    success = true; // 业务侧"拒绝"不是错误，仍记 OK 让 latency/count 正常
                    progressStatus = approvalDecisionToStatus(decision);
                    return result;
                }
            }

            // 审批只是执行前的人机交互，不属于工具调用。只有审批通过（或本工具无需审批）后，
            // 才开始发布工具进度；拒绝/超时/通道不可用都不会产生 tool_call 卡片。
            if (progress != null) {
                progressStartedAt = System.currentTimeMillis();
                progress.emitStart(sessionId, name, effectiveInput, stepLabel, callId);
                progressStarted = true;
            }

            // 方案A：同 mcpId 串行，防并行 step 并发写同一条 MCP 连接（高德 400 "Sending message failed"）
            String rawResult;
            // Real tool work has a finite deadline of its own. Neither connection queueing
            // nor a slow MCP response is a silent model; no synthetic heartbeats are emitted.
            try (StreamingActivityTracker.Scope toolPhase = activity == null ? () -> {} : activity.executingTool(name)) {
                java.util.concurrent.locks.ReentrantLock sendLock =
                        (mcpId != null && !mcpId.isBlank()) ? mcpSendLock(mcpId) : null;
                if (sendLock != null) acquireSendLock(sendLock, activity, budget);
                try {
                    StreamingActivityTracker.checkActive(activity);
                    rawResult = runInvocation(name, budget, resultScope != null && Boolean.TRUE.equals(resultScope.get(DEBUG_READ_ONLY_KEY)), inv);
                } finally {
                    if (sendLock != null) sendLock.unlock();
                }
            }
            rawResultChars = rawResult == null ? 0 : rawResult.length();
            rawResultForTrace = rawResult;
            ToolResultReferenceService resultService = this.toolResultReferenceService;
            ToolResultReferenceService.StoredResult stored = null;
            if (resultService != null) {
                originalEvidenceHandled = true;
                stored = resultService.store(resultScope, callId, name, effectiveInput, rawResult,
                        stepLabel, System.currentTimeMillis() - progressStartedAt);
            }
            String routeSummary = AmapRouteResultCompactor.compact(name, rawResult);
            boolean routeCompacted = !stringEquals(routeSummary, rawResult);
            String result = normalizeToolResult(name, routeSummary);
            if (resultService != null) result = resultService.project(name, rawResult, result, stored);
            returnedResultChars = result == null ? 0 : result.length();
            // Route summaries drop geometry/intermediate stops only from the model context.
            // Preserve the full source result in the existing evidence/resultRef store.
            returnedResultForEvidence = routeCompacted ? rawResult : result;
            resultLimited = returnedResultChars != rawResultChars;
            success = true;
            progressStatus = STATUS_SUCCESS;
            return result;
        } catch (RuntimeException e) {
            if (e instanceof DebugToolBlockedException) {
                String result = debugBlockedResult();
                rawResultChars = returnedResultChars = result.length();
                rawResultForTrace = returnedResultForEvidence = result;
                success = true; progressStatus = STATUS_BLOCKED;
                return result;
            }
            failure = e;
            progressStatus = STATUS_ERROR;
            StreamingActivityTracker.rethrowCancellation(e);
            StreamingActivityTracker.checkActive(activity);
            metrics.recordError(name, e);
            if (returnErrorOnFailure) {
                String result = toolErrorResult(name, e);
                rawResultChars = result.length();
                returnedResultChars = result.length();
                rawResultForTrace = result;
                returnedResultForEvidence = result;
                return result;
            }
            throw e;
        } finally {
            if (activity != null) activity.markDecodedResponse();
            long latency = System.currentTimeMillis() - start;
            long progressLatency = progressStarted
                    ? Math.max(0L, System.currentTimeMillis() - progressStartedAt)
                    : latency;
            metrics.recordCall(name, latency, success);
            if (rawResultChars >= 0) {
                metrics.recordResultSize(name, rawResultChars, resultLimited);
            }
            MDC.put("toolName", name);
            MDC.put("toolLatencyMs", String.valueOf(latency));
            MDC.put("toolInputChars", String.valueOf(inputChars));
            MDC.put("toolRawResultChars", String.valueOf(rawResultChars));
            MDC.put("toolResultChars", String.valueOf(returnedResultChars));
            // 2026-05-20：把工具调用的完整 input/output 也写到 MDC，让 Logstash 推 ES
            //   - toolInputFull   = normalize 后真实发给 MCP server 的入参（含 page/perPage 修正、precision 修正等）
            //   - toolResultFull  = MCP server 原始返回（未经 normalize/truncate）
            // 两个字段都带尺寸上限：单条 ES doc 单字段约 ≤8KB，避免 GitHub search 等巨型响应撑爆 mapping
            MDC.put("toolInputFull", abbrevForMdc(effectiveInput, 4_000));
            MDC.put("toolResultFull", abbrevForMdc(rawResultForTrace, 8_000));
            try {
                if (success) {
                    log.info("mcp.tool.call OK tool={} latencyMs={} inputChars={} rawResultChars={} returnedResultChars={} resultLimited={} inputChanged={}",
                            name, latency, inputChars, rawResultChars, returnedResultChars, resultLimited, inputChanged);
                } else {
                    log.warn("mcp.tool.call FAIL tool={} latencyMs={} inputChars={} rawResultChars={} returnedResultChars={} error={}",
                            name, latency, inputChars, rawResultChars, returnedResultChars, summarizeFailure(failure));
                }
                if (shouldLogToolInput(name)) {
                    log.info("mcp.tool.input tool={} original={} effective={}",
                            name, abbreviateForLog(originalInput, 1200), abbreviateForLog(effectiveInput, 1200));
                }
            } finally {
                MDC.remove("toolName");
                MDC.remove("toolLatencyMs");
                MDC.remove("toolInputChars");
                MDC.remove("toolRawResultChars");
                MDC.remove("toolResultChars");
                MDC.remove("toolInputFull");
                MDC.remove("toolResultFull");
            }
            // H3-A：emit 进度终态事件。放在 MDC 清理之后避免 emit 失败影响 MDC；
            // emitter 内部对 null sessionId / null emitter / 序列化异常都吞掉，不影响调用方。
            if (progress != null && progressStarted) {
                if (STATUS_ERROR.equals(progressStatus)) {
                    progress.emitError(sessionId, name, summarizeFailure(failure), progressLatency, stepLabel, callId);
                } else {
                    // 2026-06-22 评估采集：把工具真实返回(rawResultForTrace=未 normalize/截断 的原始响应)透给 emitter；
                    // emitter 端按 agent.tool-progress.result-preview.enabled 决定是否落进 SSE(默认关,生产不推)。
                    progress.emitEnd(sessionId, name, progressStatus, progressLatency,
                            returnedResultChars >= 0 ? returnedResultChars : 0, stepLabel, callId, rawResultForTrace);
                }
                // Never overwrite the original with the model's projection, or bypass a storage limit.
                if (!originalEvidenceHandled) {
                    progress.recordEvidence(sessionId, name, effectiveInput, returnedResultForEvidence,
                            progressStatus, progressLatency, rawResultChars, stepLabel, callId);
                }
            }
            // P0（Codex #2）工具调用事实摘要台账：记本轮真实调用（工具名/状态/返回字符数/形态，不含返回内容），
            // 供 Auto Step3 质检确认"确实调过工具/有返回"，消除"查无工具记录→反咬编造"。
            // runId 已在 call(ctx) 入口按开关 agent.tool_ledger_enabled 网关过（关=null=不记）；ledger 为 null 时 no-op。
            ToolCallLedger ledger = this.toolCallLedger;
            if (ledger != null && runId != null && !runId.isBlank()) {
                ledger.record(runId, name, progressStatus,
                        rawResultChars >= 0 ? rawResultChars : 0, rawResultForTrace);
            }
        }
    }

    /**
     * 方案A：同一个 MCP server（mcpId）的工具调用进程级串行。一条 MCP（SSE）连接同时只能有 1 个在途请求，
     * flow plan-dag 把 step1/step2 并行跑时，两个执行器各自调同一 server 会并发写同一条连接 →
     * 高德返 400「Sending message failed」。按 mcpId 各加一把锁串行化；不同 mcpId 各自一把、互不阻塞。
     * <p>RobustToolCallingManager 的 mcpId 分组只挡「单 step 内一批并行工具」，挡不住「两个并行 step 各自调同一 server」，
     * 这把锁补的就是跨 step 那一层。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> MCP_SEND_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.concurrent.locks.ReentrantLock mcpSendLock(String mcpId) {
        return MCP_SEND_LOCKS.computeIfAbsent(mcpId, k -> new java.util.concurrent.locks.ReentrantLock());
    }

    private static void acquireSendLock(java.util.concurrent.locks.ReentrantLock lock,
                                        StreamingActivityTracker.Activity activity, RunBudgetRegistry.State budget) {
        try {
            while (true) {
                StreamingActivityTracker.checkActive(activity);
                if (budget != null) budget.checkActive();
                if (lock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Interrupted while waiting for MCP connection");
        }
    }

    private void refreshDelegateFromRegistry(String name) {
        if (registry == null || name == null || name.isBlank()) {
            return;
        }
        ToolCallback current = mcpId == null ? registry.getCurrentCallback(name) : registry.getCurrentCallback(mcpId, name);
        if (mcpId != null && current == null) {
            throw new IllegalStateException("MCP tool is no longer available; reload the Agent configuration");
        }
        if (current != null && current != delegate.get()) {
            replaceDelegate(current);
            log.debug("mcp.tool.call REFRESH_DELEGATE tool={} mcpId={}", name, mcpId);
        }
    }

    private static String debugBlockedResult() {
        return "{\"ok\":false,\"error\":\"DEBUG_TOOL_BLOCKED\",\"message\":\"节点调试仅允许明确声明只读且不需审批的工具；该工具未执行\"}";
    }

    /** Reconnection may replace a transport, but must preserve the contract shown to the model. */
    private void replaceDelegate(ToolCallback current) {
        ToolDefinition definition = captureDefinition(current);
        if (cachedDefinition == null || definition == null
                || !java.util.Objects.equals(originalToolName, McpClientRegistry.originalToolName(current))
                || !java.util.Objects.equals(cachedDefinition.inputSchema(), definition.inputSchema())) {
            throw new IllegalStateException("MCP tool definition changed; reload its capabilities before calling");
        }
        delegate.set(current);
    }

    private static final class DebugToolBlockedException extends IllegalStateException {}

    private boolean debugReadOnlyAllowed(String name, ToolCallback target) {
        HumanApprovalGate gate = humanApprovalGate;
        if (gate == null || gate.getHighRiskToolRegistry() == null || gate.getHighRiskToolRegistry().isHighRisk(name)) return false;
        return target.getToolMetadata() instanceof McpToolCallMetadata metadata
                && Boolean.TRUE.equals(metadata.readOnlyHint()) && !Boolean.TRUE.equals(metadata.destructiveHint());
    }

    private String runInvocation(String name, RunBudgetRegistry.State budget, boolean debugReadOnly, Invocation inv) {
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.current();
        int maxAttempts = mcpToolCallMaxAttempts;
        String recovery = "retry";
        boolean retriedAfterLiveProbe = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            StreamingActivityTracker.checkActive(activity);
            ToolCallback target = delegate.get();
            try (McpClientRegistry.CallLease ignored=registry==null||mcpId==null?null:registry.borrowCall(mcpId)) {
                if(ignored!=null||attempt==1)refreshDelegateFromRegistry(name);
                target=delegate.get();
                if (debugReadOnly && !debugReadOnlyAllowed(name, target)) throw new DebugToolBlockedException();
                if (budget != null) budget.reserveTool();
                String result = inv.run(target);
                StreamingActivityTracker.checkActive(activity);
                if (budget != null) budget.checkActive();
                if (attempt > 1) metrics.recordRecovered(name, recovery);
                if (registry != null && mcpId != null) {
                    registry.recordSuccess(mcpId);
                }
                return result;
            } catch (RuntimeException e) {
                StreamingActivityTracker.rethrowCancellation(e);
                StreamingActivityTracker.checkActive(activity);
                if (attempt == 1) {
                    metrics.recordFirstAttemptFailure(name, classifyInitialFailure(e));
                }
                boolean sessionRejected = isConfirmedSessionRejection(e);
                boolean transientFailure = sessionRejected || isTimeoutError(e) || isDeadClientError(e) || isRetryableMcpError(e);
                if (!transientFailure) throw e;
                // A lost response does not prove that a write was not committed. Approval of the
                // original call also does not authorize an automatic duplicate of an unknown operation.
                // A structured invalid-session rejection is different: the server did not accept
                // this request. This exception applies only to that response, never to a later timeout.
                if (!sessionRejected && !retrySafe(target)) throw new ToolOutcomeUnknownException(name, e);
                // Every inv.run(), including calls after reconnect, consumes the same attempt budget.
                if (attempt >= maxAttempts) throw e;

                if (sessionRejected) {
                    // Re-sending to the rejected session cannot recover it. Do not retry without a registry.
                    if (registry == null) throw e;
                    ToolCallback fresh = mcpId == null ? registry.forceReconnect(name) : registry.forceReconnect(mcpId, name);
                    if (fresh == null) throw e;
                    replaceDelegate(fresh);
                    recovery = "session_rejected_reconnect";
                } else if (isTimeoutError(e) && registry != null) {
                    boolean alive = false;
                    if (!retriedAfterLiveProbe) {
                        alive = mcpId == null ? registry.probeAfterTimeout(name) : registry.probeAfterTimeout(mcpId, name);
                        metrics.recordTimeoutProbe(name, alive);
                    }
                    StreamingActivityTracker.checkActive(activity);
                    if (alive) {
                        retriedAfterLiveProbe = true;
                        recovery = "timeout_probe_alive_retry";
                    } else {
                        ToolCallback fresh = mcpId == null ? registry.reconnectAfterTimeout(name)
                                : registry.reconnectAfterTimeout(mcpId, name);
                        if (fresh == null) throw e;
                        replaceDelegate(fresh);
                        recovery = retriedAfterLiveProbe ? "timeout_probe_alive_reconnect" : "timeout_probe_dead_reconnect";
                    }
                } else if (isDeadClientError(e) && registry != null) {
                    ToolCallback fresh = mcpId == null ? registry.forceReconnect(name) : registry.forceReconnect(mcpId, name);
                    if (fresh == null) throw e;
                    replaceDelegate(fresh);
                    recovery = "dead_client_reconnect";
                } else {
                    retryBackoff(activity, budget, mcpToolCallRetryDelayMs * attempt);
                    recovery = "retry";
                }
                StreamingActivityTracker.checkActive(activity);
                // Reconnection may expose different annotations; never retry through weaker metadata.
                if (!sessionRejected && !retrySafe(delegate.get())) throw new ToolOutcomeUnknownException(name, e);
                log.info("mcp.tool.call RETRY tool={} completedAttempts={} maxAttempts={} recovery={}",
                        name, attempt, maxAttempts, recovery);
                metrics.recordRetry(name, attempt);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private boolean retrySafe(ToolCallback target) {
        return target.getToolMetadata() instanceof McpToolCallMetadata metadata && metadata.retrySafe();
    }

    private static void retryBackoff(StreamingActivityTracker.Activity activity, RunBudgetRegistry.State budget, long delayMs) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delayMs);
        try {
            while (System.nanoTime() < deadline) {
                StreamingActivityTracker.checkActive(activity);
                if (budget != null) budget.checkActive();
                long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                Thread.sleep(Math.max(1, Math.min(100, remaining)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Interrupted during MCP retry backoff");
        }
    }

    public static final class ToolOutcomeUnknownException extends IllegalStateException {
        public ToolOutcomeUnknownException(String toolName, RuntimeException cause) {
            super("工具 " + toolName + " 的执行结果未确认，无法确认重复调用安全，已停止自动重试。请先核对外部状态，勿重复提交。", cause);
        }
    }

    private String normalizeToolInput(String name, String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return toolInput;
        }
        if (isGithubRepositorySearchTool(name)) {
            return normalizeGithubRepositorySearchInput(toolInput);
        }
        if (isCalculateTool(name)) {
            return normalizeCalculateInput(toolInput);
        }
        if (aiSearchStripServerLlm && isAiSearchTool(name)) {
            return normalizeAiSearchInput(toolInput);
        }
        return toolInput;
    }

    private String normalizeGithubRepositorySearchInput(String toolInput) {
        try {
            JSONObject obj = JSON.parseObject(toolInput);
            Integer page = obj.getInteger("page");
            if (page == null || page <= 0) {
                obj.put("page", GITHUB_SEARCH_DEFAULT_PAGE);
            }

            Integer perPage = obj.getInteger("perPage");
            if (perPage == null) {
                perPage = obj.getInteger("per_page");
            }
            if (perPage == null || perPage <= 0 || perPage > githubSearchMaxPerPage) {
                obj.put("perPage", githubSearchMaxPerPage);
            } else {
                obj.put("perPage", perPage);
            }
            obj.remove("per_page");
            return obj.toJSONString();
        } catch (Exception e) {
            log.debug("github search input normalize skipped, input={}", abbreviateForLog(toolInput, 400));
            return toolInput;
        }
    }

    /**
     * calculate 工具的 precision 字段 schema 是 union[空串"" | number]，LLM 经常传非空字符串
     * （"2"、"high"、"default" 等）导致 invalid_union 校验失败、立即返错。
     * 这里把数字字符串转 number、其他不合规字符串/null 直接删字段（让 server 走默认）。
     */
    private String normalizeCalculateInput(String toolInput) {
        try {
            JSONObject obj = JSON.parseObject(toolInput);
            if (!obj.containsKey("precision")) {
                return toolInput;
            }
            Object precision = obj.get("precision");
            if (precision == null) {
                obj.remove("precision");
                return obj.toJSONString();
            }
            if (precision instanceof Number || "".equals(precision)) {
                return toolInput;
            }
            if (precision instanceof String s) {
                String trimmed = s.trim();
                if (trimmed.isEmpty()) {
                    obj.put("precision", "");
                    return obj.toJSONString();
                }
                try {
                    double n = Double.parseDouble(trimmed);
                    if (n == Math.floor(n) && !Double.isInfinite(n)) {
                        obj.put("precision", (long) n);
                    } else {
                        obj.put("precision", n);
                    }
                    return obj.toJSONString();
                } catch (NumberFormatException ignored) {
                    obj.remove("precision");
                    return obj.toJSONString();
                }
            }
            obj.remove("precision");
            return obj.toJSONString();
        } catch (Exception e) {
            log.debug("calculate input normalize skipped, input={}", abbreviateForLog(toolInput, 400));
            return toolInput;
        }
    }

    private String normalizeToolResult(String name, String rawResult) {
        if (!isGithubRepositorySearchTool(name) || rawResult == null || rawResult.isBlank()) {
            return rawResult;
        }
        String compact = githubSearchCompactResultEnabled ? compactGithubRepositorySearchResult(rawResult) : null;
        if (compact == null || compact.isBlank() || isEmptyCompactedGithubResult(compact)) {
            compact = rawResult;
        }
        if (compact.length() <= githubSearchMaxResultChars) {
            return compact;
        }
        return compact.substring(0, githubSearchMaxResultChars)
                + "\n...(truncated by GitHub repository search guard, rawChars=" + rawResult.length()
                + ", returnedChars=" + githubSearchMaxResultChars + ")";
    }

    private String compactGithubRepositorySearchResult(String rawResult) {
        try {
            Object parsed = JSON.parse(rawResult);
            JSONArray items = extractRepositoryItems(parsed);
            if (items == null) {
                return null;
            }

            JSONArray compactItems = new JSONArray();
            int maxItemChars = 0;
            for (int i = 0; i < items.size() && compactItems.size() < githubSearchMaxPerPage; i++) {
                Object value = items.get(i);
                if (!(value instanceof JSONObject item)) {
                    continue;
                }
                JSONObject repo = item.getJSONObject("repository");
                if (repo == null) {
                    repo = item;
                }
                JSONObject compact = new JSONObject(true);
                putIfPresent(compact, "name", repo, "name");
                putIfPresent(compact, "full_name", repo, "full_name");
                putIfPresent(compact, "html_url", repo, "html_url");
                putIfPresent(compact, "description", repo, "description", GITHUB_SEARCH_MAX_DESCRIPTION_CHARS);
                putIfPresent(compact, "stargazers_count", repo, "stargazers_count");
                putIfPresent(compact, "forks_count", repo, "forks_count");
                putIfPresent(compact, "language", repo, "language");
                putTopics(compact, repo);
                putIfPresent(compact, "updated_at", repo, "updated_at");
                putIfPresent(compact, "pushed_at", repo, "pushed_at");
                if (!compact.isEmpty()) {
                    String itemText = compact.toJSONString();
                    maxItemChars = Math.max(maxItemChars, itemText.length());
                    compactItems.add(compact);
                }
            }

            JSONObject out = new JSONObject(true);
            out.put("ok", true);
            out.put("source", "github_search_repositories_compacted");
            out.put("rawChars", rawResult.length());
            out.put("returnedItems", compactItems.size());
            out.put("maxItemChars", maxItemChars);
            out.put("items", compactItems);
            return out.toJSONString();
        } catch (Exception e) {
            log.debug("github search result compact skipped, rawChars={}", rawResult.length());
            return null;
        }
    }

    private boolean isEmptyCompactedGithubResult(String compact) {
        try {
            JSONObject obj = JSON.parseObject(compact);
            return "github_search_repositories_compacted".equals(obj.getString("source"))
                    && obj.getIntValue("returnedItems") == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONArray extractRepositoryItems(Object parsed) {
        if (parsed instanceof JSONArray arr) {
            return arr;
        }
        if (!(parsed instanceof JSONObject obj)) {
            return null;
        }
        for (String key : new String[]{"items", "repositories", "results"}) {
            JSONArray arr = obj.getJSONArray(key);
            if (arr != null) {
                return arr;
            }
        }
        Object data = obj.get("data");
        if (data instanceof JSONArray arr) {
            return arr;
        }
        if (data instanceof JSONObject dataObj) {
            for (String key : new String[]{"items", "repositories", "results"}) {
                JSONArray arr = dataObj.getJSONArray(key);
                if (arr != null) {
                    return arr;
                }
            }
        }
        return null;
    }

    private void putIfPresent(JSONObject target, String targetKey, JSONObject source, String sourceKey) {
        putIfPresent(target, targetKey, source, sourceKey, -1);
    }

    private void putIfPresent(JSONObject target, String targetKey, JSONObject source, String sourceKey, int maxChars) {
        Object value = source.get(sourceKey);
        if (value == null) {
            return;
        }
        if (value instanceof String s && maxChars > 0 && s.length() > maxChars) {
            value = s.substring(0, maxChars) + "...";
        }
        target.put(targetKey, value);
    }

    private void putTopics(JSONObject target, JSONObject source) {
        JSONArray topics = source.getJSONArray("topics");
        if (topics == null || topics.isEmpty()) {
            return;
        }
        JSONArray limited = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < topics.size() && limited.size() < 12; i++) {
            String topic = topics.getString(i);
            if (topic != null && !topic.isBlank() && seen.add(topic)) {
                limited.add(topic);
            }
        }
        if (!limited.isEmpty()) {
            target.put("topics", limited);
        }
    }

    private boolean shouldLogToolInput(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.toLowerCase();
        return n.endsWith("search_repositories")
                || n.endsWith("search_code")
                || n.endsWith("search_issues")
                || n.endsWith("search_users")
                || n.endsWith("aisearch");
    }

    private boolean isGithubRepositorySearchTool(String name) {
        return name != null && !name.isBlank() && name.toLowerCase().endsWith("search_repositories");
    }

    private boolean isCalculateTool(String name) {
        return name != null && !name.isBlank() && name.toLowerCase().endsWith("calculate");
    }

    private boolean isAiSearchTool(String name) {
        return name != null && !name.isBlank() && name.toLowerCase().contains("aisearch");
    }

    /** 给 metric 用的 normalize 类别标签；null 表示该工具不触发 normalize 路径。 */
    private String normalizeKindFor(String name) {
        if (isGithubRepositorySearchTool(name)) return "github_per_page";
        if (isCalculateTool(name)) return "calculate_precision";
        if (aiSearchStripServerLlm && isAiSearchTool(name)) return "aisearch_strip";
        return null;
    }

    private String normalizeAiSearchInput(String toolInput) {
        try {
            JSONObject obj = JSON.parseObject(toolInput);
            obj.remove("model");
            obj.remove("instruction");
            obj.remove("temperature");
            return obj.toJSONString();
        } catch (Exception e) {
            log.debug("aisearch input normalize skipped, input={}", abbreviateForLog(toolInput, 400));
            return toolInput;
        }
    }

    /**
     * 给 MDC（→ ES）用的截断 helper。比 {@link #abbreviateForLog} 区别：
     * 不做 \r\n\t 转义（让 Logstash 自己用 JSON string 编码处理），但同样把换行字面化成 \n
     * 避免 plain text appender 把单条日志拆成多行。
     */
    private static String abbrevForMdc(String value, int maxChars) {
        if (value == null || value.isEmpty()) return "";
        String s = value;
        if (s.length() > maxChars) {
            s = s.substring(0, maxChars) + "...(truncated, full=" + value.length() + ")";
        }
        return s.replace("\r", "").replace("\n", "\\n");
    }

    private String abbreviateForLog(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...(truncated, chars=" + normalized.length() + ")";
    }

    private String summarizeFailure(RuntimeException e) {
        if (e == null) {
            return "unknown";
        }
        String cause = safeFailureCause(e);
        return e instanceof ToolOutcomeUnknownException
                ? e.getClass().getSimpleName() + ": " + e.getMessage() + " 原因：" + cause
                : e.getClass().getSimpleName() + ": " + cause;
    }

    /** Match the observed transport rejection, not a tool name, a bare HTTP 400, or text in a stack trace. */
    private boolean isConfirmedSessionRejection(Throwable failure) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.size() < 16 && seen.add(current); current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || !message.startsWith(SESSION_REJECTION_PREFIX)) continue;
            JSONObject body = transportErrorBody(message.substring(SESSION_REJECTION_PREFIX.length()));
            if (body != null && SESSION_REJECTION_MESSAGE.equals(body.get("message"))
                    && body.get("jsonRpcError") == null) return true;
        }
        return false;
    }

    private JSONObject transportErrorBody(String text) {
        if (text == null || text.length() > 64_000) return null;
        String body = text.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) return null;
        try {
            // Provider error envelopes are data; never process Fastjson special keys such as @type.
            return JSON.parseObject(body, com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Keep the underlying reason in model/SSE/log diagnostics, without transport envelopes or credentials. */
    private String safeFailureCause(RuntimeException failure) {
        if (isConfirmedSessionRejection(failure)) {
            return "MCP 会话已失效，服务器拒绝了本次请求（HTTP 400；" + SESSION_REJECTION_MESSAGE + "）";
        }
        Throwable cause = failure;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (cause.getCause() != null && seen.size() < 16 && seen.add(cause) && !seen.contains(cause.getCause())) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return cause.getClass().getSimpleName();
        String transportPrefix = "Sending message failed with a non-OK HTTP code: ";
        if (message.startsWith(transportPrefix)) {
            String response = message.substring(transportPrefix.length());
            int separator = response.indexOf(" - ");
            String status = separator < 0 ? response : response.substring(0, separator);
            // Never expose the server's serialized stackTrace/cause/request from an error envelope.
            if (status.matches("[1-5][0-9]{2}")) {
                JSONObject body = separator < 0 ? null : transportErrorBody(response.substring(separator + 3));
                Object reason = body == null ? null : body.get("message");
                message = "MCP HTTP " + status + (reason instanceof String text ? ": " + text : " 请求失败");
            } else {
                message = "MCP 传输请求失败";
            }
        }
        return cause.getClass().getSimpleName() + ": " + sanitizeFailureMessage(message);
    }

    private String sanitizeFailureMessage(String message) {
        String safe = message
                .replaceAll("(?i)https?://[^\\s\\\"'<>]+", "[redacted-url]")
                .replaceAll("(?i)\\b(Bearer|Basic)\\s+[^\\s,;\\\"']+", "$1 [redacted]")
                .replaceAll("(?i)([\\\"']?(?:authorization|proxy-authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|token|password|passwd|secret|client[-_]?secret|session[-_]?id|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;&}]+)", "$1[redacted]")
                .replaceAll("[\\p{Cntrl}]", " ");
        return safe.length() <= 500 ? safe : safe.substring(0, 500) + "...";
    }

    private String classifyInitialFailure(RuntimeException e) {
        if (isTimeoutError(e)) {
            return "timeout";
        }
        if (isDeadClientError(e)) {
            return "dead_client";
        }
        if (isRetryableMcpError(e)) {
            return "retryable";
        }
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    /** 判断异常是否为超时（需探活区分慢服务 vs 死连接） */
    private boolean isTimeoutError(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.util.concurrent.TimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 判断异常是否指示 SSE 连接死亡（需要重建 McpSyncClient，而非简单重试） */
    private boolean isDeadClientError(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            // SocketException（非 SocketTimeoutException）通常意味着连接已死
            if (t instanceof SocketException && !(t instanceof SocketTimeoutException)) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("http1_0 content")
                    || msg.contains("unexpected end of stream")
                    || msg.contains("Connection reset")
                    || msg.contains("Client is closed")
                    || msg.contains("connection pool shut down")
                    || msg.contains("Session expired")
                    || msg.contains("No message endpoint")
                    || msg.contains("Failed to wait for the message endpoint")
                    // 出站 sink 已终结/连接被替换：往已关闭的 transport 发消息会瞬间(0ms)抛此异常。
                    // 归类为死连接 → 走 forceReconnect 重建后自愈。
                    || msg.contains("Failed to enqueue message")
                    // 1.1.0(mcp 0.16.0)起：往 SSE 会话发消息被服务端拒（高德返
                    // "Sending message failed with a non-OK HTTP code: 400"）会直接抛 ToolExecutionException。
                    // 0.10.0 时这是「只 log 不抛」、靠下一次超时探活兜；现在归死连接走重建自愈。
                    || msg.contains("Sending message failed"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 判断 MCP 工具调用异常是否为可重试的瞬态错误 */
    private boolean isRetryableMcpError(RuntimeException e) {
        // 遍历 cause chain 找可重试异常
        Throwable t = e;
        while (t != null) {
            if (t instanceof IOException || t instanceof SocketTimeoutException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("502") || msg.contains("503") || msg.contains("504")
                    || msg.contains("Connection reset") || msg.contains("Connection refused")
                    || msg.contains("broken pipe"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private String safeName() {
        try {
            ToolDefinition d = delegate.get().getToolDefinition();
            return d == null ? "unknown" : d.name();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean isBlockedGithubWriteTool(String name) {
        if (githubWriteEnabled || name == null || name.isBlank()) {
            return false;
        }
        return isGithubWriteToolName(name);
    }

    public static boolean isGithubWriteToolName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.toLowerCase();
        return n.endsWith("create_repository")
                || n.endsWith("create_or_update_file")
                || n.endsWith("push_files")
                || n.endsWith("delete_file")
                || n.endsWith("fork_repository")
                || n.endsWith("create_issue")
                || n.endsWith("create_pull_request")
                || n.endsWith("merge_pull_request");
    }

    private String toolBlockedResult(String name) {
        return "{\"ok\":false,\"tool\":\"" + jsonEscape(name)
                + "\",\"blocked\":true,"
                + "\"message\":\"本次 agent 运行已禁用 GitHub 写工具。除非用户明确要求执行 GitHub 写操作，且 agent.mcp.github.write-enabled=true，否则请直接回答或改用只读工具。\"}";
    }

    /**
     * G1-C：返回结构化拒绝/超时/通道缺失消息给 LLM。三种 Decision 给不同 message，让模型在下一轮
     * Reflexion 或工具切换时能区分对待（用户主动拒绝 vs 系统侧通道问题）。
     */
    private String toolApprovalResult(String name, HumanApprovalGate.Decision decision) {
        String message = switch (decision) {
            case REJECTED -> "用户已明确拒绝本次高风险工具调用。请不要重复发起相同调用；可以向用户说明为什么需要该工具，或改用其它工具/直接回答。";
            case TIMEOUT -> "等待用户审批超时。请不要重复发起相同调用；可以建议用户稍后重试，或改用非破坏性的替代方案。";
            case APPROVAL_UNAVAILABLE -> "当前没有可用的人工审批通道（无活跃 SSE 会话）。该工具需要用户确认，但现在无法弹出确认请求；请跳过该工具并基于已有上下文回答，或提示用户在交互会话中重试。";
            default -> "工具调用被拒绝：" + decision.name();
        };
        return "{\"ok\":false,\"tool\":\"" + jsonEscape(name)
                + "\",\"approval\":\"" + decision.name()
                + "\",\"message\":\"" + jsonEscape(message) + "\"}";
    }

    private String toolErrorResult(String name, RuntimeException e) {
        String cause = safeFailureCause(e);
        if (e instanceof ToolOutcomeUnknownException) {
            return "{\"ok\":false,\"tool\":\"" + jsonEscape(name)
                    + "\",\"outcome\":\"unknown\",\"retryAllowed\":false,\"error\":\"" + jsonEscape(cause)
                    + "\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}";
        }
        return "{\"ok\":false,\"tool\":\"" + jsonEscape(name)
                + "\",\"error\":\"" + jsonEscape(cause)
                + "\",\"message\":\"工具调用失败。请不要卡在该工具上，可基于已有信息继续回答，或尝试其它真实可用路径。\"}";
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private boolean stringEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @FunctionalInterface
    private interface Invocation {
        String run(ToolCallback target);
    }
}
