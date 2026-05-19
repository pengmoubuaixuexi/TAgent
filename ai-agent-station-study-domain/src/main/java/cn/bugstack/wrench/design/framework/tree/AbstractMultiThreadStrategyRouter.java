package cn.bugstack.wrench.design.framework.tree;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractMultiThreadStrategyRouter<T, D, R>
        implements StrategyMapper<T, D, R>, StrategyHandler<T, D, R> {

    protected StrategyHandler<T, D, R> defaultStrategyHandler = StrategyHandler.DEFAULT;

    public R router(T requestParameter, D dynamicContext) throws Exception {
        StrategyHandler<T, D, R> strategyHandler = get(requestParameter, dynamicContext);
        if (strategyHandler != null) {
            return strategyHandler.apply(requestParameter, dynamicContext);
        }
        return defaultStrategyHandler.apply(requestParameter, dynamicContext);
    }

    @Override
    public R apply(T requestParameter, D dynamicContext) throws Exception {
        multiThread(requestParameter, dynamicContext);
        return doApply(requestParameter, dynamicContext);
    }

    protected abstract void multiThread(T requestParameter, D dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException;

    protected abstract R doApply(T requestParameter, D dynamicContext) throws Exception;

    public StrategyHandler<T, D, R> getDefaultStrategyHandler() {
        return defaultStrategyHandler;
    }

    public void setDefaultStrategyHandler(StrategyHandler<T, D, R> defaultStrategyHandler) {
        this.defaultStrategyHandler = defaultStrategyHandler;
    }

}
