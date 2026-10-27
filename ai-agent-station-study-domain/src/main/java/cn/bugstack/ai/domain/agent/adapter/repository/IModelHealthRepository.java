package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRecord;

import java.util.List;

/** V071 {@code ai_model_health}: survives restarts, so a known-bad model stays skipped until it recovers. */
public interface IModelHealthRepository {
    List<ModelHealthRecord> loadAll();

    void save(ModelHealthRecord record);

    void delete(cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget target);
}
