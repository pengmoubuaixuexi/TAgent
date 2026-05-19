-- V020: ai_episodic_memory 加 last_summarized_msg_count，用于"每 2 轮（4 条消息）"节流。
-- 每次 upsert 时记录"摘要写入时 ChatMemory 中消息总数"，下次判断
--   currentMsgCount - last_summarized_msg_count >= 4 才再次摘要。
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

DROP PROCEDURE IF EXISTS pV020AddLastSummarized;
DELIMITER //
CREATE PROCEDURE pV020AddLastSummarized()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_episodic_memory'
          AND column_name = 'last_summarized_msg_count'
    ) THEN
        ALTER TABLE ai_episodic_memory
            ADD COLUMN last_summarized_msg_count INT NOT NULL DEFAULT 0
                COMMENT '上次摘要时 ChatMemory 中消息总数，用于每 2 轮节流';
    END IF;
END //
DELIMITER ;
CALL pV020AddLastSummarized();
DROP PROCEDURE pV020AddLastSummarized;
