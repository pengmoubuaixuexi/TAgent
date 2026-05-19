-- ============================================================================
-- P1.2.3：多租户隔离 —— conversation_id 扩展为 tenant:user:session 复合键
-- ----------------------------------------------------------------------------
-- 目的：chat memory（ai_chat_memory / ai_chat_memory_summary）的 conversation_id
--       从 sessionId 改为 {tenantId}:{userId}:{sessionId}，实现多租户天然隔离。
--       业务代码在 step 节点拼接复合键，DDL 仅需扩宽 VARCHAR。
-- ----------------------------------------------------------------------------
-- 回滚：ALTER TABLE ... MODIFY COLUMN conversation_id VARCHAR(64);
-- ============================================================================

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

ALTER TABLE `ai_chat_memory`
    MODIFY COLUMN `conversation_id` VARCHAR(200) NOT NULL COMMENT '复合对话 ID：{tenantId}:{userId}:{sessionId}（P1.2.3 多租户隔离）',
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE `ai_chat_memory_summary`
    MODIFY COLUMN `conversation_id` VARCHAR(200) NOT NULL COMMENT '复合对话 ID：{tenantId}:{userId}:{sessionId}（P1.2.3 多租户隔离）',
    ALGORITHM=INPLACE, LOCK=NONE;
