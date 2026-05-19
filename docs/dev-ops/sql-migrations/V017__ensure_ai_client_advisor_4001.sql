-- V017: ensure ai_client_advisor advisor_id=4001 exists.
-- Background: ai_client_config rows for clients 3101/3102/3103/3104 (auto agent steps)
-- all reference advisor_id=4001 (target_id 4001), but this row is absent in fresh DBs.
-- Without it, AiClientAdvisorNode throws NoSuchBeanDefinitionException during armory assembly,
-- which then propagates as "No bean named 'ai_client_3101' available" from Step1AnalyzerNode.
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

INSERT IGNORE INTO ai_client_advisor (advisor_id, advisor_name, advisor_type, order_num, ext_param, status)
VALUES ('4001', '默认 RAG 回答增强', 'RagAnswer', 0, '{"topK":"4"}', 1);
