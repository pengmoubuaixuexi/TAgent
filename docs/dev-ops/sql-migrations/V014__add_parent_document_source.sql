-- V014: Add source column to ai_parent_document (original filename, for audit/display)
-- Previously only knowledge_tag was stored; source allows tracing chunks back to original files.

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

ALTER TABLE `ai_parent_document`
    ADD COLUMN `source` VARCHAR(512) DEFAULT NULL COMMENT '原始文件名' AFTER `knowledge_tag`;
