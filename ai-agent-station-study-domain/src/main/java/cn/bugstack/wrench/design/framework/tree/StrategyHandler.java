package cn.bugstack.wrench.design.framework.tree;

@FunctionalInterface
public interface StrategyHandler<T, D, R> {

    StrategyHandler DEFAULT = (request, context) -> null;

    R apply(T requestParameter, D dynamicContext) throws Exception;

}
