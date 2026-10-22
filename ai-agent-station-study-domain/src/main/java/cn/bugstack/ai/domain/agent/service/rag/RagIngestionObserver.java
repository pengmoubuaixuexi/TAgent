package cn.bugstack.ai.domain.agent.service.rag;

/** Called before each storage phase; throwing cancellation stops subsequent writes. */
@FunctionalInterface
public interface RagIngestionObserver {
    RagIngestionObserver NONE = (stage, percent, chunks) -> { };
    void progress(String stage, int percent, int chunks);

    /**
     * Called once after parsing and before any model or storage work.
     * Returns true when an earlier attempt used the same fingerprint, so its completed parents may be reused;
     * otherwise the observer must have cleared that attempt's partial output before returning false.
     */
    default boolean prepare(String fingerprint) { return false; }

    /** Upper bound of parent blocks one file may produce; checked before any model work. */
    default int maxParents() { return Integer.MAX_VALUE; }

    /** The file was stored, but with reduced quality the uploader should know about (e.g. enrichment model unavailable). */
    default void warn(String message) { }
}
