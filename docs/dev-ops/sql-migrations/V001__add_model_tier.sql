-- ============================================================================
-- P0.1.3 Model Router：给 ai_client_model 表加 tier 字段
-- ----------------------------------------------------------------------------
-- 目的：每个模型贴一个"档位"标签，让 step 节点按难度选模型（Haiku 干分类、
--       Sonnet 干规划、Opus 干深度推理），以省 token 成本。
-- ----------------------------------------------------------------------------
-- 安全性：
--   - 加列默认 'medium'，老数据无影响
--   - 后续启用 ModelRouter 时再按下方建议 UPDATE 给具体模型贴标
-- 回滚：ALTER TABLE ai_client_model DROP COLUMN tier;
-- ============================================================================

USE `ai-agent-station-study`;

ALTER TABLE `ai_client_model`
    ADD COLUMN `tier` VARCHAR(16) NOT NULL DEFAULT 'medium'
    COMMENT '模型档位 small/medium/large；P0.1.3 ModelRouter 按 step 难度选模型';

-- 给已存在的常见模型贴默认档位（按当前主流模型能力梯度）
-- small：分类/解析/摘要等轻量任务
UPDATE `ai_client_model` SET `tier` = 'small'
    WHERE `model_name` LIKE '%haiku%'
       OR `model_name` LIKE '%mini%'
       OR `model_name` LIKE '%nano%'
       OR `model_name` LIKE '%embedding%'
       OR `model_name` LIKE 'gpt-3.5%';

-- large：深度推理 / 复杂规划
UPDATE `ai_client_model` SET `tier` = 'large'
    WHERE `model_name` LIKE '%opus%'
       OR `model_name` LIKE '%gpt-4-turbo%'
       OR `model_name` = 'gpt-4o';

-- 其余保持默认 medium（含 sonnet / 普通 4o-mini 之外的 4o 系等）
