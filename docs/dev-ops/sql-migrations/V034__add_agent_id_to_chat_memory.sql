-- V034: 给 ai_chat_memory 加 agent_id 列，区分不同 agent 的对话历史
-- summary 表不加（摘要是按 conversation 维度的，与 agent 无关）

ALTER TABLE `ai_chat_memory`
    ADD COLUMN `agent_id` VARCHAR(64) NULL DEFAULT NULL AFTER `user_id`;

CREATE INDEX `idx_agent_id` ON `ai_chat_memory` (`agent_id`);
