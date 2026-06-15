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

        /** 在飞流式调用断流触发器：每发流式 call 入口装新的，answer_now/steer emit 它实现 mid-stream 截断。
         *  lombok 生成 get/setCancelTrigger；此处只补 fire 帮手。 */
        private volatile reactor.core.publisher.Sinks.One<Object> cancelTrigger;

        /** 触发当前在飞流式 call 断流；无在飞 call 时 no-op（finalizeRequested 标记仍会在下个 step 边界被捕获）。 */
        public void fireCancelTrigger() {
            reactor.core.publisher.Sinks.One<Object> t = this.cancelTrigger;
            if (t != null) t.tryEmitValue(Boolean.TRUE);
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
