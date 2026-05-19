-- V018: enable agent 6 (Fixed strategy) and its flow_config row.
-- Background: agent 6 ("测试用Fixed版") was disabled (status=0) along with its
-- ai_agent_flow_config (id=17 → client_id=6101 DEFAULT). Without this row enabled,
-- the armory chain never registers ai_client_6101, and any Fixed-strategy test
-- against agentId=6 fails with NoSuchBeanDefinitionException.
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

UPDATE ai_agent SET status = 1 WHERE agent_id = '6' AND status = 0;
UPDATE ai_agent_flow_config SET status = 1 WHERE id = 17 AND status = 0;
