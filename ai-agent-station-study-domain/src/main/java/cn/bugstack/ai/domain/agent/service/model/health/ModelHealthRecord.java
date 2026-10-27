package cn.bugstack.ai.domain.agent.service.model.health;

import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;

/**
 * Persisted health of one connection + model (V071 {@code ai_model_health}). Times are epoch millis.
 *
 * @param lastFailureMessage redacted provider text, shown only to administrators or the model owner
 */
public record ModelHealthRecord(ModelTarget target, boolean healthy, int consecutiveFailures,
                                ModelFailure.Kind lastFailureKind, Integer lastFailureStatus, String lastFailureMessage,
                                Long lastFailureAt, Long lastSuccessAt, Long unhealthySince, Long lastProbeAt, Long lastNotifiedAt) {
    /** Safe wording of the last failure, e.g. "模型不存在或已下线，HTTP 400"; null when there was none. */
    public String lastReason() {
        if (lastFailureKind == null) return null;
        return new ModelFailure(target, lastFailureKind, lastFailureStatus == null ? 0 : lastFailureStatus, lastFailureMessage).describe();
    }
}
