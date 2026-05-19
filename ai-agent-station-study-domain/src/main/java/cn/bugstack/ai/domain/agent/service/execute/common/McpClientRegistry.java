package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

    /** mcpId → 客户端工厂函数（由 AiClientToolMcpNode 注入，打破循环依赖） */
    private final Map<String, Function<AiClientToolMcpVO, McpSyncClient>> clientFactories = new ConcurrentHashMap<>();

    /** mcpId → 活的 McpSyncClient */
    private final Map<String, McpSyncClient> clientRegistry = new ConcurrentHashMap<>();

    /** mcpId → MCP 配置（用于重建） */
    private final Map<String, AiClientToolMcpVO> configRegistry = new ConcurrentHashMap<>();

    /** toolName → 可原子替换的 ToolCallback 引用 */
    private final Map<String, AtomicReference<ToolCallback>> callbackRegistry = new ConcurrentHashMap<>();

    /** toolName → mcpId 反向索引 */
    private final Map<String, String> toolToMcpId = new ConcurrentHashMap<>();

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

    /**
     * 注册 MCP 客户端及其配置（在 AiClientToolMcpNode 装配时调用）
     *
     * @param clientFactory 客户端工厂函数（如 AiClientToolMcpNode::createMcpSyncClient），用于重建时创建新客户端
     */
    public void register(String mcpId, AiClientToolMcpVO config, McpSyncClient client,
                         Function<AiClientToolMcpVO, McpSyncClient> clientFactory) {
        clientRegistry.put(mcpId, client);
        configRegistry.put(mcpId, config);
        clientFactories.put(mcpId, clientFactory);
        consecutiveFailures.putIfAbsent(mcpId, new AtomicInteger(0));
        failFastUntil.putIfAbsent(mcpId, new AtomicLong(0));
        lastSuccessTime.putIfAbsent(mcpId, new AtomicLong(System.currentTimeMillis()));
        lastReconnectTime.putIfAbsent(mcpId, new AtomicLong(0));
        log.info("[McpRegistry] registered mcpId={} name={}", mcpId, config.getMcpName());
    }

    /**
     * 注册工具回调并建立反向索引（在 AiClientModelNode 装配时调用）
     */
    public void registerCallbacks(String mcpId, ToolCallback[] callbacks) {
        for (ToolCallback cb : callbacks) {
            String toolName = cb.getToolDefinition().name();
            toolToMcpId.put(toolName, mcpId);
            callbackRegistry.put(toolName, new AtomicReference<>(cb));
        }
        log.info("[McpRegistry] registered {} callbacks for mcpId={}", callbacks.length, mcpId);
    }

    /**
     * 获取工具名对应的 mcpId（供 MeteredToolCallback 构造时使用）
     */
    public String getMcpIdForTool(String toolName) {
        return toolToMcpId.get(toolName);
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
                        try {
                            client.listTools();
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    });
            return future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[McpRegistry] probe timeout or failed: {}", e.toString());
            return false;
        }
    }

    /**
     * 获取新鲜的 ToolCallback。
     * 冷连接（>5min 未使用）→ 先 1s 探活，失败则重建；
     * 客户端健康 → 直接返回；
     * 客户端死亡 → 重建后返回；
     * 熔断中 → 返回 null。
     */
    public ToolCallback getFreshCallback(String toolName) {
        String mcpId = toolToMcpId.get(toolName);
        if (mcpId == null) {
            return null;
        }

        // 熔断快失败
        long failFast = failFastUntil.get(mcpId).get();
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
                return doRecreate(mcpId, toolName, client);
            }

            // 快速路径：客户端健康，直接返回
            if (isHealthy(client)) {
                AtomicReference<ToolCallback> ref = callbackRegistry.get(toolName);
                return ref != null ? ref.get() : null;
            }

            // 客户端死亡，尝试重建
            return doRecreate(mcpId, toolName, client);
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
        String mcpId = toolToMcpId.get(toolName);
        if (mcpId == null) return null;

        // 熔断快失败
        long failFast = failFastUntil.get(mcpId).get();
        if (failFast > 0 && System.currentTimeMillis() < failFast) {
            log.warn("[McpRegistry] circuit OPEN for mcpId={}, skip force reconnect", mcpId);
            return null;
        }

        synchronized (locks.computeIfAbsent(mcpId, k -> new Object())) {
            // 冷却期检查：刚重连过就不再重复重连
            AtomicLong lastTs = lastReconnectTime.get(mcpId);
            if (lastTs != null) {
                long elapsed = System.currentTimeMillis() - lastTs.get();
                if (elapsed < RECONNECT_COOLDOWN_MS) {
                    log.info("[McpRegistry] reconnect cooldown active for mcpId={} ({}ms ago), skip", mcpId, elapsed);
                    AtomicReference<ToolCallback> ref = callbackRegistry.get(toolName);
                    return ref != null ? ref.get() : null;
                }
            }

            McpSyncClient client = clientRegistry.get(mcpId);
            log.info("[McpRegistry] force reconnect mcpId={}", mcpId);
            return doRecreate(mcpId, toolName, client);
        }
    }

    /**
     * 供 MeteredToolCallback 调用：工具调用超时后，用 1s 探活区分"服务端慢"还是"连接死了"。
     * @return true 连接活着（服务端慢），false 连接死了（已重建或重建失败）
     */
    public boolean probeAfterTimeout(String toolName) {
        String mcpId = toolToMcpId.get(toolName);
        if (mcpId == null) return false;

        McpSyncClient client = clientRegistry.get(mcpId);
        if (probe(client)) {
            // 连接活着 → 服务端慢
            return true;
        }
        // 连接死了 → 重建
        synchronized (locks.computeIfAbsent(mcpId, k -> new Object())) {
            doRecreate(mcpId, toolName, client);
        }
        return false;
    }

    private ToolCallback doRecreate(String mcpId, String toolName, McpSyncClient oldClient) {
        try {
            log.info("[McpRegistry] recreating client for mcpId={}", mcpId);
            AiClientToolMcpVO config = configRegistry.get(mcpId);
            if (config == null) {
                log.error("[McpRegistry] no config for mcpId={}", mcpId);
                return null;
            }

            closeQuietly(oldClient);

            Function<AiClientToolMcpVO, McpSyncClient> factory = clientFactories.get(mcpId);
            if (factory == null) {
                log.error("[McpRegistry] no client factory for mcpId={}", mcpId);
                return null;
            }
            McpSyncClient newClient = factory.apply(config);
            clientRegistry.put(mcpId, newClient);

            ToolCallback[] newCallbacks = new SyncMcpToolCallbackProvider(List.of(newClient)).getToolCallbacks();
            for (ToolCallback cb : newCallbacks) {
                String name = cb.getToolDefinition().name();
                AtomicReference<ToolCallback> ref = callbackRegistry.get(name);
                if (ref != null) {
                    ref.set(cb);
                }
            }

            registerBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId), McpSyncClient.class, newClient);

            consecutiveFailures.get(mcpId).set(0);
            failFastUntil.get(mcpId).set(0);
            long now = System.currentTimeMillis();
            lastSuccessTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);
            lastReconnectTime.computeIfAbsent(mcpId, k -> new AtomicLong(0)).set(now);

            log.info("[McpRegistry] recreated mcpId={} successfully, {} callbacks updated", mcpId, newCallbacks.length);

            AtomicReference<ToolCallback> ref = callbackRegistry.get(toolName);
            return ref != null ? ref.get() : null;

        } catch (Exception e) {
            int failures = consecutiveFailures.get(mcpId).incrementAndGet();
            log.error("[McpRegistry] recreate failed for mcpId={}, failures={}", mcpId, failures, e);

            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                long until = System.currentTimeMillis() + CIRCUIT_BREAKER_COOLDOWN_MS;
                failFastUntil.get(mcpId).set(until);
                log.error("[McpRegistry] circuit OPEN for mcpId={} until={}ms", mcpId, until);
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
        try {
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

    private <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition definition = builder.getRawBeanDefinition();
        definition.setScope(BeanDefinition.SCOPE_SINGLETON);
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }
        beanFactory.registerBeanDefinition(beanName, definition);
        log.info("[McpRegistry] re-registered bean: {}", beanName);
    }
}
