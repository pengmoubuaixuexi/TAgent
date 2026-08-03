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
public class MeteredToolCallback implements ToolCallback {

    private static final int GITHUB_SEARCH_DEFAULT_PAGE = 1;
    private static final int DEFAULT_GITHUB_SEARCH_MAX_PER_PAGE = 10;
    private static final int DEFAULT_GITHUB_SEARCH_MAX_RESULT_CHARS = 20_000;
    private static final int GITHUB_SEARCH_MAX_DESCRIPTION_CHARS = 320;

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
        return delegate.get().getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String name = safeName();
        String effectiveInput = normalizeToolInput(name, toolInput);
        return invoke(name, toolInput, effectiveInput, resolveSessionId(null), resolveStepLabel(null),
                null, () -> delegate.get().call(effectiveInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = safeName();
        String effectiveInput = normalizeToolInput(name, toolInput);
        // 工具调用事实摘要台账：仅当 ToolContext 带开关 agent.tool_ledger_enabled=true（只有 Auto 质检路径注入）时才记账，
        // 且需拿到 buildToolContext 注入的 agent.run_id（按 runId 隔离）。Fixed/Flow 不注入开关 → 不记 → 不会"只记不清"。
        boolean ledgerOn = "true".equals(readContext(toolContext, ToolCallLedger.CTX_ENABLED_KEY));
        String ledgerRunId = ledgerOn ? readContext(toolContext, "agent.run_id") : null;
        ToolContext mcpSafeContext = withoutToolCallHistory(toolContext);
        return invoke(name, toolInput, effectiveInput, resolveSessionId(toolContext), resolveStepLabel(toolContext),
                ledgerRunId, () -> delegate.get().call(effectiveInput, mcpSafeContext));
    }

    /**
     * Spring AI 1.1.x appends the complete conversation to
     * {@link ToolContext#TOOL_CALL_HISTORY}. The MCP converter copies that map
     * into JSON-RPC {@code _meta}. URL-backed Media stores a URI rather than a
     * byte array, so Jackson fails on Media#getDataAsByteArray before the MCP
     * server sees the request. Binary media would also make every call huge.
     *
     * Remove only framework-generated history from the MCP-bound copy.
     * Session, step, approval and ledger context stay intact; the original
     * prompt and ChatMemory are not changed.
     */
    private ToolContext withoutToolCallHistory(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null
                || !toolContext.getContext().containsKey(ToolContext.TOOL_CALL_HISTORY)) {
            return toolContext;
        }
        LinkedHashMap<String, Object> safe = new LinkedHashMap<>(toolContext.getContext());
        safe.remove(ToolContext.TOOL_CALL_HISTORY);
        log.debug("[MeteredToolCallback] stripped TOOL_CALL_HISTORY from MCP metadata tool={}", safeName());
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
                          String sessionId, String stepLabel, String runId, Invocation inv) {
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

            // G1-C：高风险工具人工审批 hook。gate==null 时 no-op 放行（保护机制），
            // 装配点未注入 gate 不应影响现有 agent 运行。审批拒绝/超时/通道缺失 → 结构化错误返 LLM 不抛异常，
            // Agent 流程继续，LLM 可据此说明原因或换路径。
            HumanApprovalGate gate = this.humanApprovalGate;
            if (gate != null && gate.isEnabled()
                    && gate.getHighRiskToolRegistry() != null
                    && gate.getHighRiskToolRegistry().isHighRisk(name)) {
                HumanApprovalGate.Decision decision = gate.requestApproval(sessionId, name, effectiveInput, stepLabel);
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
            java.util.concurrent.locks.ReentrantLock sendLock =
                    (mcpId != null && !mcpId.isBlank()) ? mcpSendLock(mcpId) : null;
            if (sendLock != null) sendLock.lock();
            try {
                refreshDelegateFromRegistry(name);
                rawResult = runInvocation(name, inv);
            } finally {
                if (sendLock != null) sendLock.unlock();
            }
            rawResultChars = rawResult == null ? 0 : rawResult.length();
            rawResultForTrace = rawResult;
            String result = normalizeToolResult(name, rawResult);
            returnedResultChars = result == null ? 0 : result.length();
            returnedResultForEvidence = result;
            resultLimited = returnedResultChars != rawResultChars;
            success = true;
            progressStatus = STATUS_SUCCESS;
            return result;
        } catch (RuntimeException e) {
            failure = e;
            metrics.recordError(name, e);
            progressStatus = STATUS_ERROR;
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
                progress.recordEvidence(sessionId, name, effectiveInput, returnedResultForEvidence,
                        progressStatus, progressLatency, rawResultChars, stepLabel, callId);
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

    private void refreshDelegateFromRegistry(String name) {
        if (registry == null || name == null || name.isBlank()) {
            return;
        }
        ToolCallback current = registry.getCurrentCallback(name);
        if (current != null && current != delegate.get()) {
            delegate.set(current);
            log.debug("mcp.tool.call REFRESH_DELEGATE tool={} mcpId={}", name, mcpId);
        }
    }

    private String runInvocation(String name, Invocation inv) {
        int maxAttempts = mcpToolCallMaxAttempts;
        boolean firstAttemptFailed = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = inv.run();
                if (firstAttemptFailed) {
                    metrics.recordRecovered(name, "retry");
                }
                // 调用成功，记录时间戳（用于冷连接探活判断）
                if (registry != null && mcpId != null) {
                    registry.recordSuccess(mcpId);
                }
                return result;
            } catch (RuntimeException e) {
                if (attempt == 1 && !firstAttemptFailed) {
                    firstAttemptFailed = true;
                    metrics.recordFirstAttemptFailure(name, classifyInitialFailure(e));
                }
                // 超时 → 探活判断：连接活着先重试，连接死了才重建。
                // 历史背景：MCP SDK 0.10.0 时 sendMessage 收到 400「只打日志不抛异常」，message endpoint
                // 失效但 SSE 流仍活着，只能靠「下一次调用超时 + 探活」间接发现——这套探活兜底就是为它写的。
                // 1.1.0(mcp 0.16.0)起 400 会直接抛 ToolExecutionException（已在 isDeadClientError 归类重建），
                // 所以本超时分支现在主要兜「工具真的慢」的 TimeoutException：探活活着=慢服务先重试，探活死了才重建。
                if (isTimeoutError(e) && registry != null) {
                    boolean alive = registry.probeAfterTimeout(name);
                    metrics.recordTimeoutProbe(name, alive);
                    if (alive) {
                        // 连接活着（可能是工具确实慢），先用当前连接重试一次
                        log.info("mcp.tool.call TIMEOUT_BUT_ALIVE tool={} attempt={}/{}, retrying with current connection", name, attempt, maxAttempts);
                        try {
                            String result = inv.run();
                            metrics.recordRecovered(name, "timeout_probe_alive_retry");
                            if (mcpId != null) {
                                registry.recordSuccess(mcpId);
                            }
                            return result;
                        } catch (RuntimeException retryEx) {
                            // 重试也失败了，需要按 timeout 自愈路径重建连接
                            log.warn("mcp.tool.call RETRY_FAILED_AFTER_PROBE_ALIVE tool={}, will reconnect after timeout", name);
                            ToolCallback fresh = registry.reconnectAfterTimeout(name);
                            if (fresh != null) {
                                delegate.set(fresh);
                                log.info("mcp.tool.call RECONNECTED_AFTER_TIMEOUT tool={}", name);
                                try {
                                    String result = inv.run();
                                    metrics.recordRecovered(name, "timeout_probe_alive_reconnect");
                                    if (mcpId != null) {
                                        registry.recordSuccess(mcpId);
                                    }
                                    return result;
                                } catch (RuntimeException e3) {
                                    log.warn("mcp.tool.call RETRY_AFTER_RECONNECT tool={} error={}", name, summarizeFailure(e3));
                                    throw e3;
                                }
                            }
                            throw e;
                        }
                    } else {
                        // 连接已死，直接重建
                        log.warn("mcp.tool.call TIMEOUT_DEAD tool={} attempt={}/{}", name, attempt, maxAttempts);
                        ToolCallback fresh = registry.reconnectAfterTimeout(name);
                        if (fresh != null) {
                            delegate.set(fresh);
                            log.info("mcp.tool.call RECONNECTED_AFTER_TIMEOUT tool={}", name);
                            try {
                                String result = inv.run();
                                metrics.recordRecovered(name, "timeout_probe_dead_reconnect");
                                if (mcpId != null) {
                                    registry.recordSuccess(mcpId);
                                }
                                return result;
                            } catch (RuntimeException e2) {
                                log.warn("mcp.tool.call RETRY_AFTER_RECONNECT tool={} error={}", name, summarizeFailure(e2));
                                throw e2;
                            }
                        }
                        throw e;
                    }
                }
                // 死客户端（非超时类，如 Connection reset / 400 发送失败） → 直接重建当前 mcpId，并用新 callback 重试一次。
                if (isDeadClientError(e) && registry != null) {
                    log.warn("mcp.tool.call DEAD_CLIENT tool={} attempt={}/{} error={}",
                            name, attempt, maxAttempts, summarizeFailure(e));
                    ToolCallback fresh = registry.forceReconnect(name);
                    if (fresh != null) {
                        delegate.set(fresh);
                        log.info("mcp.tool.call RECONNECTED tool={}, retrying with fresh delegate", name);
                        try {
                            String result = inv.run();
                            metrics.recordRecovered(name, "dead_client_reconnect");
                            if (mcpId != null) {
                                registry.recordSuccess(mcpId);
                            }
                            return result;
                        } catch (RuntimeException e2) {
                            log.warn("mcp.tool.call RETRY_AFTER_RECONNECT tool={} error={}", name, summarizeFailure(e2));
                            throw e2;
                        }
                    }
                    throw e;
                }
                // 瞬态错误 → 同客户端重试
                if (attempt < maxAttempts && isRetryableMcpError(e)) {
                    log.warn("mcp.tool.call RETRY tool={} attempt={}/{} error={}",
                            name, attempt, maxAttempts, summarizeFailure(e));
                    metrics.recordRetry(name, attempt);
                    try { Thread.sleep(mcpToolCallRetryDelayMs * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("unreachable");
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
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
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
            if (t instanceof java.util.concurrent.TimeoutException) {
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
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return "{\"ok\":false,\"tool\":\"" + jsonEscape(name)
                + "\",\"error\":\"" + jsonEscape(message)
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
        String run();
    }
}
