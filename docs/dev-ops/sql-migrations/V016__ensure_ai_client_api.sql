-- ============================================================================
-- US-007: 确保 ai_client_api 表存在 api_id=1001 记录
-- ----------------------------------------------------------------------------
-- 背景：ai_client_model 表中所有模型 (1000/2000/2001/3001/4001/5001/6001/700X)
-- 都引用 api_id=1001，但该记录可能在运行库中缺失，导致 armory 装配链在
-- AiClientModelNode 处因 NoSuchBeanDefinitionException('ai_client_api_1001') 失败。
-- ----------------------------------------------------------------------------
-- 用 INSERT IGNORE 让脚本可重复执行（uk_api_id UNIQUE）
-- ============================================================================
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

INSERT IGNORE INTO `ai_client_api` (`id`, `api_id`, `base_url`, `api_key`, `completions_path`, `embeddings_path`, `status`, `create_time`, `update_time`)
VALUES
	(1,'1001','https://apis.itedus.cn','${OPENAI_API_KEY}','v1/chat/completions','v1/embeddings',1,'2025-06-14 12:33:22','2025-10-04 21:18:48');
