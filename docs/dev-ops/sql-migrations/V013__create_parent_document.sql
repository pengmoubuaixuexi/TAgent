-- V013: Parent Document table for Parent Document Retriever (P2.3 12.4)
-- Stores large parent documents; small child chunks go to PgVector with parent_id in metadata.
-- At retrieval time: children are retrieved via vector similarity, then their parent docs
-- are fetched from this table and fed to the LLM for richer context.

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_parent_document` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `parent_id`   VARCHAR(128) NOT NULL COMMENT 'Parent document unique ID，对应 child metadata.parent_id',
    `content`     MEDIUMTEXT   NOT NULL COMMENT 'Parent document full text（大块，~2000 chars）',
    `knowledge_tag` VARCHAR(128) DEFAULT NULL COMMENT '知识库标签',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_parent_id` (`parent_id`),
    INDEX `idx_knowledge_tag` (`knowledge_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Parent documents for two-level retrieval';
