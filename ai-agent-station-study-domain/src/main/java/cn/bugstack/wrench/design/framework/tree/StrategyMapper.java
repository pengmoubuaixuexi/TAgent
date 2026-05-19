package cn.bugstack.wrench.design.framework.tree;

public interface StrategyMapper<T, D, R> {

    StrategyHandler<T, D, R> get(T requestParameter, D dynamicContext) throws Exception;

}
