-- V019:
-- 1) ai_episodic_memory 加 updated_at（每次 upsert 自动刷新，做"最后使用时间"排序用）
-- 2) ai_parent_document / ai_client_rag_order 的 user_id 列已存在，
--    但应用层一直没写——这里没 schema 变化，仅记录意图：后续应用层 INSERT 会带上。
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- 1. updated_at 列（MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用 stored proc 包一层做幂等）
DROP PROCEDURE IF EXISTS pV019AddUpdatedAt;
DELIMITER //
CREATE PROCEDURE pV019AddUpdatedAt()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_episodic_memory'
          AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE ai_episodic_memory
            ADD COLUMN updated_at DATETIME NOT NULL
                DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                COMMENT '最后使用/更新时间，跨会话注入排序按此';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_episodic_memory'
          AND index_name = 'idx_user_updated'
    ) THEN
        ALTER TABLE ai_episodic_memory ADD INDEX idx_user_updated (user_id, updated_at);
    END IF;
END //
DELIMITER ;
CALL pV019AddUpdatedAt();
DROP PROCEDURE pV019AddUpdatedAt;

-- 2. 历史行回填：updated_at = created_at
UPDATE ai_episodic_memory
SET updated_at = created_at
WHERE updated_at < created_at;
