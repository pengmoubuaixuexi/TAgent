package cn.bugstack.ai.domain.agent.service.execute.auto.step.factory;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.RootNode;
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
 * 工厂类
 *
 * @author TAgent
 * 2025/7/27 16:34
 */
@Service
public class DefaultAutoAgentExecuteStrategyFactory {

    private final RootNode executeRootNode;

    public DefaultAutoAgentExecuteStrategyFactory(RootNode executeRootNode) {
        this.executeRootNode = executeRootNode;
    }

    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> armoryStrategyHandler(){
        return executeRootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // 任务执行步骤
        private int step = 1;

        // 最大任务步骤
        private int maxStep = 1;

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

        // P2.2 11.2 Plan DAG / token-streaming 等并行场景下需要线程安全的容器；
        // ConcurrentHashMap 不允许 null value，所以 setValue(k, null) 翻译成 remove 保留原 reset 语义
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
