package cn.bugstack.ai.config;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2026-05-07：flow Step4 DAG 并行执行专用 IO 线程池。
 * <p>
 * <b>为什么单独建</b>：
 * <ul>
 *   <li>原 {@code threadPoolExecutor} 给 Episodic 摘要 / Intent shadow / Dispatch / LTM 抽取等多条异步链路共用，
 *       高并发下 DAG step 并行容易跟它们互抢资源、排队</li>
 *   <li>原来用 {@code CompletableFuture.runAsync(...)} 不传池，默认走 ForkJoinPool.commonPool()，
 *       并行度 = CPU核数-1（4 核机只有 3 个 worker），且全 JVM 共享，被其他 Stream API 抢更厉害</li>
 *   <li>DAG step 本质是 LLM HTTP 调用 = IO 阻塞，要的是"线程多 + 单线程开销小"——
 *       JDK 17 没虚拟线程，用核心 30 / max 100 的 IO 池近似覆盖</li>
 * </ul>
 * <p>
 * <b>升 JDK 21 时的迁移路径</b>：把这里的 ThreadPoolExecutor 换成
 * {@code Executors.newVirtualThreadPerTaskExecutor()}，下游 {@code @Resource(name="dagExecutor") Executor}
 * 不用动。或者直接 ConditionalOnJava 双 bean。
 * <p>
 * 上下文接力：每个 task 都被 ContextSnapshot.wrap 一层，跟 {@link ThreadPoolConfig} 镜像，
 * 保证 traceId / spanId / requestId / sessionId 等 MDC + OTel 作用域都接得住。
 */
@Slf4j
@Configuration
public class DagExecutorConfig {

    private static final ContextSnapshotFactory SNAPSHOT_FACTORY = ContextSnapshotFactory.builder().build();

    @Resource
    private MeterRegistry meterRegistry;

    @Value("${agent.dag-executor.core-pool-size:30}")
    private int corePoolSize;

    @Value("${agent.dag-executor.max-pool-size:100}")
    private int maxPoolSize;

    @Value("${agent.dag-executor.queue-size:200}")
    private int queueSize;

    @Value("${agent.dag-executor.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * 命名要清晰，注入侧用 {@code @Resource(name = "dagExecutor")} 或 {@code @Qualifier} 显式取它。
     */
    @Bean(name = "dagExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor dagExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "dag-step-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        // 满了 → CallerRunsPolicy 退化到主线程跑，至少不丢任务
        // 替代方案 AbortPolicy 会抛异常，对 DAG 致命；DiscardPolicy 静默丢，更糟
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize,
                keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            public void execute(Runnable command) {
                super.execute(wrap(command));
            }
        };
        log.info("[DagExecutor] initialized core={} max={} queue={} keepAlive={}s",
                corePoolSize, maxPoolSize, queueSize, keepAliveSeconds);
        meterRegistry.gauge("dag.executor.active.count", pool, ThreadPoolExecutor::getActiveCount);
        meterRegistry.gauge("dag.executor.queue.size", pool, p -> p.getQueue().size());
        meterRegistry.gauge("dag.executor.pool.size", pool, ThreadPoolExecutor::getPoolSize);
        return pool;
    }

    /** 跟 ThreadPoolConfig.wrap 镜像：ContextSnapshot + MDC 双保险接力 */
    private static Runnable wrap(Runnable task) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot;
        try {
            snapshot = SNAPSHOT_FACTORY.captureAll();
        } catch (Throwable t) {
            return wrapMdcOnly(task, capturedMdc);
        }
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                if (capturedMdc != null) capturedMdc.forEach(MDC::put);
                task.run();
            } finally {
                if (previous != null) MDC.setContextMap(previous);
                else MDC.clear();
            }
        };
    }

    private static Runnable wrapMdcOnly(Runnable task, Map<String, String> captured) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) MDC.setContextMap(captured);
            else MDC.clear();
            try {
                task.run();
            } finally {
                if (previous != null) MDC.setContextMap(previous);
                else MDC.clear();
            }
        };
    }
}
