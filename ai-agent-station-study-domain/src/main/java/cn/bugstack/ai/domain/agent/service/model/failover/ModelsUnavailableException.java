package cn.bugstack.ai.domain.agent.service.model.failover;

import org.springframework.ai.retry.NonTransientAiException;

import java.util.List;
import java.util.stream.Collectors;

/** Every candidate rejected the request. Non-transient, so Spring AI does not retry the whole chain. */
public class ModelsUnavailableException extends NonTransientAiException {
    private final transient ModelTarget requested;
    private final transient List<ModelFailure> failures;

    public ModelsUnavailableException(ModelTarget requested, List<ModelFailure> failures) {
        super("所有可用模型都不可用：" + failures.stream()
                .map(f -> f.target().model() + "（" + f.describe() + "）")
                .collect(Collectors.joining("；")));
        this.requested = requested;
        this.failures = List.copyOf(failures);
    }

    public ModelTarget requested() { return requested; }

    public List<ModelFailure> failures() { return failures; }
}
