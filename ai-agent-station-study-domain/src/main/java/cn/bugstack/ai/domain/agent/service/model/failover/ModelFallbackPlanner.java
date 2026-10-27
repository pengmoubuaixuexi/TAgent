package cn.bugstack.ai.domain.agent.service.model.failover;

import java.util.List;

/** Ordered substitutes for a requested target. Implementations never cross ownership. */
@FunctionalInterface
public interface ModelFallbackPlanner {
    ModelFallbackPlanner NONE = (requested, traits) -> List.of();

    List<ModelTarget> fallbacks(ModelTarget requested, ModelRequestTraits traits);
}
