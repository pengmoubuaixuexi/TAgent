-- For a V062 schema whose data initialization did not complete.
-- Run this whole file in ONE MySQL connection, on ai-agent-station-study.
-- Stop the application while completing initialization. This file has no DDL.
-- Existing explicitly assigned owners are preserved, including personal agents.
SET NAMES utf8mb4;
START TRANSACTION;
SET @workspace_owner = (SELECT user_id FROM admin_user
  WHERE username = 'admin' AND role = 'ADMIN' AND status = 1 LIMIT 1);

UPDATE ai_agent SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;
UPDATE ai_client SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;
UPDATE ai_client_api SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;
UPDATE ai_client_model SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;
UPDATE ai_client_system_prompt SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;
UPDATE ai_client_advisor SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;
UPDATE ai_client_tool_mcp SET owner_user_id = @workspace_owner
  WHERE owner_user_id = '__legacy_unowned__' AND @workspace_owner IS NOT NULL;

UPDATE ai_agent SET is_public = 1
  WHERE agent_id IN ('8011','8012','8013') AND owner_user_id = @workspace_owner;
UPDATE ai_client_model m JOIN ai_client_config c
  ON c.target_type = 'model' AND c.target_id = m.model_id AND c.source_type = 'client'
  JOIN ai_agent_flow_config f ON f.client_id = c.source_id
  JOIN ai_agent a ON a.agent_id = f.agent_id
  SET m.platform_enabled = 1
  WHERE a.agent_id IN ('8011','8012','8013') AND a.is_public = 1
    AND a.owner_user_id = @workspace_owner AND m.owner_user_id = @workspace_owner
    AND m.status = 1 AND c.status = 1;
COMMIT;

SELECT DATABASE() AS current_database,
  IF(@workspace_owner IS NULL, 'STOP: no active admin account; no data changed', 'OK: legacy ownership initialized') AS result;
SELECT agent_id, owner_user_id, is_public, status FROM ai_agent
  WHERE agent_id IN ('8011','8012','8013');
SELECT COUNT(*) AS available_platform_models FROM ai_client_model m
  JOIN ai_client_api a ON a.api_id = m.api_id
  WHERE m.platform_enabled = 1 AND m.status = 1 AND a.status = 1;
