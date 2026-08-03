package cn.bugstack.ai.domain.agent.service.execute.flow.step.factory;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流程执行策略工厂类
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/24 14:28
 */
@Service
public class DefaultFlowAgentExecuteStrategyFactory {

    private final RootNode flowRootNode;

    public DefaultFlowAgentExecuteStrategyFactory(RootNode flowRootNode) {
        this.flowRootNode = flowRootNode;
    }

    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> armoryStrategyHandler(){
        return flowRootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // 任务执行步骤
        private int step = 1;

        // 最大任务步骤
        private int maxStep = 4;

        private StringBuilder executionHistory;

        private String currentTask;

        boolean isCompleted = false;

        /** P2.2.4：SSE 关闭后置 true，各 step 入口检查跳过 LLM 调用 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            cancelled.set(true);
        }

        // ==================== 执行干预（立即回答 / 引导回复）====================
        // 设计：docs/INTERVENTION_立即回答与引导回复_设计.md
        // 三字段默认惰性；不触发任何干预入口时全程不被分支读取，对原链路零影响。

        /** 立即回答标记：answer_now 置位；各 step doApply 入口检查后跳 finalize（flow=buildFinalDeliverable）。 */
        private final AtomicBoolean finalizeRequested = new AtomicBoolean(false);

        public boolean isFinalizeRequested() {
            return finalizeRequested.get();
        }

        public void requestFinalize() {
            finalizeRequested.set(true);
        }

        /** 引导新想法收件箱：steer 写入，当前/下一步 drain 折进 message。null=无引导。 */
        private volatile String steerIdea;

        public boolean hasSteerIdea() {
            return steerIdea != null && !steerIdea.isBlank();
        }

        public String drainSteerIdea() {
            String s = steerIdea;
            steerIdea = null;
            return s;
        }

        /**
         * 所有在飞流式调用的断流触发器。Flow Step4 会并行执行多个 DAG 子步骤，单个 volatile 引用会被
         * 后注册分支覆盖，导致 cancel 只能截断最后一个分支。按调用注册并在 finally 注销，广播时命中全部分支。
         */
        private final Set<reactor.core.publisher.Sinks.One<Object>> cancelTriggers = ConcurrentHashMap.newKeySet();

        public void registerCancelTrigger(reactor.core.publisher.Sinks.One<Object> trigger) {
            if (trigger == null) return;
            cancelTriggers.add(trigger);
            // 关闭请求可能发生在“创建 trigger”和“注册 trigger”之间。注册后补检查，避免漏掉竞态窗口。
            if (cancelled.get() || finalizeRequested.get()) {
                trigger.tryEmitValue(Boolean.TRUE);
            }
        }

        public void unregisterCancelTrigger(reactor.core.publisher.Sinks.One<Object> trigger) {
            if (trigger != null) cancelTriggers.remove(trigger);
        }

        /** 触发全部在飞流式 call 断流；无在飞 call 时 no-op。 */
        public void fireCancelTrigger() {
            for (reactor.core.publisher.Sinks.One<Object> trigger : cancelTriggers) {
                trigger.tryEmitValue(Boolean.TRUE);
            }
        }

        /** 立即回答可观测：累计本轮各 step（含被截断步的半截）的 token，供 marker 报告"打断之前叠加的 token"。 */
        private final java.util.concurrent.atomic.AtomicLong turnPromptTokens = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong turnCompletionTokens = new java.util.concurrent.atomic.AtomicLong();

        public void addTokens(long prompt, long completion) {
            if (prompt > 0) turnPromptTokens.addAndGet(prompt);
            if (completion > 0) turnCompletionTokens.addAndGet(completion);
        }

        public long cumulativePromptTokens() { return turnPromptTokens.get(); }
        public long cumulativeCompletionTokens() { return turnCompletionTokens.get(); }

        private Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap;

        // P2.2 11.2 Plan DAG 并行写入需要线程安全容器；ConcurrentHashMap 禁 null，把 setValue(k,null) 译成 remove
        private final Map<String, Object> dataObjects = new ConcurrentHashMap<>();

        public <T> void setValue(String key, T value) {
            if (value == null) {
                dataObjects.remove(key);
            } else {
                dataObjects.put(key, value);
            }
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }
    }

}
