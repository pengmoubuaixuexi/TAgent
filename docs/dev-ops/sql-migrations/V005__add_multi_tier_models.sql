-- ============================================================================
-- P0.1.3 配套：补充多档位模型，让 ModelRouter 真能选出 small/medium/large
-- ----------------------------------------------------------------------------
-- 复用现有 api_id=1001（同一个网关支持多家模型）。新模型 ID 从 7001 起，避开已用的
-- 1000/2000/2001/3001/4001/5001/6001。
-- ----------------------------------------------------------------------------
-- 必须用 utf8mb4 连接执行：
--   docker exec -i mysql mysql --default-character-set=utf8mb4 -u root -p123456 ai-agent-station-study < V005__...sql
-- ----------------------------------------------------------------------------
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- 用 INSERT IGNORE 让脚本可重复执行（model_id UNIQUE）
INSERT IGNORE INTO `ai_client_model`
    (model_id, api_id, model_name, model_type, model_usage, tier, status, create_time, update_time)
VALUES
    -- LARGE 档：深度推理 / 复杂规划
    ('7001', '1001', 'claude-opus-4-7',  'claude', '深度推理 / Reflexion', 'large',  1, NOW(), NOW()),
    ('7002', '1001', 'gpt-4o',           'openai', '通用大模型',           'large',  1, NOW(), NOW()),
    -- MEDIUM 档：常规对话 / QA
    ('7003', '1001', 'claude-sonnet-4-6','claude', '常规对话与执行',       'medium', 1, NOW(), NOW()),
    ('7004', '1001', 'gpt-4o-mini',      'openai', '常规对话 / 评审',      'medium', 1, NOW(), NOW()),
    -- SMALL 档：分类 / 解析 / 摘要
    ('7005', '1001', 'claude-haiku-4-5', 'claude', '意图分类 / Query 改写 / 摘要', 'small', 1, NOW(), NOW())
;
