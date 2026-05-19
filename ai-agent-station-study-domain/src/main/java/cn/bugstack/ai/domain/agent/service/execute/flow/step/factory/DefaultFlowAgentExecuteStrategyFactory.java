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
 * @author TAgent
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
