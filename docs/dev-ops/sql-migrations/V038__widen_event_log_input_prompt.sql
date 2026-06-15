-- V038: ai_event_log.input_prompt 从 TEXT(64KB) 加宽到 MEDIUMTEXT(16MB)
--
-- 根因：input_prompt 原为 TEXT，上限 65,535 字节。flow 大 prompt（实测 promptTokens 4 万+，
-- 中文+JSON 混合折算 utf8mb4 字节远超 64KB）写入时 MySQL 抛 "Data too long for column 'input_prompt'"，
-- 被 EventLogService.log 的 catch 静默吞掉 → ai_event_log 自 2026-05-20 起停止写入。
-- 加宽到 MEDIUMTEXT(16MB) 与 output_text 对齐，可重放完整 prompt。
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

ALTER TABLE ai_event_log
    MODIFY COLUMN input_prompt MEDIUMTEXT NOT NULL COMMENT '发往 LLM 的完整 prompt';
