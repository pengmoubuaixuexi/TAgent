package cn.bugstack.ai.domain.agent.service.execute.common;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * LLM 调用网关：唯一被 Resilience4j 注解保护的入口。
 *
 * 为什么单独抽出来一个 Bean：Spring AOP 靠代理对象拦截，如果 @Retry/@CircuitBreaker 直接放在
 * AbstractExecuteSupport 上，Step*Node 的 doApply() 内部调用的是 this.callChatClientWithLogging，
 * 是"自调用"，根本不过代理，注解静默失效。把实际执行动作抽到独立 Bean 后，各 Step 通过依赖注入
 * 拿到代理对象，注解链才真正生效。
 *
 * 降级策略：fallback 只写在最外层 @Retry 上。一次请求无论 CB 拒绝还是方法失败，都最终一次性汇入
 * fallback 返回 null — 调用方(callChatClientWithLogging) 原本就有 null 判空分支，行为一致。
 */
@Slf4j
@Service
public class LlmCallGateway {

    /**
     * 被保护的 LLM 调用：Retry 外层，CircuitBreaker 内层。
     *
     * 入参是 Supplier<CallResponseSpec> 而不是 CallResponseSpec：
     * Spring AI 1.0.0 的 CallResponseSpec 非幂等 —— 第一次 chatResponse() 会消费 advisor 链，
     * 对同一个 spec 第二次调用会抛 "No CallAdvisors available to execute"。
     * 改成 Supplier 让每次重试都【重新构建】一个全新的 spec，消费链可重新填充。
     *
     * - 所有异常(含 CB 拒绝产生的 CallNotPermittedException)向上抛；
     * - Retry 层按 retry-exceptions / ignore-exceptions 决策；
     * - 最终失败进 fallback，返回 null 让上游走降级分支。
     */
    @Retry(name = "llmCall", fallbackMethod = "fallback")
    @CircuitBreaker(name = "llmCall")
    public ChatResponse call(Supplier<ChatClient.CallResponseSpec> specSupplier) {
        return specSupplier.get().chatResponse();
    }

    /**
     * fallback：Retry 次数耗尽、或 CB 拒绝(CallNotPermittedException 被 ignore-exceptions 跳过重试)时进这里。
     * 返回 null 让上游 callChatClientWithLogging 走现有的 "response == null" 分支，调用方拿到 null 串。
     */
    @SuppressWarnings("unused")
    public ChatResponse fallback(Supplier<ChatClient.CallResponseSpec> specSupplier, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.warn("LLM 调用被熔断器拒绝(CB OPEN)，进入降级；cause={}", t.getMessage());
        } else {
            log.error("LLM 调用重试耗尽，进入降级；cause={}", t.toString());
        }
        return null;
    }

}
