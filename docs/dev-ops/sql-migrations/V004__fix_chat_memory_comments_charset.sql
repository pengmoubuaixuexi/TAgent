-- ============================================================================
-- 修复 V002 / V003 列注释的字符集（执行 V002/V003 时连接 charset 是 latin1，
-- 中文 COMMENT 按错误编码写入了 mojibake）。
-- ----------------------------------------------------------------------------
-- 必须用 utf8mb4 连接执行：
--   docker exec -i mysql mysql --default-character-set=utf8mb4 -u root -p123456 ai-agent-station-study < V004__...sql
-- ----------------------------------------------------------------------------
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- ai_chat_memory：5 个字段
ALTER TABLE `ai_chat_memory`
    MODIFY COLUMN `id`              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    MODIFY COLUMN `conversation_id` VARCHAR(64)  NOT NULL                 COMMENT '对话 ID，等价于业务 sessionId',
    MODIFY COLUMN `message_type`    VARCHAR(16)  NOT NULL                 COMMENT '消息类型：USER / ASSISTANT / SYSTEM',
    MODIFY COLUMN `content`         LONGTEXT     NOT NULL                 COMMENT '消息内容（不限长，旧消息走滚动摘要压缩）',
    MODIFY COLUMN `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ai_chat_memory_summary：7 个字段
ALTER TABLE `ai_chat_memory_summary`
    MODIFY COLUMN `id`               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    MODIFY COLUMN `conversation_id`  VARCHAR(64)  NOT NULL                 COMMENT '对话 ID（== sessionId）',
    MODIFY COLUMN `summary`          LONGTEXT     NOT NULL                 COMMENT '摘要内容；下次读取时作为 SystemMessage 注入',
    MODIFY COLUMN `last_message_id`  BIGINT       NULL                     COMMENT '摘要覆盖到的最新 ai_chat_memory.id；增量摘要用',
    MODIFY COLUMN `version`          INT          NOT NULL DEFAULT 1       COMMENT '摘要版本号，每次刷新 +1',
    MODIFY COLUMN `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- 顺手把 ai_client_model.tier 注释也修一下（如果之前 V001 也是 latin1 跑的）
ALTER TABLE `ai_client_model`
    MODIFY COLUMN `tier` VARCHAR(16) NOT NULL DEFAULT 'medium'
    COMMENT '模型档位 small/medium/large；P0.1.3 ModelRouter 按 step 难度选模型';
