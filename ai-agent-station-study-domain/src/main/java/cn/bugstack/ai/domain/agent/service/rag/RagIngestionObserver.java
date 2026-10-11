package cn.bugstack.ai.domain.agent.service.rag;

/** Called before each storage phase; throwing cancellation stops subsequent writes. */
@FunctionalInterface
public interface RagIngestionObserver {
    RagIngestionObserver NONE = (stage, percent, chunks) -> { };
    void progress(String stage, int percent, int chunks);
}
