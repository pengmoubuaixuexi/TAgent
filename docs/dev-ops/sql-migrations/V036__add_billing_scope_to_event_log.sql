-- V036: add billing_scope to ai_event_log for future quota/billing classification.
-- USER_CHARGEABLE: can be counted toward user quota later.
-- SYSTEM_OVERHEAD: internal routing/RAG/memory helper cost; still counted as system cost.
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

SET @col_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_event_log'
      AND column_name = 'billing_scope'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_event_log ADD COLUMN billing_scope VARCHAR(32) NOT NULL DEFAULT ''USER_CHARGEABLE'' COMMENT ''额度/计费口径：USER_CHARGEABLE=用户可扣额度，SYSTEM_OVERHEAD=系统内部开销'' AFTER agent_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_event_log'
      AND index_name = 'idx_billing_scope_created'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE ai_event_log ADD INDEX idx_billing_scope_created (billing_scope, created_at)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE ai_event_log
SET billing_scope = 'SYSTEM_OVERHEAD'
WHERE step_name IN ('unified_router', 'rag_router', 'query_decomposer', 'query_rewriter',
                    'memory_extractor', 'summarizer', 'reranker');
