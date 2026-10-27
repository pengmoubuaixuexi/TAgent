package cn.bugstack.ai.domain.agent.service.model.catalog;

import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;

import java.util.Set;

/**
 * One model in the failover catalog: owner + connection + model name, registered once.
 *
 * @param ownerScope      {@link #PLATFORM}, or the user that owns a personal (BYOK) connection
 * @param inputModalities TEXT and optionally IMAGE, as in {@code ai_client_model.capabilities_json}
 * @param toolCalling     false only for a model known to reject tool definitions
 * @param published       for platform entries: some enabled model row publishes this model to users
 */
public record ModelCatalogEntry(String ownerScope, String apiId, String modelName, String tier, int sortOrder,
                                Set<String> inputModalities, boolean toolCalling, boolean enabled, boolean published) {
    public static final String PLATFORM = "PLATFORM";
    public static final java.util.List<String> TIERS = java.util.List.of("small", "medium", "large");

    public ModelCatalogEntry {
        tier = normalizeTier(tier);
        inputModalities = inputModalities == null || inputModalities.isEmpty() ? Set.of("TEXT") : Set.copyOf(inputModalities);
    }

    public ModelTarget target() { return new ModelTarget(apiId, modelName); }

    public boolean platform() { return PLATFORM.equals(ownerScope); }

    public boolean acceptsImages() { return inputModalities.contains("IMAGE"); }

    public int tierRank() { return TIERS.indexOf(tier); }

    /** Unknown values have always been read as medium. */
    public static String normalizeTier(String tier) {
        String value = tier == null ? "" : tier.trim().toLowerCase();
        return TIERS.contains(value) ? value : "medium";
    }
}
