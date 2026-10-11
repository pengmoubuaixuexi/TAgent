package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * MCP 客户端注册表 —— 管理 MCP 客户端生命周期、健康检测和自动重建。
 * <p>
 * 当 SSE 连接死亡导致 McpSyncClient 不可用时，从此注册表中取出存储的配置重建客户端，
 * 并原子替换所有 ToolCallback 引用，使所有 agent 自动恢复。
 */
@Slf4j
@Component
public class McpClientRegistry {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000L;
    private static final long IDLE_THRESHOLD_MS = 5 * 60 * 1000L; // 5 分钟
    private static final long PROBE_TIMEOUT_MS = 500L; // 探活超时 500ms（正常 listTools 130ms 左右）
    private static final long RECONNECT_COOLDOWN_MS = 10_000L; // 重连冷却期 10 秒

    @Resource
    private ApplicationContext applicationContext;

    /** 给 Observe 治理面用——重连/冷却计数，可为 null（让单测/旧调用方零成本接入） */
    @Autowired(required = false)
    private McpToolMetrics mcpToolMetrics;

    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository workspaceAccess;
    @Autowired(required=false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository agentRepository;
    @org.springframework.beans.factory.annotation.Value("${agent.mcp.pool.max-connections:64}") private int maxConnections=64;
    @org.springframework.beans.factory.annotation.Value("${agent.mcp.pool.max-per-user:16}") private int maxPerUser=16;
    @org.springframework.beans.factory.annotation.Value("${agent.mcp.pool.idle-ms:900000}") private long poolIdleMs=900000;
    private final McpConnectionPool pool=new McpConnectionPool();
    private final Map<String,String> capacityOwners=new ConcurrentHashMap<>();
    private final com.google.common.cache.Cache<String,Long> evictedAt=com.google.common.cache.CacheBuilder.newBuilder()
            .maximumSize(4096).expireAfterWrite(24,TimeUnit.HOURS).build();
    private final com.google.common.cache.Cache<String,Long> lastUseBeforeEviction=com.google.common.cache.CacheBuilder.newBuilder()
            .maximumSize(4096).expireAfterWrite(24,TimeUnit.HOURS).build();
    private volatile Function<AiClientToolMcpVO,McpSyncClient> defaultFactory;

    /** Reserve capacity before any transport/socket/process is created. Both eager and lazy paths use this. */
    public McpSyncClient ensureClient(AiClientToolMcpVO candidate,Function<AiClientToolMcpVO,McpSyncClient> factory) {
        String id=candidate.getMcpId();
        synchronized(locks.computeIfAbsent(id,k->new Object())) {
            AiClientToolMcpVO config=agentRepository==null?candidate:agentRepository.queryAiClientToolMcpVOByMcpId(id);
            if(config==null)throw new IllegalStateException("MCP 配置已删除或停用");
            String owner=workspaceAccess==null?capacityOwners.get(id):workspaceAccess.mcpCapacityOwner(id);
            if(owner==null||owner.isBlank()) {
                if(workspaceAccess!=null&&workspaceAccess.trustedSystemMcp(id)&&(org.slf4j.MDC.get("userId")==null||org.slf4j.MDC.get("userId").isBlank()))owner="SYSTEM";
                else throw new IllegalStateException("MCP 配置缺少可信的归属，不能创建个人连接");
            }
            McpConnectionPool.Slot active=pool.current(id);
            if(active!=null&&active.client!=null) {
                if (sameTransport(configRegistry.get(id), config)) return active.client;
                // Source credential rotation is authoritative even if an admin invalidation failed
                // or the row was changed outside the UI. Never send another call on stale auth.
                unregister(id);
                clientFactories.put(id, factory);
            }
            makeRoom(owner,id);McpConnectionPool.Slot reservation=pool.reserve(id,owner,Math.max(1,maxConnections),Math.max(1,maxPerUser));
            McpSyncClient client=null;
            try {
                client=factory.apply(config);pool.commit(reservation,client);
                capacityOwners.put(id,owner);defaultFactory=factory;
                registerState(id,config,client,factory);registerBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(id),McpSyncClient.class,client);
                evictedAt.invalidate(id);return client;
            } catch(RuntimeException|Error failure){pool.abort(reservation);closeQuietly(client);if(client!=null)clientRegistry.remove(id,client);throw failure;}
        }
    }
    private void makeRoom(String owner,String exclude) {
        if(pool.hasCapacity(owner,Math.max(1,maxConnections),Math.max(1,maxPerUser)))return;
        // Prefer this owner's oldest unused generation so one user cannot evict everyone else's working set.
        var candidates=pool.idleCandidates(System.currentTimeMillis());
        candidates=new ArrayList<>(candidates);candidates.sort(java.util.Comparator.comparing(id->!owner.equals(capacityOwners.get(id))));
        for(String id:candidates){if(id.equals(exclude))continue;evictIdle(id,System.currentTimeMillis());if(pool.hasCapacity(owner,Math.max(1,maxConnections),Math.max(1,maxPerUser)))return;}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${agent.mcp.pool.sweep-ms:60000}")
    public void evictIdleConnections(){long cutoff=System.currentTimeMillis()-Math.max(1000,poolIdleMs);for(String id:pool.idleCandidates(cutoff))evictIdle(id,cutoff);}
    private void evictIdle(String id,long cutoff) {
        // Never wait on another connection's creation lock while holding our own admission lock.
        McpConnectionPool.Slot slot=pool.current(id);if(slot==null||slot.lastUsed>cutoff)return;
        McpSyncClient closed=pool.retireIfIdle(id,slot.client,cutoff);if(closed==null)return;
        clientRegistry.remove(id,closed);evictedAt.put(id,System.currentTimeMillis());lastUseBeforeEviction.put(id,slot.lastUsed);closeQuietly(closed);
        // Leave the bean routing slot alone here: a concurrent recreation may already have replaced it.
    }
    private static final class SharedLease {final McpConnectionPool.Lease lease;int references;SharedLease(McpConnectionPool.Lease lease){this.lease=lease;}}
    private final ThreadLocal<Map<String,SharedLease>> callScopes=ThreadLocal.withInitial(java.util.HashMap::new);
    public final class CallLease implements AutoCloseable {
        private final String id;private final SharedLease scope;private boolean closed;
        private CallLease(String id,SharedLease scope){this.id=id;this.scope=scope;scope.references++;}
        public McpSyncClient client(){return scope.lease.client();}
        @Override public void close(){if(closed)return;closed=true;if(--scope.references==0){callScopes.get().remove(id,scope);if(callScopes.get().isEmpty())callScopes.remove();scope.lease.close();}}
    }
    /** Callback registration-only test/legacy registries have no transport to lease. */
    public CallLease borrowCall(String id) {
        if(id!=null&&agentRepository!=null&&agentRepository.queryAiClientToolMcpVOByMcpId(id)==null)
            throw new IllegalStateException("MCP 配置已删除或停用");
        if(id!=null&&workspaceAccess!=null&&workspaceAccess.mcpCapacityOwner(id)==null&&!workspaceAccess.trustedSystemMcp(id))
            throw new IllegalStateException("MCP 配置已删除或停用");
        SharedLease existing=id==null?null:callScopes.get().get(id);if(existing!=null)return new CallLease(id,existing);
        if(id==null||!configRegistry.containsKey(id)&&agentRepository==null)return null;
        synchronized(locks.computeIfAbsent(id,k->new Object())) {
            AiClientToolMcpVO config=agentRepository==null?configRegistry.get(id):agentRepository.queryAiClientToolMcpVOByMcpId(id);
            Function<AiClientToolMcpVO,McpSyncClient> factory=clientFactories.getOrDefault(id,defaultFactory);
            if (config == null) throw new IllegalStateException("MCP 已撤销，不能恢复旧连接");
            if (pool.current(id) != null && !sameTransport(configRegistry.get(id), config)) {
                unregister(id);
                if (factory != null) clientFactories.put(id, factory);
            }
            McpConnectionPool.Lease acquired=pool.acquireCurrent(id,true);
            if(acquired==null) {
                if(factory==null)throw new IllegalStateException("MCP 已撤销，不能恢复旧连接");
                // Admission may evict an unused connection immediately. Retry this internal race,
                // before any business request is sent; never replay a tool call to recover it.
                for(int i=0;i<3&&acquired==null;i++){ensureClient(config,factory);acquired=pool.acquireCurrent(id,true);}
                if(acquired==null)throw new IllegalStateException("MCP 容量正在调整，请稍后重试");
                try{registerCallbacks(id,listCallbacksWithMetadata(id,acquired.client()));}
                catch(RuntimeException|Error failure){acquired.close();throw failure;}
            }
            SharedLease scope=new SharedLease(acquired);callScopes.get().put(id,scope);return new CallLease(id,scope);
        }
    }

    private static boolean sameTransport(AiClientToolMcpVO old, AiClientToolMcpVO current) {
        return old != null && java.util.Objects.equals(old.getMcpName(), current.getMcpName())
                && java.util.Objects.equals(old.getTransportType(), current.getTransportType())
                && java.util.Objects.equals(old.getTransportConfig(), current.getTransportConfig())
                && java.util.Objects.equals(old.getRequestTimeout(), current.getRequestTimeout());
    }
    private void retireClient(String id,McpSyncClient client) {
        McpConnectionPool.Slot slot=pool.current(id);
        if(slot==null){closeQuietly(client);return;}
        closeQuietly(pool.retire(id,client,false));clientRegistry.remove(id,client);
    }
    private void removeClientBean(String id){if(applicationContext!=null){DefaultListableBeanFactory factory=(DefaultListableBeanFactory)applicationContext.getAutowireCapableBeanFactory();String bean=AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(id);if(factory.containsBeanDefinition(bean))factory.removeBeanDefinition(bean);}}
    public Map<String,Object> lifecycle(String id){Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("activeLeases",pool.activeLeases(id));Long used=pool.lastUsed(id);result.put("lastUsedAt",used==null?lastUseBeforeEviction.getIfPresent(id):used);result.put("evictedAt",evictedAt.getIfPresent(id));return result;}

    public void assertAccessible(String mcpId, String userId) {
        if (userId == null || userId.isBlank() || mcpId == null || workspaceAccess == null
                || !workspaceAccess.ownsMcp(userId, mcpId)) {
            throw new IllegalStateException("MCP 不存在、已停用或无权访问");
        }
    }

    public void assertAccessible(String mcpId, String userId, String agentId) {
        assertAccessible(mcpId, userId);
        if (agentId == null || agentId.isBlank() || !workspaceAccess.agentMcpIds(userId, agentId).contains(mcpId)) {
            throw new IllegalStateException("当前 Agent 未配置此 MCP");
        }
    }

    /** mcpId → 客户端工厂函数（由 AiClientToolMcpNode 注入，打破循环依赖） */
    private final Map<String, Function<AiClientToolMcpVO, McpSyncClient>> clientFactories = new ConcurrentHashMap<>();

    /** mcpId → 活的 McpSyncClient */
    private final Map<String, McpSyncClient> clientRegistry = new ConcurrentHashMap<>();

    /** mcpId → MCP 配置（用于重建） */
    private final Map<String, AiClientToolMcpVO> configRegistry = new ConcurrentHashMap<>();

    /** toolName → 可原子替换的 ToolCallback 引用 */
    private final Map<ToolKey, AtomicReference<ToolCallback>> callbackRegistry = new ConcurrentHashMap<>();

    /** toolName → mcpId 反向索引 */
    private record ToolKey(String mcpId, String name) {}

    /** mcpId → 连续重建失败次数 */
    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();

    /** mcpId → 熔断快失败截止时间 */
    private final Map<String, AtomicLong> failFastUntil = new ConcurrentHashMap<>();

    /** mcpId → 重建锁（避免 String.intern() 污染 string pool） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** mcpId → 最后一次成功调用时间戳 */
    private final Map<String, AtomicLong> lastSuccessTime = new ConcurrentHashMap<>();

    /** mcpId → 最近一次重连成功时间戳（用于冷却期判断） */
    private final Map<String, AtomicLong> lastReconnectTime = new ConcurrentHashMap<>();

    /** mcpId → 最近一次 snapshot 懒探活时间戳（用户问 idle 时是否真活——节流到 5min 一次） */
    private final Map<String, AtomicLong> lastProbeTime = new ConcurrentHashMap<>();

    /** mcpId → 最近一次探活**成功**时间戳。<b>与 lastSuccessTime 严格分离</b>：lastSuccessTime 只记录业务调用成功，探活成功不污染它。 */
    private final Map<String, AtomicLong> lastProbeOkTime = new ConcurrentHashMap<>();

    /** mcpId → 最近一次探活失败时间戳（>0 表示已被探死，区分 dead vs 从未注册的 unknown） */
    private final Map<String, AtomicLong> lastProbeFailTime = new ConcurrentHashMap<>();

    /**
     * 注册 MCP 客户端及其配置（在 AiClientToolMcpNode 装配时调用）
     *
     * @param clientFactory 客户端工厂函数（如 AiClientToolMcpNode::createMcpSyncClient），用于重建时创建新客户端
     */
    public void register(String mcpId, AiClientToolMcpVO config, McpSyncClient client,
                         Function<AiClientToolMcpVO, McpSyncClient> clientFactory) {
        // Trusted internal compatibility entry point. Production creation uses ensureClient.
        if(pool.current(mcpId)==null){var slot=pool.reserve(mcpId,"SYSTEM",Math.max(1,maxConnections),Math.max(1,maxPerUser));pool.commit(slot,client);capacityOwners.put(mcpId,"SYSTEM");}
        registerState(mcpId,config,client,clientFactory);
    }
    private void registerState(String mcpId,AiClientToolMcpVO config,McpSyncClient client,Function<AiClientToolMcpVO,McpSyncClient> clientFactory) {
        clientRegistry.put(mcpId, client);
        configRegistry.put(mcpId, config);
        clientFactories.put(mcpId, clientFactory);
        AtomicInteger failures = consecutiveFailures.computeIfAbsent(mcpId, k -> new AtomicInteger(0));
        failFastUntil.putIfAbsent(mcpId, new AtomicLong(0));
        lastSuccessTime.putIfAbsent(mcpId, new AtomicLong(0));
        lastReconnectTime.putIfAbsent(mcpId, new AtomicLong(0));
        // 把 AtomicInteger 接到 Prometheus gauge：Grafana 能画"实时连续失败"曲线
        if (mcpToolMetrics != null) {
            mcpToolMetrics.registerConsecutiveFailuresGauge(mcpId, failures);
        }
        log.info("[McpRegistry] registered mcpId={} name={}", mcpId, config.getMcpName());
    }

    /**
     * 装配复用判据：该 mcpId 是否已有在册的活连接。
     * <p>true → 已被其它 agent 装配过且未被重连流程清理，装配应<b>复用</b>而非删旧建新。
     * 删旧建新（registerBean → removeBeanDefinition → close 旧 McpSyncClient）会孤儿化
     * 已装配 agent 缓存的 ToolCallback，下次调用瞬间抛 "Failed to enqueue message"。
     * <p>连接若已死，由业务路径（getFreshCallback / forceReconnect）负责重建，不在装配期处理；
     * doRecreate 失败会 {@code clientRegistry.remove(mcpId)}，此时返回 false → 装配重建。
     */
    public boolean hasClient(String mcpId) {
        return mcpId != null && clientRegistry.get(mcpId) != null;
    }

    /**
     * Return the registered MCP client without probing or rebuilding.
     */
    public McpSyncClient getClient(String mcpId) {
        return mcpId == null ? null : clientRegistry.get(mcpId);
    }

    /** Revoke cached credentials/callbacks after an owned MCP is changed or removed. */
    public void unregister(String mcpId) {
        if (mcpId == null) return;
        synchronized (locks.computeIfAbsent(mcpId, ignored -> new Object())) {
            retireClient(mcpId,clientRegistry.get(mcpId));
            configRegistry.remove(mcpId);
            clientFactories.remove(mcpId);
            callbackRegistry.keySet().removeIf(key -> mcpId.equals(key.mcpId()));
            consecutiveFailures.computeIfAbsent(mcpId, ignored -> new AtomicInteger()).set(0);
            failFastUntil.remove(mcpId);
            lastSuccessTime.remove(mcpId);
            lastReconnectTime.remove(mcpId);
            lastProbeTime.remove(mcpId);
            lastProbeOkTime.remove(mcpId);
            lastProbeFailTime.remove(mcpId);
            capacityOwners.remove(mcpId);evictedAt.invalidate(mcpId);lastUseBeforeEviction.invalidate(mcpId);
            if (applicationContext != null) {
                DefaultListableBeanFactory factory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
                String bean = AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId);
                if (factory.containsBeanDefinition(bean)) factory.removeBeanDefinition(bean);
            }
        }
    }

    /**
     * List tool callbacks during model assembly.
     *
     * <p>This is intentionally different from {@link #getFreshCallback(String)}: at assembly time
     * there may be no toolName -> mcpId index yet, because the index is built from listTools().
     * If a reused SSE session has expired, listTools() is exactly the first operation that fails.
     * Rebuild once by mcpId, then retry through the new client.
     */
    public ToolCallback[] getToolCallbacksForAssembly(String mcpId, McpSyncClient fallbackClient) throws Exception {
        if (mcpId == null) {
            return new ToolCallback[0];
        }

        AtomicLong failFast = failFastUntil.get(mcpId);
        if (failFast != null && failFast.get() > System.currentTimeMillis()) {
            throw new IllegalStateException("MCP circuit is open for mcpId=" + mcpId);
        }

        synchronized (locks.computeIfAbsent(mcpId, k -> new Object())) {
            McpSyncClient client = clientRegistry.get(mcpId);
            if (client == null) {
                client = fallbackClient;
            }
            if (client == null) {
                return new ToolCallback[0];
            }

            try {
                ToolCallback[] callbacks = listCallbacksWithMetadata(mcpId, client);
                lastProbeOkTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(System.currentTimeMillis());
                AtomicLong fail = lastProbeFailTime.get(mcpId);
                if (fail != null) fail.set(0);
                return callbacks;
            } catch (Exception first) {
                lastProbeFailTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(System.currentTimeMillis());
                log.warn("[McpRegistry] assembly listTools failed for mcpId={}, will recreate once: {}",
                        mcpId, first.toString());
                ToolCallback[] recreated = doRecreateCallbacks(mcpId, client, "assembly-list-tools");
                if (recreated != null) {
                    return recreated;
                }
                throw first;
            }
        }
    }

    /** Capture annotations during the existing tools/list, without an extra remote request. */
    private ToolCallback[] listCallbacksWithMetadata(String mcpId, McpSyncClient client) {
        Map<String, McpSchema.ToolAnnotations> annotations = new java.util.HashMap<>();
        ToolCallback[] callbacks;
        // Only the legacy, never-managed discovery path can omit a lease. A known generation
        // may have been retired before clientRegistry.remove; never issue tools/list on it.
        try(McpConnectionPool.Lease ignored=!configRegistry.containsKey(mcpId)&&pool.current(mcpId)==null
                ?null:pool.acquire(mcpId,client,false)) {
        callbacks = SyncMcpToolCallbackProvider.builder().mcpClients(client)
                .toolFilter((connection, tool) -> {
                    annotations.put(tool.name(), tool.annotations());
                    return true;
                }).build().getToolCallbacks();
        }
        return java.util.Arrays.stream(callbacks).map(callback -> {
            String originalName = callback instanceof SyncMcpToolCallback mcpCallback
                    ? mcpCallback.getOriginalToolName() : callback.getToolDefinition().name();
            McpToolCallMetadata metadata = McpToolCallMetadata.from(mcpId, annotations.get(originalName),
                    callback.getToolMetadata().returnDirect());
            return (ToolCallback) new LeasedCallback(mcpId,client,callback,metadata);
        }).toArray(ToolCallback[]::new);
    }
    public interface OriginalToolName {
        String getOriginalToolName();
    }

    private final class LeasedCallback implements ToolCallback, OriginalToolName {
        final String id;final McpSyncClient client;final ToolCallback callback;final McpToolCallMetadata metadata;
        LeasedCallback(String id,McpSyncClient client,ToolCallback callback,McpToolCallMetadata metadata){this.id=id;this.client=client;this.callback=callback;this.metadata=metadata;}
                @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return callback.getToolDefinition();
                }
                @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() { return metadata; }
                @Override public String getOriginalToolName() { return originalToolName(callback); }
                @Override public String call(String input) { return invoke(input,null); }
                @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                    return invoke(input,context);
                }
        private String invoke(String input,org.springframework.ai.chat.model.ToolContext context) {
            if(workspaceAccess!=null){
                Map<String,Object> values=context==null?Map.of():context.getContext();
                Object user=values.get("userId"),agent=values.get("agentId");
                assertAccessible(id,user==null?org.slf4j.MDC.get("userId"):String.valueOf(user),
                        agent==null?org.slf4j.MDC.get("agentId"):String.valueOf(agent));
            }
            try(CallLease lease=borrowCall(id)) {
                if(lease==null)throw new IllegalStateException("MCP connection is no longer authorized");
                ToolCallback target=callback;
                if(lease.client()!=client){
                    ToolCallback current=getCurrentCallback(id,getToolDefinition().name());
                    if(!(current instanceof LeasedCallback active)||active.client!=lease.client()
                            ||!java.util.Objects.equals(getOriginalToolName(),active.getOriginalToolName())
                            ||!java.util.Objects.equals(getToolDefinition().inputSchema(),active.getToolDefinition().inputSchema()))
                        throw new IllegalStateException("MCP tool changed; reload its capabilities");
                    target=active.callback;
                }
                return context==null?target.call(input):target.call(input,context);
            }
        }
    }

    /**
     * 注册工具回调并建立反向索引（在 AiClientModelNode 装配时调用）
     */
    public void registerCallbacks(String mcpId, ToolCallback[] callbacks) {
        if (mcpId == null || callbacks == null) return;
        synchronized (locks.computeIfAbsent(mcpId, ignored -> new Object())) {
            Map<String, ToolCallback> current = new java.util.LinkedHashMap<>();
            Map<String, ToolCallback> names = new java.util.HashMap<>();
            for (ToolCallback cb : callbacks) {
                String name = cb.getToolDefinition().name();
                if (current.putIfAbsent(name, cb) != null) {
                    throw new IllegalStateException("Duplicate MCP tool name for mcpId=" + mcpId + ": " + name);
                }
                for (String alias : new String[]{name, originalToolName(cb)}) {
                    ToolCallback previous = names.putIfAbsent(alias, cb);
                    if (previous != null && previous != cb) {
                        throw new IllegalStateException("Ambiguous MCP tool name for mcpId=" + mcpId + ": " + alias);
                    }
                }
            }
            current.forEach((name, cb) -> callbackRegistry
                    .computeIfAbsent(new ToolKey(mcpId, name), ignored -> new AtomicReference<>()).set(cb));
            callbackRegistry.keySet().removeIf(key -> mcpId.equals(key.mcpId()) && !current.containsKey(key.name()));
        }
        log.info("[McpRegistry] registered {} callbacks for mcpId={}", callbacks.length, mcpId);
    }

    /** Stable server-side name; SDK model-facing names can be formatted or prefixed. */
    public static String originalToolName(ToolCallback callback) {
        if (callback instanceof OriginalToolName named) return named.getOriginalToolName();
        if (callback instanceof SyncMcpToolCallback mcpCallback) return mcpCallback.getOriginalToolName();
        return callback == null || callback.getToolDefinition() == null ? null : callback.getToolDefinition().name();
    }

    /**
     * 获取工具名对应的 mcpId（供 MeteredToolCallback 构造时使用）
     */
    public String getMcpIdForTool(String toolName) {
        String match = null;
        for (var entry : callbackRegistry.entrySet()) {
            ToolKey key = entry.getKey();
            if (!java.util.Objects.equals(toolName, key.name())
                    && !java.util.Objects.equals(toolName, originalToolName(entry.getValue().get()))) continue;
            if (match != null && !match.equals(key.mcpId())) return null;
            match = key.mcpId();
        }
        return match;
    }

    /**
     * Return the currently registered callback without probing or rebuilding.
     * MeteredToolCallback calls this while holding the per-MCP send lock, so a
     * sibling tool can move away from a stale SDK callback after the MCP client
     * has already been rebuilt by another tool.
     */
    public ToolCallback getCurrentCallback(String toolName) {
        return getCurrentCallback(getMcpIdForTool(toolName), toolName);
    }

    public ToolCallback getCurrentCallback(String mcpId, String toolName) {
        if (mcpId == null || toolName == null) return null;
        AtomicReference<ToolCallback> ref = callbackRegistry.get(new ToolKey(mcpId, toolName));
        if (ref != null) return availableCallback(ref.get());
        // Resolve only names actually reported by this connection. Character replacement or
        // global alias lookup can route an old catalog hit to a different tool/tenant.
        ToolCallback match = null;
        for (var entry : callbackRegistry.entrySet()) {
            if (!mcpId.equals(entry.getKey().mcpId())) continue;
            ToolCallback callback = availableCallback(entry.getValue().get());
            if (callback == null) continue;
            if (!toolName.equals(originalToolName(callback))) continue;
            if (match != null && match != callback) return null;
            match = callback;
        }
        return match;
    }

    /** Keep name metadata for recovery, but never advertise a retired transport as callable. */
    private ToolCallback availableCallback(ToolCallback callback) {
        if (callback instanceof LeasedCallback leased && !pool.isCurrent(leased.id, leased.client)) return null;
        return callback;
    }
    public ToolCallback[] currentCallbacks(String mcpId) {
        return callbackRegistry.entrySet().stream().filter(e->java.util.Objects.equals(mcpId,e.getKey().mcpId()))
                .map(e->availableCallback(e.getValue().get())).filter(java.util.Objects::nonNull).toArray(ToolCallback[]::new);
    }

    /**
     * 记录某 mcpId 的一次成功调用（供 MeteredToolCallback 成功后调用）
     */
    public void recordSuccess(String mcpId) {
        if (mcpId != null) {
            lastSuccessTime.computeIfAbsent(mcpId, k -> new AtomicLong(0))
                    .set(System.currentTimeMillis());
        }
    }

    /**
     * 某 mcpId 是否处于冷连接状态（超过 IDLE_THRESHOLD_MS 没有成功调用）
     */
    private boolean isIdle(String mcpId) {
        AtomicLong ts = lastSuccessTime.get(mcpId);
        if (ts == null) return true; // 从未调用过，视为冷连接
        return System.currentTimeMillis() - ts.get() > IDLE_THRESHOLD_MS;
    }

    /**
     * 快速探活：在独立线程中调用 listTools，1 秒内返回即为活。
     * @return true 连接活着，false 连接死了
     */
    private boolean probe(McpSyncClient client) {
        if (client == null) return false;
        try {
            // listTools 正常 200-500ms 返回，给 1 秒上限
            java.util.concurrent.CompletableFuture<Boolean> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        String id=clientRegistry.entrySet().stream().filter(e->e.getValue()==client).map(Map.Entry::getKey).findFirst().orElse(null);
                        if(id==null)return false;
                        try (McpConnectionPool.Lease ignored=pool.acquire(id,client,false)) {
                            client.listTools();
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    },probeExecutor);
            return future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[McpRegistry] probe timeout or failed: {}", e.toString());
            return false;
        }
    }
    private final java.util.concurrent.ExecutorService probeExecutor=new java.util.concurrent.ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),r->{Thread thread=new Thread(r,"mcp-health-probe");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    @jakarta.annotation.PreDestroy public void shutdown(){probeExecutor.shutdownNow();for(String id:new ArrayList<>(clientRegistry.keySet()))unregister(id);}

    /**
     * 获取新鲜的 ToolCallback。
     * 冷连接（>5min 未使用）→ 先 1s 探活，失败则重建；
     * 客户端健康 → 直接返回；
     * 客户端死亡 → 重建后返回；
     * 熔断中 → 返回 null。
     */
    public ToolCallback getFreshCallback(String toolName) {
        return getFreshCallback(getMcpIdForTool(toolName), toolName);
    }

    public ToolCallback getFreshCallback(String mcpId, String toolName) {
        if (mcpId == null) {
            return null;
        }

        // 熔断快失败
        AtomicLong cutoff = failFastUntil.get(mcpId);
        if (cutoff == null) return null;
        long failFast = cutoff.get();
        if (failFast > 0 && System.currentTimeMillis() < failFast) {
            log.warn("[McpRegistry] circuit OPEN for mcpId={}, fail-fast", mcpId);
            return null;
        }

        // 同步重建（同 mcpId 只有一个线程重建，其他等待后直接拿新引用）
        synchronized (locks.computeIfAbsent(mcpId, k -> new Object())) {
            McpSyncClient client = clientRegistry.get(mcpId);

            // 冷连接探活：超过 5 分钟没成功调用过，先用 1s 短超时确认连接状态
            if (isIdle(mcpId) && !probe(client)) {
                log.info("[McpRegistry] cold connection probe failed for mcpId={}, will recreate", mcpId);
                return doRecreate(mcpId, toolName, client, "cold");
            }

            // 快速路径：客户端健康，直接返回
            if (isHealthy(client)) {
                return getCurrentCallback(mcpId, toolName);
            }

            // 客户端死亡，尝试重建
            return doRecreate(mcpId, toolName, client, "dead");
        }
    }

    /**
     * 强制重建连接：无论当前连接是否健康，都关闭旧连接并创建新连接。
     * 用于 MCP SDK 0.10.0 的 sendMessage 400 bug：SSE 流活着但 message endpoint 已失效，
     * 普通的 isHealthy 检测（listTools）无法发现此问题。
     *
     * @return 新的 ToolCallback，或 null（重建失败 / 熔断中）
     */
    public ToolCallback forceReconnect(String toolName) {
        return forceReconnect(getMcpIdForTool(toolName), toolName);
    }

    public ToolCallback forceReconnect(String mcpId, String toolName) {
        return reconnect(mcpId, toolName, "force");
    }

    /**
     * 超时探活失败后的重建路径。
     *
     * <p>与 {@link #forceReconnect(String)} 行为一致，但保留 trigger=timeout 的观测语义，
     * 方便区分主动强制重连和工具调用超时后的自愈。</p>
     */
    public ToolCallback reconnectAfterTimeout(String toolName) {
        return reconnectAfterTimeout(getMcpIdForTool(toolName), toolName);
    }

    public ToolCallback reconnectAfterTimeout(String mcpId, String toolName) {
        return reconnect(mcpId, toolName, "timeout");
    }

    private ToolCallback reconnect(String mcpId, String toolName, String trigger) {
        if (mcpId == null) return null;

        // 熔断快失败
        AtomicLong cutoff = failFastUntil.get(mcpId);
        if (cutoff == null) return null;
        long failFast = cutoff.get();
        if (failFast > 0 && System.currentTimeMillis() < failFast) {
            log.warn("[McpRegistry] circuit OPEN for mcpId={}, skip {} reconnect", mcpId, trigger);
            return null;
        }

        synchronized (locks.computeIfAbsent(mcpId, k -> new Object())) {
            // 冷却期检查：刚重连过就不再重复重连
            AtomicLong lastTs = lastReconnectTime.get(mcpId);
            if (lastTs != null) {
                long elapsed = System.currentTimeMillis() - lastTs.get();
                if (elapsed < RECONNECT_COOLDOWN_MS) {
                    log.info("[McpRegistry] reconnect cooldown active for mcpId={} ({}ms ago), skip", mcpId, elapsed);
                    if (mcpToolMetrics != null) {
                        mcpToolMetrics.recordReconnectCooldownHit(mcpId);
                    }
                    return getCurrentCallback(mcpId, toolName);
                }
            }

            McpSyncClient client = clientRegistry.get(mcpId);
            log.info("[McpRegistry] {} reconnect mcpId={}", trigger, mcpId);
            return doRecreate(mcpId, toolName, client, trigger);
        }
    }

    /**
     * 供 MeteredToolCallback 调用：工具调用超时后，用 1s 探活区分"服务端慢"还是"连接死了"。
     * @return true 连接活着（服务端慢），false 连接死了（仅判定，不在这里重建）
     */
    public boolean probeAfterTimeout(String toolName) {
        return probeAfterTimeout(getMcpIdForTool(toolName), toolName);
    }

    public boolean probeAfterTimeout(String mcpId, String toolName) {
        if (mcpId == null) return false;

        McpSyncClient client = clientRegistry.get(mcpId);
        return probe(client);
    }

    private ToolCallback doRecreate(String mcpId, String toolName, McpSyncClient oldClient, String trigger) {
        ToolCallback[] newCallbacks = doRecreateCallbacks(mcpId, oldClient, trigger);
        if (newCallbacks == null) {
            return null;
        }
        return getCurrentCallback(mcpId, toolName);
    }

    private ToolCallback[] doRecreateCallbacks(String mcpId, McpSyncClient oldClient, String trigger) {
        try {
            log.info("[McpRegistry] recreating client for mcpId={}", mcpId);
            AiClientToolMcpVO config = configRegistry.get(mcpId);
            if (config == null) {
                log.error("[McpRegistry] no config for mcpId={}", mcpId);
                return null;
            }

            retireClient(mcpId,oldClient);

            Function<AiClientToolMcpVO, McpSyncClient> factory = clientFactories.get(mcpId);
            if (factory == null) {
                log.error("[McpRegistry] no client factory for mcpId={}", mcpId);
                return null;
            }
            McpSyncClient newClient = ensureClient(config,factory);

            ToolCallback[] newCallbacks = listCallbacksWithMetadata(mcpId, newClient);
            registerCallbacks(mcpId, newCallbacks);

            registerBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId), McpSyncClient.class, newClient);

            consecutiveFailures.get(mcpId).set(0);
            failFastUntil.get(mcpId).set(0);
            long now = System.currentTimeMillis();
            lastReconnectTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);
            lastProbeOkTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);
            AtomicLong probeFail = lastProbeFailTime.get(mcpId);
            if (probeFail != null) probeFail.set(0);

            log.info("[McpRegistry] recreated mcpId={} trigger={} successfully, {} callbacks updated",
                    mcpId, trigger, newCallbacks.length);
            if (mcpToolMetrics != null) {
                mcpToolMetrics.recordClientReconnect(mcpId, trigger);
            }

            return newCallbacks;

        } catch (Exception e) {
            int failures = consecutiveFailures.computeIfAbsent(mcpId, k -> new AtomicInteger(0)).incrementAndGet();
            log.error("[McpRegistry] recreate failed for mcpId={} trigger={} failures={}", mcpId, trigger, failures, e);
            if (mcpToolMetrics != null) {
                mcpToolMetrics.recordClientReconnectFailure(mcpId, trigger, e);
            }
            // 清理 stale clientRegistry entry：oldClient 已 closeQuietly，新 client 创建失败，
            // 留着这个 stale 引用会让 snapshotAll 误报 alive。移除后状态变 unknown，跟真实情况对齐。
            retireClient(mcpId,clientRegistry.get(mcpId));

            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                long until = System.currentTimeMillis() + CIRCUIT_BREAKER_COOLDOWN_MS;
                failFastUntil.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(until);
                log.error("[McpRegistry] circuit OPEN for mcpId={} until={}ms", mcpId, until);
                if (mcpToolMetrics != null) {
                    mcpToolMetrics.recordCircuitOpen(mcpId);
                }
            }
            return null;
        }
    }

    /**
     * 轻量健康检查：尝试 listTools，失败则认为死亡
     */
    private boolean isHealthy(McpSyncClient client) {
        if (client == null) {
            return false;
        }
        String id=clientRegistry.entrySet().stream().filter(e->e.getValue()==client).map(Map.Entry::getKey).findFirst().orElse(null);
        if(id==null)return false;
        try (McpConnectionPool.Lease ignored=pool.acquire(id,client,false)) {
            client.listTools();
            return true;
        } catch (Exception e) {
            log.debug("[McpRegistry] health check failed: {}", e.toString());
            return false;
        }
    }

    private void closeQuietly(McpSyncClient client) {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.debug("[McpRegistry] close old client failed (expected if dead): {}", e.toString());
        }
    }

    /** alive 状态要求最近这么长时间内有成功调用（业务调用 or 探活），否则进入下一轮探活，探不通即 dead。 */
    private static final long ALIVE_SUCCESS_WINDOW_MS = 5 * 60 * 1000L;

    /** 懒探活节流：同 mcpId 最多每 5min 探一次，避免高频 polling 撑爆 mcp server。 */
    private static final long PROBE_THROTTLE_MS = 5 * 60 * 1000L;

    /**
     * 对外快照：把内部 map 翻译成观测视图，给 ObserveController 拼 `/mcp-client-health` 用。
     * <p>
     * <b>状态判定 + 懒探活</b>：
     * <pre>
     *   circuit_open      → failFastUntil > now
     *   reconnect_cooldown→ 距离上次成功重连不到 10s
     *   alive             → clientRegistry 有引用 且 5min 内有过成功调用（业务调用 or 懒探活成功都算）
     *   dead              → 最近一次懒探活失败晚于业务成功/探活成功/重连成功
     *   unknown           → clientRegistry 已无引用 且 历史上从未探活失败（doRecreate 失败被清理 / 从未注册）
     * </pre>
     * <b>懒探活策略（用户要求）</b>：当某 mcp 超过 5min 没成功调用，本次 snapshot 会**并发**触发一次
     * {@code listTools()} 探活（500ms 超时）；成功 → 刷新 lastProbeOkTime（下一次状态变 alive）；
     * 失败 → 只记录 lastProbeFailTime（状态变 dead），不修改 clientRegistry。真正重建只由业务调用路径触发。
     * 探活有 {@link #PROBE_THROTTLE_MS} 节流：同一个 mcp 5min 内只探一次，HTTP polling 不会形成探活风暴。
     */
    public List<ClientHealthSnapshot> snapshotAll() {
        return snapshotSelected(null);
    }

    /** Select before probes, so a personal dashboard never contacts another owner's MCP. */
    public List<ClientHealthSnapshot> snapshotSelected(Set<String> selectedIds) {
        long now = System.currentTimeMillis();
        // 把已注册 mcpId 与有 callback 的 mcpId 合并，避免漏算
        Set<String> ids = new LinkedHashSet<>(configRegistry.keySet());
        ids.addAll(clientRegistry.keySet());
        callbackRegistry.keySet().forEach(key -> ids.add(key.mcpId()));
        if (selectedIds != null) ids.retainAll(selectedIds);

        // 第一步：找出本轮需要懒探活的 mcpId —— 当前有 client 引用但业务调用 & 探活都 5min+ 没成功，且节流过期
        List<String> probeTargets = new ArrayList<>();
        for (String mcpId : ids) {
            McpSyncClient client = clientRegistry.get(mcpId);
            if (client == null) continue; // 无 client 不探，由重连流程负责
            if (pool.activeLeases(mcpId)>0) continue;
            AtomicLong lastSuccess = lastSuccessTime.get(mcpId);
            long lastSuccessTs = lastSuccess == null ? 0 : lastSuccess.get();
            if (lastSuccessTs > 0 && now - lastSuccessTs < ALIVE_SUCCESS_WINDOW_MS) continue; // 业务调用 5min 内有成功 → 已 alive
            AtomicLong lastProbeOk = lastProbeOkTime.get(mcpId);
            long lastProbeOkTs = lastProbeOk == null ? 0 : lastProbeOk.get();
            if (lastProbeOkTs > 0 && now - lastProbeOkTs < ALIVE_SUCCESS_WINDOW_MS) continue; // 探活 5min 内成功过 → 也 alive
            AtomicLong probeTs = lastProbeTime.get(mcpId);
            long lastProbeAt = probeTs == null ? 0 : probeTs.get();
            if (lastProbeAt > 0 && now - lastProbeAt < PROBE_THROTTLE_MS) continue; // 节流命中
            // 熔断 / 重连冷却期不探，让熔断逻辑负责
            AtomicLong failFast = failFastUntil.get(mcpId);
            if (failFast != null && failFast.get() > now) continue;
            AtomicLong lastReconnect = lastReconnectTime.get(mcpId);
            if (lastReconnect != null && lastReconnect.get() > 0
                    && now - lastReconnect.get() < RECONNECT_COOLDOWN_MS) continue;
            probeTargets.add(mcpId);
        }

        // 第二步：并发探活；总耗时 ≈ 单个 PROBE_TIMEOUT_MS（默认 500ms），不阻塞 HTTP 请求
        if (!probeTargets.isEmpty()) {
            log.debug("[McpRegistry] snapshot lazy-probe targets: {}", probeTargets);
            java.util.concurrent.CompletableFuture<?>[] futures = probeTargets.stream()
                    .map(mcpId -> java.util.concurrent.CompletableFuture.runAsync(() -> probeAndUpdate(mcpId)))
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            try {
                java.util.concurrent.CompletableFuture.allOf(futures)
                        .get(PROBE_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.debug("[McpRegistry] snapshot probe wait timeout/error: {}", e.toString());
            }
        }

        // 第三步：基于探活后状态生成快照
        List<ClientHealthSnapshot> out = new ArrayList<>(ids.size());
        for (String mcpId : ids) {
            AtomicInteger failures = consecutiveFailures.get(mcpId);
            AtomicLong failFast = failFastUntil.get(mcpId);
            AtomicLong lastReconnect = lastReconnectTime.get(mcpId);
            AtomicLong lastSuccess = lastSuccessTime.get(mcpId);
            AtomicLong lastProbeOk = lastProbeOkTime.get(mcpId);
            AtomicLong lastProbeFail = lastProbeFailTime.get(mcpId);
            AtomicLong lastProbe = lastProbeTime.get(mcpId);
            McpSyncClient client = clientRegistry.get(mcpId);

            int consecutive = failures == null ? 0 : failures.get();
            long failFastTs = failFast == null ? 0 : failFast.get();
            long lastReconnectTs = lastReconnect == null ? 0 : lastReconnect.get();
            long lastSuccessTs = lastSuccess == null ? 0 : lastSuccess.get();
            long lastProbeOkTs = lastProbeOk == null ? 0 : lastProbeOk.get();
            long lastProbeFailTs = lastProbeFail == null ? 0 : lastProbeFail.get();
            long lastProbeTs = lastProbe == null ? 0 : lastProbe.get();
            long circuitRemain = failFastTs > now ? (failFastTs - now) / 1000L : 0L;
            long reconnectRemain = (lastReconnectTs > 0 && now - lastReconnectTs < RECONNECT_COOLDOWN_MS)
                    ? (RECONNECT_COOLDOWN_MS - (now - lastReconnectTs)) / 1000L : 0L;
            // alive 接受两种证据：业务调用成功 OR 探活成功（都要求 5min 内）。两者严格分离不互相污染。
            boolean recentBusiness = lastSuccessTs > 0 && (now - lastSuccessTs) < ALIVE_SUCCESS_WINDOW_MS;
            boolean recentProbeOk = lastProbeOkTs > 0 && (now - lastProbeOkTs) < ALIVE_SUCCESS_WINDOW_MS;
            boolean probeFailedAfterLatestRecovery = lastProbeFailTs > 0
                    && lastProbeFailTs > lastSuccessTs
                    && lastProbeFailTs > lastProbeOkTs
                    && lastProbeFailTs > lastReconnectTs;

            String status;
            if (client == null && evictedAt.getIfPresent(mcpId)!=null) {
                status="idle_evicted";
            } else if (failFastTs > now) {
                status = "circuit_open";
            } else if (reconnectRemain > 0) {
                status = "reconnect_cooldown";
            } else if (probeFailedAfterLatestRecovery) {
                status = "dead";
            } else if (client == null) {
                // 区分 dead（曾被探活探死）vs unknown（从未注册或从未探过）
                status = lastProbeFailTs > 0 ? "dead" : "unknown";
            } else if (recentBusiness || recentProbeOk) {
                status = "alive";
            } else if (pool.activeLeases(mcpId)>0) {
                status = "in_use";
            } else {
                // 5min+ 没有业务成功或探活成功时，本轮 snapshot 会按节流触发探活；
                // 如果探活未能提供成功证据，则观测面直接标 dead，避免长期停留在语义模糊的 idle。
                status = "dead";
            }

            // 反向找 tools：toolToMcpId 是 toolName→mcpId，反查
            List<String> tools = new ArrayList<>();
            for (ToolKey key : callbackRegistry.keySet()) {
                if (mcpId.equals(key.mcpId())) tools.add(key.name());
            }
            Collections.sort(tools);

            out.add(new ClientHealthSnapshot(
                    mcpId,
                    status,
                    consecutive,
                    failFastTs > 0 ? failFastTs : null,
                    circuitRemain,
                    lastReconnectTs > 0 ? lastReconnectTs : null,
                    reconnectRemain,
                    lastSuccessTs > 0 ? lastSuccessTs : null,
                    lastProbeTs > 0 ? lastProbeTs : null,
                    lastProbeOkTs > 0 ? lastProbeOkTs : null,
                    tools
            ));
        }
        return out;
    }

    /**
     * 懒探活实际执行：发 listTools()，成功记 lastProbeOkTime（<b>不动 lastSuccessTime</b>——那是业务调用专属硬证据），
     * 失败只标记 lastProbeFailTime。注意：观测接口不能删除/替换 clientRegistry 中的业务连接引用，
     * 真正的重建只由 getFreshCallback / forceReconnect / probeAfterTimeout 这些业务路径触发。
     * 总是更新 lastProbeTime 让节流生效。
     */
    private void probeAndUpdate(String mcpId) {
        long now = System.currentTimeMillis();
        lastProbeTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);
        McpSyncClient client = clientRegistry.get(mcpId);
        if (client == null) {
            // 被并发流程移走了，跳过本次探活
            return;
        }
        boolean alive = probe(client);
        if (alive) {
            // 严格隔离：探活成功只更新 lastProbeOkTime，绝不染指 lastSuccessTime
            // （lastSuccessTime 只能由 MeteredToolCallback.recordSuccess 在真实业务调用成功时打）
            lastProbeOkTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);
            // 探活成功清掉旧的失败标记，避免历史标记永远残留
            AtomicLong fail = lastProbeFailTime.get(mcpId);
            if (fail != null) fail.set(0);
            log.info("[McpRegistry] lazy-probe OK mcpId={}, refreshed lastProbeOkTime (lastSuccessTime untouched)", mcpId);
        } else {
            lastProbeFailTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);
            log.warn("[McpRegistry] lazy-probe FAIL mcpId={}, status=dead (clientRegistry untouched)", mcpId);
            if (mcpToolMetrics != null) {
                // 复用现有 reconnect.failure 指标，trigger="probe"，让 Grafana 能看到主动探死的流量
                mcpToolMetrics.recordClientReconnectFailure(mcpId, "probe",
                        new RuntimeException("lazy-probe listTools failed"));
            }
        }
    }

    /**
     * 客户端健康快照 record。
     * <p>
     * <b>字段语义</b>：
     * <ul>
     *   <li>{@code status}: alive / dead / reconnect_cooldown / circuit_open / unknown（见 snapshotAll javadoc；idle 已不作为稳定状态输出）</li>
     *   <li>{@code consecutiveFailures}: 连续重建失败次数（≥3 触发熔断）</li>
     *   <li>{@code circuitOpenUntil}: 熔断截止 epoch ms；null 表示未熔断</li>
     *   <li>{@code lastReconnectAt}: 最近一次重连成功 epoch ms；null 表示从未重连</li>
     *   <li>{@code lastSuccessAt}: 最近一次<b>业务工具调用</b>成功 epoch ms（探活成功<b>不</b>污染这个字段）；null 表示从未有真实业务调用成功过</li>
     *   <li>{@code lastProbeAt}: 最近一次懒探活时间 epoch ms；null 表示从未探过</li>
     *   <li>{@code lastProbeOkAt}: 最近一次懒探活<b>成功</b>时间 epoch ms；null 表示从未探活成功</li>
     *   <li>{@code registeredTools}: 这个 mcp 下的工具名列表</li>
     * </ul>
     * <b>用户区分"真有人在用" vs "只是探活活着"</b>：lastSuccessAt 是业务硬证据（用户/Agent 实际调过工具）；
     * lastProbeOkAt 是连接硬证据（listTools 成功）。两者都能让 status=alive，但语义不同。
     */
    public record ClientHealthSnapshot(
            String mcpId,
            String status,
            int consecutiveFailures,
            Long circuitOpenUntil,
            long circuitCooldownRemainingSec,
            Long lastReconnectAt,
            long reconnectCooldownRemainingSec,
            Long lastSuccessAt,
            Long lastProbeAt,
            Long lastProbeOkAt,
            List<String> registeredTools
    ) {}

    private <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        if(applicationContext==null)return;
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition definition = builder.getRawBeanDefinition();
        ((org.springframework.beans.factory.support.AbstractBeanDefinition)definition).setDestroyMethodName("");
        definition.setScope(BeanDefinition.SCOPE_SINGLETON);
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }
        beanFactory.registerBeanDefinition(beanName, definition);
        log.info("[McpRegistry] re-registered bean: {}", beanName);
    }
}
