package cn.bugstack.ai.domain.agent.service.model.failover;

import java.util.List;

/** Health and reporting hooks. The defaults leave the request path unchanged. Never throw. */
public interface ModelFailoverObserver {
    ModelFailoverObserver NONE = new ModelFailoverObserver() { };

    /** False skips the target while another candidate is still considered healthy. */
    default boolean available(ModelTarget target) { return true; }

    default void succeeded(ModelTarget target) { }

    default void failed(ModelFailure failure) { }

    /** The answer came from {@code actual} because every earlier target was unavailable. */
    default void substituted(ModelCall call, ModelTarget requested, ModelTarget actual, List<ModelFailure> failures) { }

    static ModelFailoverObserver of(ModelFailoverObserver... observers) {
        return new ModelFailoverObserver() {
            @Override public boolean available(ModelTarget target) {
                for (ModelFailoverObserver o : observers) if (!o.available(target)) return false;
                return true;
            }
            @Override public void succeeded(ModelTarget target) { for (ModelFailoverObserver o : observers) o.succeeded(target); }
            @Override public void failed(ModelFailure failure) { for (ModelFailoverObserver o : observers) o.failed(failure); }
            @Override public void substituted(ModelCall call, ModelTarget requested, ModelTarget actual, List<ModelFailure> failures) {
                for (ModelFailoverObserver o : observers) o.substituted(call, requested, actual, failures);
            }
        };
    }
}
