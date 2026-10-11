package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import java.time.LocalDateTime;

/** Internal storage record. Never serialize sourcePath or leaseToken to a browser. */
public record RagIngestionTask(String taskId, String owner, String ragId, String knowledgeTag,
        String fileName, String fileHash, long fileSize, String sourcePath, String dedupKey,
        String status, String stage, int progress, int chunkCount, int attempt, String error,
        String leaseToken, LocalDateTime leaseUntil, LocalDateTime createdAt, LocalDateTime updatedAt) { }
