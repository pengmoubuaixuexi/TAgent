package cn.bugstack.ai.domain.agent.service.memory.working;

import java.time.Duration;
import java.util.Map;

/**
 * Working Memory（P1.2.2）：把 4 step 之间的中间产物落 Redis。
 * <p>
 * 出发点：当前 dynamicContext 在 JVM Map 里活，单进程崩溃 / SSE 客户端断线 / 异步 step 跨节点重启都会丢上下文。
 * Working Memory 作为旁路镜像，把每步的关键产物（analysisResult / executionResult / qualityReview 等）按
 * sessionId 维度落 Redis，TTL 默认 1h。后续可基于这条数据：
 * <ol>
 *   <li>客户端 SSE 重连时，先回放已完成 step 的产物，避免从头算；</li>
 *   <li>跨实例可见，多 pod 部署时 sticky session 失败仍可续；</li>
 *   <li>调试期把 Redis 当成"step 执行轨迹"查询库，比扒日志直观。</li>
 * </ol>
 * <p>
 * 默认绑定 Noop 实现（{@code agent.working-memory.enabled=false}）保证离线 / 无 Redis 环境零侵入；
 * 业务侧调用可无脑 put / get，不用判空。
 *
 * @author TAgent
 * 2026-04-26
 */
public interface IWorkingMemoryService {

    /** 写一个键值；value 内部按 JSON 字符串序列化。 */
    void put(String sessionId, String key, Object value);

    /** 写一个键值，自定义 TTL（覆盖配置默认值）。 */
    void put(String sessionId, String key, Object value, Duration ttl);

    /** 读一个键值，类型由调用方负责（JSON 反序列化失败返回 null）。 */
    <T> T get(String sessionId, String key, Class<T> type);

    /** 列出某 sessionId 当前所有 key→value（按 JSON 字符串原样返回，调试用）。 */
    Map<String, String> dump(String sessionId);

    /** 清空某 sessionId 所有 key（一般在任务完成 / 显式 reset 时调用）。 */
    void clear(String sessionId);

    /** 实现是否真的把数据落到了外部存储；Noop 实现返回 false，用于上层节流日志 / metric。 */
    default boolean isPersistent() {
        return false;
    }
}
