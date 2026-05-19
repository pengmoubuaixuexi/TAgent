package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.IntentVO;

/**
 * 策略路由器（P0.1.2）。
 * <p>
 * 在 {@link IIntentRouter} 给出 IntentVO 之后，决定本次请求实际走哪个 IExecuteStrategy。
 * <p>
 * 默认行为 = passthrough（返回 agent 自身配置的 strategy），开 live-mode 后才按 intent 改写。
 */
public interface IStrategyRouter {

    /**
     * @param intent         意图分类结果（UNKNOWN 时通常 fallback agentStrategy）
     * @param agentStrategy  agent DB 表里配的默认策略
     * @return 最终要执行的 strategy bean name，对应 executeStrategyMap 的 key
     */
    RoutingDecision route(IntentVO intent, String agentStrategy);

    /** 路由决策；除了 strategy 本身还带 reject 标志（敏感意图直接拒绝） */
    record RoutingDecision(String strategy, boolean rejected, String rejectReason) {

        public static RoutingDecision passthrough(String strategy) {
            return new RoutingDecision(strategy, false, null);
        }

        public static RoutingDecision reject(String reason) {
            return new RoutingDecision(null, true, reason);
        }
    }
}
