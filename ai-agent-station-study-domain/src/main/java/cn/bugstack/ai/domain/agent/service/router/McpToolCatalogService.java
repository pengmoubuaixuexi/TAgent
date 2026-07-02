package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.armory.node.AiClientToolMcpNode;
import cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.DynamicToolUnavailableException;
import cn.bugstack.ai.domain.agent.service.execute.common.ResolvedToolLease;
import cn.bugstack.ai.domain.agent.service.execute.common.ResolvedToolLeaseStore;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolPromptHintRegistry;
import cn.bugstack.ai.domain.agent.service.security.HumanApprovalGate;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 动态 MCP 工具发现：由持久化的工具目录表（ai_mcp_tool_catalog）驱动。
 *
 * <p>匹配走 {@link IToolVectorStore} 的 PgVector 语义相似度——把路由给出的"缺失工具描述"(need)
 * embed 后对目录里每条工具的语义向量取 cosine top-N。纯词法 BM25 在"同义词错位 + 多个'搜索X'工具"下
 * 结构性分不开，已弃用；embedding 跨同义词、分领域，又快(~150ms)。可选再加一道 LLM rerank 精排（默认关）。
 * 向量库不可用/为空时不补工具（不再回退词法）。
 */
@Slf4j
@Service
public class McpToolCatalogService {

    @Resource
    private IAgentRepository repository;

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

    @Resource
    private McpClientRegistry mcpClientRegistry;

    @Resource
    private McpToolMetrics mcpToolMetrics;

    @Resource
    private ToolPromptHintRegistry toolPromptHintRegistry;

    @Resource
    private HumanApprovalGate humanApprovalGate;

    @Resource
    private ToolCallProgressEmitter toolCallProgressEmitter;

    /** P0（Codex #2）工具调用事实摘要台账；动态 request_tool 装载的工具也要记账，否则 Auto Step3 对动态工具(如 search_papers)仍误判编造。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolCallLedger toolCallLedger;

    @Resource
    private ToolDescriptionTranslator toolDescriptionTranslator;

    @Resource
    private IToolVectorStore toolVectorStore;

    /** P2-A1：run 级动态工具租约；可选，缺失时完全退回旧行为。 */
    @Autowired(required = false)
    private ResolvedToolLeaseStore resolvedToolLeaseStore;

    /**
     * 每条 need 各取 embedding top-k（默认 2）。一次 query 可能有多条 need（多类能力），
     * 每条各自取 top-k、最后并集去重——见 {@link #resolveDynamicToolCallbacks}。
     */
    @Value("${agent.dynamic-tools.per-need-top-k:2}")
    private int perNeedTopK;

    /**
     * 并集后补挂工具的<b>总量上限</b>（安全闸，正常吃不到）。多条 need × 每条 top-k 去重后若超过此数，按 need 顺序截断。
     * 默认 6 ≈ 容纳 3 条 need。
     */
    @Value("${agent.dynamic-tools.max-extra-tools-per-request:6}")
    private int maxExtraToolsPerRequest;

    /**
     * 单条 need 的匹配结果按 need 缓存的 TTL(毫秒)。一个 agent 一次请求会按 step 多次调到这里、且多条 need 可能重复，
     * 同一 need 只算一次 embedding 检索，别重复。刷新目录时清空。默认 10 分钟。
     */
    @Value("${agent.dynamic-tools.match-cache-ttl-ms:600000}")
    private long matchCacheTtlMs;

    private final Map<String, MatchCacheEntry> matchCache = new ConcurrentHashMap<>();

    private record MatchCacheEntry(List<AiMcpToolCatalogVO> tools, long expireAtMs) {}

    private record MatchedTool(String need, AiMcpToolCatalogVO tool) {}

    private record ToolIdentity(String mcpId, String toolName, String definitionHash) {}

    @Value("${agent.dynamic-tools.catalog.auto-refresh-enabled:false}")
    private boolean autoRefreshCatalogEnabled;

    @Value("${agent.mcp.return-error-on-failure:true}")
    private boolean returnToolErrorOnFailure;

    @Value("${agent.mcp.github.write-enabled:false}")
    private boolean githubWriteEnabled;

    @Value("${agent.mcp.github.search.max-per-page:10}")
    private int githubSearchMaxPerPage;

    @Value("${agent.mcp.github.search.max-result-chars:20000}")
    private int githubSearchMaxResultChars;

    @Value("${agent.mcp.github.search.compact-result-enabled:false}")
    private boolean githubSearchCompactResultEnabled;

    @Value("${agent.mcp.aisearch.strip-server-llm:true}")
    private boolean aiSearchStripServerLlm;

    @Value("${agent.mcp.tool-call.max-attempts:2}")
    private int mcpToolCallMaxAttempts;

    @Value("${agent.mcp.tool-call.retry-delay-ms:1000}")
    private long mcpToolCallRetryDelayMs;

    private final Map<String, Object> mcpLocks = new ConcurrentHashMap<>();

    private final Map<String, ToolCallback> dynamicWrapperCache = new ConcurrentHashMap<>();

    /**
     * 本次执行（按 sessionId）已知的"缺失工具能力描述"(need，多条换行连接)。统一来源：
     * <ul>
     *   <li>路由/选定 agent 推断的 need 由 dispatch 层 {@link #setNeeds} 写入（替换式）；</li>
     *   <li>执行中途 request_tool 由 manager {@link #appendNeed} 追加（去重累加）；</li>
     *   <li>每个 step 经 {@link #needsFor} 读取后 resolve 补挂工具。</li>
     * </ul>
     * 退休了原 {@code ExecuteCommandEntity.dynamicMissingToolDesc} 字段（请求级自动 GC）→
     * 改用本 map 后<b>必须</b>在执行结束 {@link #clearNeeds} 显式清理，否则泄漏 + 串请求。
     */
    private final Map<String, String> sessionNeeds = new ConcurrentHashMap<>();

    /** 路由层写入本次执行的 need（多条已换行连接）。空串视为清除。 */
    public void setNeeds(String sessionId, String joinedNeeds) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (joinedNeeds == null || joinedNeeds.isBlank()) {
            sessionNeeds.remove(sessionId);
            return;
        }
        sessionNeeds.put(sessionId, joinedNeeds.trim());
    }

    /** request_tool：执行中途追加一条 need（按行去重、换行累加），供后续 step resolve。 */
    public void appendNeed(String sessionId, String need) {
        if (sessionId == null || sessionId.isBlank() || need == null || need.isBlank()) return;
        String add = need.trim();
        sessionNeeds.merge(sessionId, add, (old, n) -> {
            for (String line : old.split("\\r?\\n")) {
                if (line.trim().equals(n)) return old; // 已含该 need，不重复
            }
            return old + "\n" + n;
        });
    }

    /** 取本次执行已知的 need（路由 + 中途 request_tool 合并后的换行串）；无则 null。 */
    public String needsFor(String sessionId) {
        return sessionId == null ? null : sessionNeeds.get(sessionId);
    }

    /** 执行结束清理（必须调，否则 sessionId 维度泄漏 + 串请求）。 */
    public void clearNeeds(String sessionId) {
        if (sessionId != null) sessionNeeds.remove(sessionId);
    }

    @Value("${agent.dynamic-tools.catalog.warmup-on-startup:false}")
    private boolean warmupOnStartup;

    /**
     * 根据路由给出的"缺失工具描述"（可<b>多条</b>，换行分隔），从工具目录里语义匹配出要补的工具回调。
     * 每条 need 各取 embedding top-k，按 need 顺序<b>并集去重</b>、排除已挂工具、截到总量上限。
     *
     * @param clientId        当前 agent 的 clientId（仅用于日志 / 排除已挂工具）
     * @param missingToolDesc 路由产出的"可能缺失的工具能力"中文描述；多条用换行连接；为空则不补工具
     * @param query           用户问题（当前仅日志用，匹配只用 missingToolDesc，避免噪声）
     * @param currentTools    该 agent 已挂载的工具，命中的会被排除
     */
    public List<ToolCallback> resolveDynamicToolCallbacks(String clientId,
                                                          String missingToolDesc,
                                                          String query,
                                                          List<AgentToolRegistry.ToolInfo> currentTools) {
        return resolveDynamicToolCallbacks(null, null, clientId, missingToolDesc, query, currentTools);
    }

    /**
     * P2-A1：带 runId 的动态工具解析。
     * <p>有 runId + leaseStore 时，后续 step 优先重物化本 run 里已解析过的 tool identity；
     * 未覆盖的 need 才走原 embedding/top-k。无 runId 时保持旧行为，便于迁移期回退。
     */
    public List<ToolCallback> resolveDynamicToolCallbacks(String runId,
                                                          String sessionId,
                                                          String clientId,
                                                          String missingToolDesc,
                                                          String query,
                                                          List<AgentToolRegistry.ToolInfo> currentTools) {
        List<String> needs = splitNeeds(missingToolDesc);
        if (needs.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> currentToolNames = currentTools == null ? Set.of() : currentTools.stream()
                .filter(t -> t != null && t.name() != null)
                .map(AgentToolRegistry.ToolInfo::name)
                .collect(Collectors.toSet());

        boolean leaseEnabled = runId != null && !runId.isBlank() && resolvedToolLeaseStore != null;
        if (leaseEnabled) {
            List<ToolCallback> result = new ArrayList<>();
            Set<String> resultToolNames = new LinkedHashSet<>();
            Set<String> coveredNeeds = new LinkedHashSet<>();

            for (ResolvedToolLease lease : resolvedToolLeaseStore.listLeases(runId)) {
                if (lease == null || !needs.contains(lease.originalNeed()) || !lease.isAvailable()) {
                    continue;
                }
                ToolCallback callback;
                try {
                    callback = materializeLease(lease);
                } catch (DynamicToolUnavailableException e) {
                    log.warn("[DynamicTools] skip unavailable leased tool runId={} need={} identity={} reason={}",
                            runId, lease.originalNeed(), lease.toolIdentity(), e.getMessage());
                    continue;
                } catch (Exception e) {
                    log.warn("[DynamicTools] skip failed leased tool runId={} need={} identity={} error={}",
                            runId, lease.originalNeed(), lease.toolIdentity(), e.toString());
                    continue;
                }
                String name = callback != null && callback.getToolDefinition() != null
                        ? callback.getToolDefinition().name() : null;
                coveredNeeds.add(lease.originalNeed());
                if (name == null || currentToolNames.contains(name) || !resultToolNames.add(name)) {
                    continue;
                }
                result.add(callback);
            }

            List<String> uncoveredNeeds = needs.stream()
                    .filter(n -> !coveredNeeds.contains(n))
                    .toList();
            List<MatchedTool> matched = matchToolsByNeed(uncoveredNeeds, query, currentToolNames, resultToolNames);
            if (matched.isEmpty() && !uncoveredNeeds.isEmpty() && autoRefreshCatalogEnabled) {
                log.info("[DynamicTools] no lease/uncovered match for clientId={} runId={} needs={}, refreshing enabled MCP catalog",
                        clientId, runId, uncoveredNeeds);
                refreshEnabledMcpCatalog();
                matched = matchToolsByNeed(uncoveredNeeds, query, currentToolNames, resultToolNames);
            }
            for (MatchedTool mt : matched) {
                ToolCallback callback;
                try {
                    callback = ensureToolCallback(mt.tool());
                } catch (Exception e) {
                    log.warn("[DynamicTools] skip failed matched tool runId={} need={} mcpId={} tool={} error={}",
                            runId, mt.need(),
                            mt.tool() != null ? mt.tool().getMcpId() : null,
                            mt.tool() != null ? mt.tool().getToolName() : null,
                            e.toString());
                    continue;
                }
                if (callback != null) {
                    String name = callback.getToolDefinition() != null ? callback.getToolDefinition().name() : null;
                    if (name != null && resultToolNames.add(name)) {
                        String identity = toolIdentity(mt.tool(), callback);
                        resolvedToolLeaseStore.createOrMerge(runId, sessionId, mt.need(), identity);
                        result.add(callback);
                    }
                }
            }

            if (!result.isEmpty()) {
                log.info("[DynamicTools] clientId={} runId={} needs={} materializedOrMatchedTools={}", clientId, runId, needs,
                        result.stream().map(cb -> cb.getToolDefinition().name()).toList());
            } else {
                log.info("[DynamicTools] clientId={} runId={} needs={} no usable catalog tool", clientId, runId, needs);
            }
            return result;
        }

        // 每条 need 各取 embedding top-k，并集去重；语义匹配走 PgVector，命中按 need 请求级缓存复用。
        List<AiMcpToolCatalogVO> matched = matchUnion(needs, query, currentToolNames);
        if (matched.isEmpty() && autoRefreshCatalogEnabled) {
            log.info("[DynamicTools] no match for clientId={} needs={}, refreshing enabled MCP catalog",
                    clientId, needs);
            refreshEnabledMcpCatalog();
            matched = matchUnion(needs, query, currentToolNames);
        }

        List<ToolCallback> result = new ArrayList<>();
        for (AiMcpToolCatalogVO tool : matched) {
            ToolCallback callback;
            try {
                callback = ensureToolCallback(tool);
            } catch (Exception e) {
                log.warn("[DynamicTools] skip failed matched tool clientId={} mcpId={} tool={} error={}",
                        clientId,
                        tool != null ? tool.getMcpId() : null,
                        tool != null ? tool.getToolName() : null,
                        e.toString());
                continue;
            }
            if (callback != null) {
                result.add(callback);
            }
        }

        if (!result.isEmpty()) {
            log.info("[DynamicTools] clientId={} needs={} matchedCatalogTools={}", clientId, needs,
                    result.stream().map(cb -> cb.getToolDefinition().name()).toList());
        } else {
            log.info("[DynamicTools] clientId={} needs={} no usable catalog tool", clientId, needs);
        }
        return result;
    }

    /** 清理 run 级 lease；供 dispatch/strategy finally 调用。 */
    public void cleanupRun(String runId) {
        if (resolvedToolLeaseStore != null && runId != null && !runId.isBlank()) {
            resolvedToolLeaseStore.cleanupRun(runId);
        }
    }

    /**
     * 多条 need 各取 embedding top-k，按 need 顺序<b>并集去重</b>、排除已挂工具、截到总量上限。
     * 向量库空/不可用（端点挂了、或没 refresh 过）时不补工具——不再回退词法。
     */
    private List<AiMcpToolCatalogVO> matchUnion(List<String> needs, String query, Set<String> currentToolNames) {
        return matchToolsByNeed(needs, query, currentToolNames, Set.of()).stream()
                .map(MatchedTool::tool)
                .toList();
    }

    /**
     * 多条 need 各取 embedding top-k，同时保留 tool ← need 的来源，供 P2-A1 建 lease(originalNeed, identity)。
     */
    private List<MatchedTool> matchToolsByNeed(List<String> needs, String query, Set<String> currentToolNames, Set<String> alreadySelectedNames) {
        if (needs == null || needs.isEmpty()) {
            return Collections.emptyList();
        }
        if (!toolVectorStore.isAvailable()) {
            log.warn("[DynamicTools] tool vector store empty/unavailable — 请先 POST /tool-catalog/refresh 灌向量；skip supplement (needs={})", needs);
            return Collections.emptyList();
        }
        int topK = perNeedTopK > 0 ? perNeedTopK : 2;
        int cap = maxExtraToolsPerRequest > 0 ? maxExtraToolsPerRequest : 6;
        Set<String> exclude = currentToolNames == null ? Set.of() : currentToolNames;
        Set<String> already = alreadySelectedNames == null ? Set.of() : alreadySelectedNames;
        LinkedHashMap<String, MatchedTool> union = new LinkedHashMap<>();
        for (String need : needs) {
            for (AiMcpToolCatalogVO tool : cachedMatch(need, topK)) {
                String name = tool.getToolName();
                if (name == null || exclude.contains(name) || already.contains(name) || union.containsKey(name)) {
                    continue;
                }
                union.put(name, new MatchedTool(need, tool));
                if (already.size() + union.size() >= cap) break;
            }
            if (already.size() + union.size() >= cap) break;
        }
        return new ArrayList<>(union.values());
    }

    private ToolCallback materializeLease(ResolvedToolLease lease) {
        ToolIdentity identity = parseToolIdentity(lease.toolIdentity());
        if (identity == null) {
            markLeaseInvalidated(lease, ResolvedToolLease.Availability.INVALIDATED);
            throw new DynamicToolUnavailableException(lease.toolIdentity(), ResolvedToolLease.Availability.INVALIDATED);
        }
        if (!mcpClientRegistry.hasClient(identity.mcpId())) {
            markLeaseInvalidated(lease, ResolvedToolLease.Availability.MCP_DOWN);
            log.warn("[DynamicTools][Lease] MCP down for lease runId={} identity={}", lease.runId(), lease.toolIdentity());
            throw new DynamicToolUnavailableException(lease.toolIdentity(), ResolvedToolLease.Availability.MCP_DOWN);
        }

        ToolCallback current = mcpClientRegistry.getCurrentCallback(identity.toolName());
        if (current == null) {
            current = ensureToolCallback(AiMcpToolCatalogVO.builder()
                    .mcpId(identity.mcpId())
                    .toolName(identity.toolName())
                    .build());
        }
        if (current == null) {
            markLeaseInvalidated(lease, ResolvedToolLease.Availability.INVALIDATED);
            throw new DynamicToolUnavailableException(lease.toolIdentity(), ResolvedToolLease.Availability.INVALIDATED);
        }

        String actualHash = definitionHash(readInputSchema(current));
        if (!identity.definitionHash().equals(actualHash)) {
            markLeaseInvalidated(lease, ResolvedToolLease.Availability.INVALIDATED);
            log.warn("[DynamicTools][Lease] definition hash changed runId={} identity={} actual={}",
                    lease.runId(), lease.toolIdentity(), actualHash);
            throw new DynamicToolUnavailableException(lease.toolIdentity(), ResolvedToolLease.Availability.INVALIDATED);
        }

        ToolCallback wrapped = ensureToolCallback(AiMcpToolCatalogVO.builder()
                .mcpId(identity.mcpId())
                .toolName(identity.toolName())
                .build());
        return wrapped != null ? wrapped : current;
    }

    private void markLeaseInvalidated(ResolvedToolLease lease, ResolvedToolLease.Availability reason) {
        if (resolvedToolLeaseStore != null && lease != null) {
            resolvedToolLeaseStore.markInvalidated(lease.runId(), lease.toolIdentity(), reason);
        }
    }

    private String toolIdentity(AiMcpToolCatalogVO tool, ToolCallback callback) {
        String mcpId = safe(tool != null ? tool.getMcpId() : null);
        String toolName = callback != null && callback.getToolDefinition() != null
                ? safe(callback.getToolDefinition().name())
                : safe(tool != null ? tool.getToolName() : null);
        String schema = callback != null ? readInputSchema(callback)
                : safe(tool != null ? tool.getInputSchemaJson() : null);
        return mcpId + ":" + toolName + ":" + definitionHash(schema);
    }

    private ToolIdentity parseToolIdentity(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split(":", 3);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return new ToolIdentity(parts[0], parts[1], parts[2]);
    }

    private String definitionHash(String schema) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(safe(schema).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 单条 need 的 embedding top-k 命中，按 need 缓存 TTL 内复用（多 step / 多条重复 need 不重算）；刷新清空。
     * 排除已挂工具留给 {@link #matchUnion} 并集时统一做，所以这里缓存的是原始命中。
     */
    private List<AiMcpToolCatalogVO> cachedMatch(String need, int limit) {
        String key = need.trim();
        long now = System.currentTimeMillis();
        MatchCacheEntry cached = matchCache.get(key);
        if (cached != null && cached.expireAtMs() > now) {
            return cached.tools();
        }
        List<AiMcpToolCatalogVO> tools = primaryMatch(need, Set.of(), limit);
        matchCache.put(key, new MatchCacheEntry(tools, now + Math.max(0, matchCacheTtlMs)));
        return tools;
    }

    /** 主匹配器：PgVector 语义检索直接取 top-k。向量库出错(返回 null)/无命中都返回空，不再回退词法。 */
    private List<AiMcpToolCatalogVO> primaryMatch(String need, Set<String> exclude, int limit) {
        List<AiMcpToolCatalogVO> hits = toolVectorStore.search(need, exclude, limit);
        return hits == null ? Collections.emptyList() : hits;
    }

    /** 把换行连成的多条 need 串拆回列表（去空白、去空行、按序去重）。 */
    private List<String> splitNeeds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> needs = new LinkedHashSet<>();
        for (String line : joined.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isBlank()) needs.add(t);
        }
        return new ArrayList<>(needs);
    }

    /**
     * 预览：给定 need（可多条，换行分隔）/query 最终会补哪些<b>目录工具</b>（每条 need 取 embedding top-k 并集，
     * 但<b>不</b>连 MCP 物化回调）。供诊断 / 全链路测试看"补到哪些工具"，避免在测试里连 MCP。
     */
    public List<AiMcpToolCatalogVO> previewMatchedTools(String missingToolDesc, String query, Set<String> currentToolNames) {
        return matchUnion(splitNeeds(missingToolDesc), query, currentToolNames == null ? Set.of() : currentToolNames);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupCatalogOnStartup() {
        if (!warmupOnStartup) {
            return;
        }
        try {
            log.info("[DynamicTools] warmup MCP tool catalog on startup");
            int count = refreshEnabledMcpCatalog();
            log.info("[DynamicTools] warmup MCP tool catalog done, tools={}", count);
        } catch (Exception e) {
            log.warn("[DynamicTools] warmup MCP tool catalog failed: {}", e.toString());
        }
    }

    public int refreshEnabledMcpCatalog() {
        List<AiClientToolMcpVO> mcpConfigs = repository.queryEnabledAiClientToolMcpVOList();
        if (mcpConfigs == null || mcpConfigs.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AiClientToolMcpVO config : mcpConfigs) {
            try {
                ToolCallback[] callbacks = ensureMcpCallbacks(config);
                upsertCatalog(config, callbacks);
                total += callbacks == null ? 0 : callbacks.length;
            } catch (Exception e) {
                log.warn("[DynamicTools] refresh catalog failed mcpId={} name={}: {}",
                        config.getMcpId(), config.getMcpName(), e.toString());
            }
        }
        rebuildIndexes();
        return total;
    }

    public int refreshMcpCatalog(String mcpId) {
        AiClientToolMcpVO config = repository.queryAiClientToolMcpVOByMcpId(mcpId);
        if (config == null) {
            log.warn("[DynamicTools] refresh catalog skipped, enabled mcp config missing: {}", mcpId);
            return 0;
        }
        ToolCallback[] callbacks = ensureMcpCallbacks(config);
        upsertCatalog(config, callbacks);
        rebuildIndexes();
        return callbacks == null ? 0 : callbacks.length;
    }

    /** 刷新目录后同步向量库(PgVector：embed 写库) 并清空按 need 的匹配缓存。 */
    private void rebuildIndexes() {
        try {
            toolVectorStore.syncAll(repository.queryEnabledMcpToolCatalog());
        } catch (Exception e) {
            log.warn("[DynamicTools] tool vector sync failed: {}", e.toString());
        }
        matchCache.clear();
    }

    private ToolCallback ensureToolCallback(AiMcpToolCatalogVO catalogTool) {
        if (catalogTool == null || catalogTool.getMcpId() == null || catalogTool.getToolName() == null) {
            return null;
        }
        String cacheKey = catalogTool.getMcpId() + "::" + catalogTool.getToolName();
        ToolCallback cached = dynamicWrapperCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        synchronized (mcpLocks.computeIfAbsent(catalogTool.getMcpId(), k -> new Object())) {
            cached = dynamicWrapperCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            ToolCallback raw = mcpClientRegistry.getCurrentCallback(catalogTool.getToolName());
            if (raw == null) {
                AiClientToolMcpVO config = repository.queryAiClientToolMcpVOByMcpId(catalogTool.getMcpId());
                if (config == null) {
                    log.warn("[DynamicTools] mcp config missing for mcpId={}, tool={}",
                            catalogTool.getMcpId(), catalogTool.getToolName());
                    return null;
                }
                ToolCallback[] callbacks = ensureMcpCallbacks(config);
                upsertCatalog(config, callbacks);
                raw = findTool(callbacks, catalogTool.getToolName());
            }
            if (raw == null) {
                log.warn("[DynamicTools] tool not found after mcp init mcpId={} tool={}",
                        catalogTool.getMcpId(), catalogTool.getToolName());
                return null;
            }

            ToolCallback wrapped = wrapToolCallback(raw, catalogTool.getMcpId());
            dynamicWrapperCache.put(cacheKey, wrapped);
            return wrapped;
        }
    }

    private ToolCallback[] ensureMcpCallbacks(AiClientToolMcpVO config) {
        synchronized (mcpLocks.computeIfAbsent(config.getMcpId(), k -> new Object())) {
            McpSyncClient client = mcpClientRegistry.getClient(config.getMcpId());
            if (client == null) {
                client = aiClientToolMcpNode.createMcpSyncClient(config);
                aiClientToolMcpNode.registerMcpBean(config.getMcpId(), client);
                mcpClientRegistry.register(config.getMcpId(), config, client, aiClientToolMcpNode::createMcpSyncClient);
            }
            ToolCallback[] callbacks;
            try {
                callbacks = mcpClientRegistry.getToolCallbacksForAssembly(config.getMcpId(), client);
            } catch (Exception ex) {
                log.warn("[DynamicTools] mcp callbacks unavailable after reconnect mcpId={}: {}",
                        config.getMcpId(), ex.toString());
                return new ToolCallback[0];
            }
            mcpClientRegistry.registerCallbacks(config.getMcpId(), callbacks);
            return callbacks;
        }
    }

    /**
     * 增量同步某个 MCP 的工具到目录：name+description 都没变(且已有中文翻译)的<b>不动、不翻译</b>；
     * 只对新增/描述变更的工具翻译并写入；MCP 已不再上报的工具从目录删除。
     * 这样手动刷新时不会全量重翻，省 LLM 调用和时间。
     *
     * <p>callbacks 为空时直接返回(可能是连接抖动)，<b>不删</b>目录，避免误清空。
     */
    private void upsertCatalog(AiClientToolMcpVO config, ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return;
        }
        String mcpId = config.getMcpId();

        // 1. MCP 当前上报的工具：name -> rawDesc / callback（按名去重）
        Map<String, String> currentRawDesc = new LinkedHashMap<>();
        Map<String, ToolCallback> currentCallback = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) continue;
            String toolName = safe(callback.getToolDefinition().name());
            if (toolName.isBlank() || currentRawDesc.containsKey(toolName)) continue;
            currentRawDesc.put(toolName, safe(callback.getToolDefinition().description()));
            currentCallback.put(toolName, callback);
        }
        if (currentRawDesc.isEmpty()) {
            return;
        }

        // 2. 读已有目录行：name -> 现有 VO
        Map<String, AiMcpToolCatalogVO> existing = new HashMap<>();
        for (AiMcpToolCatalogVO row : repository.queryMcpToolCatalogByMcpId(mcpId)) {
            if (row != null && row.getToolName() != null) {
                existing.put(row.getToolName(), row);
            }
        }

        // 3. 删除：库里有、MCP 已不再上报的工具
        List<String> removed = new ArrayList<>();
        for (String name : existing.keySet()) {
            if (!currentRawDesc.containsKey(name)) {
                removed.add(name);
            }
        }
        if (!removed.isEmpty()) {
            repository.deleteMcpToolCatalog(mcpId, removed);
            log.info("[DynamicTools] catalog mcpId={} removed stale tools={}", mcpId, removed);
        }

        // 4. 找新增/变更：库里没有 || 描述变了 || 中文用途为空 || 意图扩写(doc2query)为空。只对这批增强。
        List<String> changedNames = new ArrayList<>();
        List<String> changedRaws = new ArrayList<>();
        for (Map.Entry<String, String> entry : currentRawDesc.entrySet()) {
            AiMcpToolCatalogVO old = existing.get(entry.getKey());
            boolean unchanged = old != null
                    && java.util.Objects.equals(safe(old.getToolDescription()), entry.getValue())
                    && old.getToolDescriptionZh() != null && !old.getToolDescriptionZh().isBlank()
                    && old.getToolIntentZh() != null && !old.getToolIntentZh().isBlank();
            if (!unchanged) {
                changedNames.add(entry.getKey());
                changedRaws.add(entry.getValue());
            }
        }
        if (changedNames.isEmpty()) {
            log.info("[DynamicTools] catalog mcpId={} unchanged ({} tools), skip enrichment; removed {}",
                    mcpId, currentRawDesc.size(), removed.size());
            return;
        }

        // 5. 只增强变更的：一次 LLM 调用产出 {中文用途, doc2query 意图扩写}，写回
        List<ToolDescriptionTranslator.ToolText> texts =
                toolDescriptionTranslator.toCatalogText(changedNames, changedRaws);
        LocalDateTime now = LocalDateTime.now();
        List<AiMcpToolCatalogVO> rows = new ArrayList<>(changedNames.size());
        for (int i = 0; i < changedNames.size(); i++) {
            String name = changedNames.get(i);
            ToolDescriptionTranslator.ToolText text = texts.get(i);
            rows.add(AiMcpToolCatalogVO.builder()
                    .mcpId(mcpId)
                    .mcpName(config.getMcpName())
                    .toolName(name)
                    .toolDescription(changedRaws.get(i))
                    .toolDescriptionZh(text.zh())
                    .toolIntentZh(text.intentZh())
                    .inputSchemaJson(readInputSchema(currentCallback.get(name)))
                    .enabled(1)
                    .lastSeenAt(now)
                    .build());
        }
        repository.upsertMcpToolCatalog(rows);
        log.info("[DynamicTools] catalog mcpId={} upserted {} add/changed tools (translated), removed {}, unchanged {}",
                mcpId, rows.size(), removed.size(), currentRawDesc.size() - changedNames.size());
    }

    private ToolCallback wrapToolCallback(ToolCallback raw, String mcpId) {
        String toolName = raw.getToolDefinition() != null ? raw.getToolDefinition().name() : "";
        String hint = toolPromptHintRegistry != null ? toolPromptHintRegistry.getHint(toolName) : null;
        ToolCallback hinted = (hint != null && !hint.isBlank())
                ? new HintedToolCallback(raw, hint)
                : raw;
        MeteredToolCallback metered = new MeteredToolCallback(hinted, mcpToolMetrics,
                returnToolErrorOnFailure, githubWriteEnabled,
                githubSearchMaxPerPage, githubSearchMaxResultChars,
                githubSearchCompactResultEnabled, aiSearchStripServerLlm,
                mcpToolCallMaxAttempts, mcpToolCallRetryDelayMs,
                mcpClientRegistry, mcpId);
        metered.setHumanApprovalGate(humanApprovalGate);
        metered.setToolCallProgressEmitter(toolCallProgressEmitter);
        metered.setToolCallLedger(toolCallLedger); // P0 Codex#2：动态工具也记账，Step3 才看得到 search_papers 等 request_tool 装载的工具
        return metered;
    }

    private ToolCallback findTool(ToolCallback[] callbacks, String toolName) {
        if (callbacks == null || toolName == null) {
            return null;
        }
        for (ToolCallback callback : callbacks) {
            if (callback != null && callback.getToolDefinition() != null
                    && toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    private String readInputSchema(ToolCallback callback) {
        try {
            Object definition = callback.getToolDefinition();
            Method method = definition.getClass().getMethod("inputSchema");
            Object schema = method.invoke(definition);
            return schema == null ? "" : String.valueOf(schema);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
