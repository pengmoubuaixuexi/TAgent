package cn.bugstack.ai.domain.agent.service.rag;

/** Administrative deletion of the directory entry and its owner-scoped stored data. */
public interface IRagFileDeletionService {
    boolean deleteById(Long id);
    boolean deleteByRagId(String ragId);
    /** Trusted ingestion task scope, also covers a failed upload that has no directory yet. */
    void deleteIngestionFile(RagFileScope scope);
}
