package cn.bugstack.ai.domain.agent.service.execute.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

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

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.get().getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.get().getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String name = safeName();
        String effectiveInput = normalizeToolInput(name, toolInput);
        return invoke(name, toolInput, effectiveInput, () -> delegate.get().call(effectiveInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = safeName();
        String effectiveInput = normalizeToolInput(name, toolInput);
        return invoke(name, toolInput, effectiveInput, () -> delegate.get().call(effectiveInput, toolContext));
    }

    private String invoke(String name, String originalInput, String effectiveInput, Invocation inv) {
        long start = System.currentTimeMillis();
        boolean success = false;
        RuntimeException failure = null;
        int rawResultChars = -1;
        int returnedResultChars = -1;
        int inputChars = effectiveInput == null ? 0 : effectiveInput.length();
        boolean inputChanged = !stringEquals(originalInput, effectiveInput);
        boolean resultLimited = false;
        try {
            if (isBlockedGithubWriteTool(name)) {
                String result = toolBlockedResult(name);
                rawResultChars = result.length();
                returnedResultChars = result.length();
                success = true;
                return result;
            }

            String rawResult = runInvocation(name, inv);
            rawResultChars = rawResult == null ? 0 : rawResult.length();
            String result = normalizeToolResult(name, rawResult);
            returnedResultChars = result == null ? 0 : result.length();
            resultLimited = returnedResultChars != rawResultChars;
            success = true;
            return result;
        } catch (RuntimeException e) {
            failure = e;
            metrics.recordError(name, e);
            if (returnErrorOnFailure) {
                String result = toolErrorResult(name, e);
                rawResultChars = result.length();
                returnedResultChars = result.length();
                return result;
            }
            throw e;
        } finally {
            long latency = System.currentTimeMillis() - start;
            metrics.recordCall(name, latency, success);
            MDC.put("toolName", name);
            MDC.put("toolLatencyMs", String.valueOf(latency));
            MDC.put("toolInputChars", String.valueOf(inputChars));
            MDC.put("toolRawResultChars", String.valueOf(rawResultChars));
            MDC.put("toolResultChars", String.valueOf(returnedResultChars));
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
            }
        }
    }

    private String runInvocation(String name, Invocation inv) {
        int maxAttempts = mcpToolCallMaxAttempts;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = inv.run();
                // 调用成功，记录时间戳（用于冷连接探活判断）
                if (registry != null && mcpId != null) {
                    registry.recordSuccess(mcpId);
                }
                return result;
            } catch (RuntimeException e) {
                // 超时 → 探活判断：连接活着先重试，连接死了才重建
                // MCP SDK 0.10.0 bug: sendMessage 收到 400 时只打日志不抛异常，
                // 导致 message endpoint 失效但 SSE 流仍活着。probe alive 不代表一定可用，
                // 但可以先重试一次（省掉重建开销），重试失败再重建。
                if (isTimeoutError(e) && registry != null) {
                    boolean alive = registry.probeAfterTimeout(name);
                    if (alive) {
                        // 连接活着（可能是工具确实慢），先用当前连接重试一次
                        log.info("mcp.tool.call TIMEOUT_BUT_ALIVE tool={} attempt={}/{}, retrying with current connection", name, attempt, maxAttempts);
                        try {
                            return inv.run();
                        } catch (RuntimeException retryEx) {
                            // 重试也失败了，需要重建连接
                            log.warn("mcp.tool.call RETRY_FAILED_AFTER_PROBE_ALIVE tool={}, will force reconnect", name);
                            ToolCallback fresh = registry.forceReconnect(name);
                            if (fresh != null) {
                                delegate.set(fresh);
                                log.info("mcp.tool.call RECONNECTED_AFTER_TIMEOUT tool={}", name);
                                try {
                                    return inv.run();
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
                        ToolCallback fresh = registry.forceReconnect(name);
                        if (fresh != null) {
                            delegate.set(fresh);
                            log.info("mcp.tool.call RECONNECTED_AFTER_TIMEOUT tool={}", name);
                            try {
                                return inv.run();
                            } catch (RuntimeException e2) {
                                log.warn("mcp.tool.call RETRY_AFTER_RECONNECT tool={} error={}", name, summarizeFailure(e2));
                                throw e2;
                            }
                        }
                        throw e;
                    }
                }
                // 死客户端（非超时类，如 Connection reset） → 尝试重建
                if (isDeadClientError(e) && registry != null) {
                    log.warn("mcp.tool.call DEAD_CLIENT tool={} attempt={}/{} error={}",
                            name, attempt, maxAttempts, summarizeFailure(e));
                    ToolCallback fresh = registry.getFreshCallback(name);
                    if (fresh != null) {
                        delegate.set(fresh);
                        log.info("mcp.tool.call RECONNECTED tool={}, retrying with fresh delegate", name);
                        try {
                            return inv.run();
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
                    || msg.contains("Failed to wait for the message endpoint"))) {
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
                + "\"message\":\"GitHub write tools are disabled for this agent run. Answer directly unless the user explicitly requests GitHub write operations and agent.mcp.github.write-enabled is true.\"}";
    }

    private String toolErrorResult(String name, RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return "{\"ok\":false,\"tool\":\"" + jsonEscape(name)
                + "\",\"error\":\"" + jsonEscape(message)
                + "\",\"message\":\"Tool call failed. Continue without this tool or try another path.\"}";
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
