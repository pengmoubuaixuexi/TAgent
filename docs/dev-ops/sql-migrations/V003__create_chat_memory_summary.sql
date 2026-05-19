-- ============================================================================
-- P0.2.2：滚动摘要记忆
-- ----------------------------------------------------------------------------
-- 目的：长对话超过窗口阈值后，把最旧的 K 条让 LLM 摘成一段话存这里；
--       下次拿历史就用 [summary 系统消息 + 最近 N 条] 代替全量。
-- ----------------------------------------------------------------------------
-- 一个 conversation 一行（覆盖式更新）；version 字段用于追踪摘要演进次数，
-- 后续可做"保留过去 M 个版本"。当前摘要器 OFF 时此表为空，无副作用。
-- 回滚：DROP TABLE ai_chat_memory_summary;
-- ============================================================================

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_chat_memory_summary` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    `conversation_id`   VARCHAR(64)  NOT NULL                 COMMENT '对话 ID（== sessionId）',
    `summary`           LONGTEXT     NOT NULL                 COMMENT '摘要内容；下次读取时作为 SystemMessage 注入',
    `last_message_id`   BIGINT       NULL                     COMMENT '摘要覆盖到的最新 ai_chat_memory.id；增量摘要用',
    `version`           INT          NOT NULL DEFAULT 1       COMMENT '摘要版本号，每次刷新 +1',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Spring AI ChatMemory 滚动摘要表（P0.2.2）';
