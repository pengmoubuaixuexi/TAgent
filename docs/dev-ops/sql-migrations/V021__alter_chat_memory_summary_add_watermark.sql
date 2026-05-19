-- V021: 渐进式摘要水位线字段
-- summary_msg_count: 已纳入摘要的消息数（水位线）
-- detail_msg_count: 上次触发摘要时的总消息数（用于计算下次触发时机）

ALTER TABLE ai_chat_memory_summary
  ADD COLUMN `summary_msg_count` INT NOT NULL DEFAULT 0 COMMENT '已纳入摘要的消息数(水位线)',
  ADD COLUMN `detail_msg_count`  INT NOT NULL DEFAULT 0 COMMENT '上次触发时的总消息数';
