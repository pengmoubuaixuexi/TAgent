-- V054: attach execution run_id to persisted chat turns and event logs.
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

SET @schema_name = DATABASE();

SET @col_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ai_chat_memory'
      AND column_name = 'run_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_chat_memory ADD COLUMN run_id VARCHAR(128) NULL COMMENT ''Execution run ID'' AFTER agent_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ai_event_log'
      AND column_name = 'run_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_event_log ADD COLUMN run_id VARCHAR(128) NULL COMMENT ''Execution run ID'' AFTER session_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_chat_memory'
      AND index_name = 'idx_chat_memory_run_id'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_chat_memory_run_id ON ai_chat_memory (run_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_event_log'
      AND index_name = 'idx_event_log_run_id'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_event_log_run_id ON ai_event_log (run_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
