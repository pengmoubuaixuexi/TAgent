package cn.bugstack.ai.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * G1-C 终极修复：让 MDC 业务字段（sessionId/userId/tenantId）自动传播到 Reactor 流式线程。
 * <p>
 * <b>问题</b>：Agent 走 {@code spec.stream()} 时，工具调用 / advisor 实际执行在 Reactor 调度线程
 * （{@code boundedElastic-*}）上，MDC 是 ThreadLocal 不会自动跟过去 →
 * {@code MeteredToolCallback} 读 {@code MDC.get("sessionId")} 得 null → 人工审批门查不到 SSE 通道
 * → 高危工具被 {@code APPROVAL_UNAVAILABLE} 静默放行/拦截，审批弹窗永远不出现。
 * <p>
 * <b>为什么不用 ToolContext</b>：试过 {@code spec.toolContext("sessionId", ...)}，但
 * {@code OpenAiChatModel.buildRequestPrompt} 用 {@code ModelOptionsUtils.copyToTarget}（Jackson）
 * 拷贝 runtime options，而 {@code OpenAiChatOptions.toolContext} 标了 {@code @JsonIgnore} →
 * 拷贝时被丢弃，per-request toolContext 在 OpenAI 流式链路里根本传不到工具（Spring AI 设计限制）。
 * <p>
 * <b>方案</b>：注册 MDC 业务键的 {@link io.micrometer.context.ThreadLocalAccessor} +
 * 开启 Reactor {@code Hooks.enableAutomaticContextPropagation()}。订阅时（在已带 MDC 的 worker 线程上
 * 触发 blockLast）捕获 ThreadLocal，Reactor 在每个 operator 执行（含工具调用）时恢复 →
 * boundedElastic 线程上 {@code MDC.get("sessionId")} 重新可用。
 * <p>
 * 与 {@link ThreadPoolConfig} / {@link DagExecutorConfig} 的 {@code ContextSnapshot.wrap} 互补：
 * 那两个管自建线程池的接力，这个管 Reactor 调度线程的接力。
 */
@Slf4j
@Configuration
public class ReactorContextPropagationConfig {

    /** 需要跨 Reactor 线程传播的 MDC 业务键。traceId/spanId 已由 micrometer-tracing 处理，不重复注册。 */
    private static final String[] PROPAGATED_MDC_KEYS = {"sessionId", "userId", "tenantId"};

    @PostConstruct
    public void enableMdcContextPropagation() {
        ContextRegistry registry = ContextRegistry.getInstance();
        for (String key : PROPAGATED_MDC_KEYS) {
            registry.registerThreadLocalAccessor(
                    key,
                    () -> MDC.get(key),                 // getValue：捕获时读
                    value -> MDC.put(key, value),       // setValue：恢复时写
                    () -> MDC.remove(key));             // cleanup：作用域结束清理
        }
        Hooks.enableAutomaticContextPropagation();
        log.info("[ReactorCtxProp] enabled automatic context propagation for MDC keys: {}",
                String.join(",", PROPAGATED_MDC_KEYS));
    }
}
