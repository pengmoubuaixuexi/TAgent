package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.IntentVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.IntentCategoryEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * 基于 Intent 的默认策略路由（P0.1.2）。
 * <p>
 * <b>live-mode 默认 off</b>：开启前 dispatch 行为完全不变（passthrough agent 自身策略）。
 * 设 {@code agent.intent-router.live-mode=true} 后才按下表改写：
 *
 * <pre>
 *   CHAT       → fixedAgentExecuteStrategy   （单跳，无 RAG/工具）
 *   QA         → fixedAgentExecuteStrategy   （单跳 + agent 自带的 RAG advisor）
 *   TOOL       → flowAgentExecuteStrategy    （规划 + 工具调用）
 *   PLAN       → autoAgentExecuteStrategy    （4-step 自治）
 *   SENSITIVE  → 拒绝 + 友好提示（P2 接入 human-in-loop 后改为审批流）
 *   UNKNOWN    → 回退 agentStrategy（不强制）
 * </pre>
 * <p>
 * 后续可改为 DB 表 {@code ai_intent_route_config} 驱动；当前先静态映射，路由测试稳定再迁。
 */
@Slf4j
@Service
public class IntentBasedStrategyRouter implements IStrategyRouter {

    private static final Map<IntentCategoryEnumVO, String> INTENT_TO_STRATEGY = new EnumMap<>(IntentCategoryEnumVO.class);
    static {
        INTENT_TO_STRATEGY.put(IntentCategoryEnumVO.CHAT, "fixedAgentExecuteStrategy");
        INTENT_TO_STRATEGY.put(IntentCategoryEnumVO.QA, "fixedAgentExecuteStrategy");
        INTENT_TO_STRATEGY.put(IntentCategoryEnumVO.TOOL, "flowAgentExecuteStrategy");
        INTENT_TO_STRATEGY.put(IntentCategoryEnumVO.PLAN, "autoAgentExecuteStrategy");
        // SENSITIVE 不映射策略，单独走 reject 分支
        // UNKNOWN 不映射，由调用方 fallback agentStrategy
    }

    @Value("${agent.intent-router.live-mode:false}")
    private boolean liveMode;

    @Override
    public RoutingDecision route(IntentVO intent, String agentStrategy) {
        // shadow 模式：永远 passthrough
        if (!liveMode) {
            return RoutingDecision.passthrough(agentStrategy);
        }
        if (intent == null) {
            return RoutingDecision.passthrough(agentStrategy);
        }

        IntentCategoryEnumVO category = intent.getCategory();

        // 敏感意图直接拒绝；P2 human-in-loop 上线后改为发起审批
        if (category == IntentCategoryEnumVO.SENSITIVE) {
            return RoutingDecision.reject("检测到敏感操作意图，已暂停执行（待人工审批）");
        }

        // UNKNOWN / 未映射的类目都回退 agent 自身策略
        String mapped = INTENT_TO_STRATEGY.get(category);
        if (mapped == null) {
            log.debug("intent {} not mapped, fallback to agent strategy {}", category, agentStrategy);
            return RoutingDecision.passthrough(agentStrategy);
        }

        if (!mapped.equals(agentStrategy)) {
            log.info("intent.live-route override: intent={} agentStrategy={} → routedStrategy={}",
                    category.getCode(), agentStrategy, mapped);
        }
        return RoutingDecision.passthrough(mapped);
    }
}
