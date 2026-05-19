-- V035: extend ai_event_log dimensions for per-user and per-agent usage reports.
-- Note: V015 may already add user_id, so this script uses IF NOT EXISTS.
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

ALTER TABLE ai_event_log
    ADD COLUMN IF NOT EXISTS user_id   VARCHAR(64) NULL COMMENT 'User ID' AFTER session_id,
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NULL COMMENT 'Tenant ID' AFTER user_id,
    ADD COLUMN IF NOT EXISTS agent_id  VARCHAR(64) NULL COMMENT 'Agent ID' AFTER tenant_id;

SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(COUNT(*) = 0,
              'CREATE INDEX idx_user_id_created ON ai_event_log (user_id, created_at)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_event_log'
      AND index_name = 'idx_user_id_created'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
              'CREATE INDEX idx_agent_id ON ai_event_log (agent_id)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_event_log'
      AND index_name = 'idx_agent_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
