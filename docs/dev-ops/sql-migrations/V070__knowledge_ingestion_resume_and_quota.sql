-- Knowledge ingestion: resumable retries and per-user daily quota. Back up MySQL before applying.
-- resume_fingerprint: split + enrichment identity of the latest attempt; a retry with the same value
--   skips parents that were completely written instead of clearing the whole file.
-- retry_day / retry_day_count: retries made on one Beijing calendar day, counted toward the daily ingestion quota.
-- rag_name: holds the original file name; uploads accept names up to 255 characters (was 50, so long names failed at publish).
SET NAMES utf8mb4;
ALTER TABLE ai_rag_ingestion_task ADD COLUMN resume_fingerprint CHAR(64) NULL;
ALTER TABLE ai_rag_ingestion_task ADD COLUMN retry_day DATE NULL;
ALTER TABLE ai_rag_ingestion_task ADD COLUMN retry_day_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_client_rag_order MODIFY COLUMN rag_name VARCHAR(255) NOT NULL COMMENT '原始文件名';
