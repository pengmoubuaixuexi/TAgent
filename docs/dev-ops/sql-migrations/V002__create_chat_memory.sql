-- ============================================================================
-- P0.2.1：持久化 ChatMemory
-- ----------------------------------------------------------------------------
-- 目的：把 MessageWindowChatMemory 默认的 InMemoryChatMemoryRepository 换成 JDBC 后端，
--       JVM 重启后多轮对话不丢。conversation_id == sessionId（业务侧约定）。
-- ----------------------------------------------------------------------------
-- 索引设计：
--   - PRIMARY KEY 给 id（自增写入）
--   - idx_conv_created：按 conversation_id 取消息时按时间排序回放
--   - 不加 conversation_id 上的唯一约束，单 conversation 多消息正常
-- 回滚：DROP TABLE ai_chat_memory;
-- ============================================================================

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_chat_memory` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `conversation_id` VARCHAR(64)  NOT NULL                COMMENT '对话 ID，等价于业务 sessionId',
    `message_type`    VARCHAR(16)  NOT NULL                COMMENT '消息类型：USER / ASSISTANT / SYSTEM',
    `content`         LONGTEXT     NOT NULL                COMMENT '消息内容（不限长，旧消息走滚动摘要压缩）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conv_created` (`conversation_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Spring AI ChatMemory 持久化表（P0.2.1）';
