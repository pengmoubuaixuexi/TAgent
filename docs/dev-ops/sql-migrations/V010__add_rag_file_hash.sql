-- ============================================================================
-- P2.3 12.5 文件去重 + 增量索引：ai_client_rag_order 加 file_hash / file_size
-- ============================================================================
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

ALTER TABLE `ai_client_rag_order`
    ADD COLUMN `file_hash` VARCHAR(64) NULL COMMENT '文件 SHA-256（幂等去重）' AFTER `knowledge_tag`,
    ADD COLUMN `file_size` BIGINT NULL COMMENT '文件原始大小（字节）' AFTER `file_hash`,
    ADD INDEX `idx_file_hash` (`file_hash`);
