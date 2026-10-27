package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;

import java.util.List;
import java.util.Map;

/** The failover catalog (V071 {@code ai_model_catalog}). */
public interface IModelCatalogRepository {
    /** Enabled entries on an active connection; a personal entry also needs its owner's active secret. */
    List<ModelCatalogEntry> usableEntries();

    /** apiId → {@link ModelCatalogEntry#PLATFORM} or the owner of a personal connection, for every active connection. */
    Map<String, String> connectionScopes();

    /** Every entry of one owner, enabled or not, for the management pages. */
    List<ModelCatalogEntry> listByScope(String ownerScope);

    /** Insert, or update tier/order/capabilities/enabled of the same owner + connection + model. */
    void upsert(ModelCatalogEntry entry);

    boolean update(String ownerScope, String apiId, String modelName, String tier, int sortOrder, boolean enabled);

    boolean delete(String ownerScope, String apiId, String modelName);

    /** Some enabled model row or catalog entry still uses this connection + model name. */
    default boolean referenced(String apiId, String modelName) { return true; }
}
