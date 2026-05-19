package cn.bugstack.ai.config;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class ThreadPoolConfig {

    /**
     * P1.6 后由 Micrometer Context Propagation 统一捕获跨线程上下文：
     * 包含 MDC（slf4j MDCContextAccessor）+ OTel Observation 作用域（ObservationThreadLocalAccessor）。
     * 自己手抄 MDC 已经不够——OTel 的 traceId/spanId 不在 MDC 表里，必须靠 ContextSnapshot 接力。
     */
    private static final ContextSnapshotFactory SNAPSHOT_FACTORY = ContextSnapshotFactory.builder().build();

    @Resource
    private MeterRegistry meterRegistry;

    @Bean
    @ConditionalOnMissingBean(ThreadPoolExecutor.class)
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolConfigProperties properties) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        // 实例化策略
        RejectedExecutionHandler handler;
        switch (properties.getPolicy()){
            case "AbortPolicy":
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
            case "DiscardPolicy":
                handler = new ThreadPoolExecutor.DiscardPolicy();
                break;
            case "DiscardOldestPolicy":
                handler = new ThreadPoolExecutor.DiscardOldestPolicy();
                break;
            case "CallerRunsPolicy":
                handler = new ThreadPoolExecutor.CallerRunsPolicy();
                break;
            default:
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
        }
        // 创建线程池：每个 submit/execute 的 Runnable 都被 ContextSnapshot.wrap 一层，
        // 同时把 MDC（traceId/spanId/requestId/sessionId 等）+ OTel Observation 作用域接力到 agent 执行线程，
        // 保证 ELK 按 traceId 串链路，Jaeger 跨 4 step 串 span。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getBlockQueueSize()),
                Executors.defaultThreadFactory(),
                handler) {
            @Override
            public void execute(Runnable command) {
                super.execute(wrap(command));
            }
        };
        meterRegistry.gauge("thread.pool.executor.active.count", executor, ThreadPoolExecutor::getActiveCount);
        meterRegistry.gauge("thread.pool.executor.queue.size", executor, tp -> tp.getQueue().size());
        return executor;
    }

    /**
     * 双保险：先用 ContextSnapshot 接力 OTel + 注册的 ThreadLocal 上下文（含 MDC，由 Slf4jMDCContextAccessor 注册）；
     * 再叠一层手抄 MDC，防 ContextSnapshot 注册器在某些场景没装上时业务追踪字段丢失。
     */
    private static Runnable wrap(Runnable task) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot;
        try {
            snapshot = SNAPSHOT_FACTORY.captureAll();
        } catch (Throwable t) {
            // 极端情况下（比如单元测试不加载 micrometer-context）直接降级为纯 MDC 透传
            return wrapMdcOnly(task, capturedMdc);
        }
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                // setThreadLocals 已恢复 OTel + 注册器涵盖的 ThreadLocal；
                // 兜底：业务自己手放进 MDC 但没走注册器的字段，再补一遍
                if (capturedMdc != null) {
                    capturedMdc.forEach(MDC::put);
                }
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    private static Runnable wrapMdcOnly(Runnable task, Map<String, String> captured) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

}
