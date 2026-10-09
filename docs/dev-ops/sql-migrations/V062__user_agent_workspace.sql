-- Apply once after V061, with the application stopped. Back up MySQL first.
-- Existing configuration remains owned by the existing administrator. A missing
-- administrator is deliberately assigned an inaccessible sentinel, never public.
SET NAMES utf8mb4;
SET @workspace_owner = COALESCE((SELECT user_id FROM admin_user
  WHERE username = 'admin' AND role = 'ADMIN' LIMIT 1), '__legacy_unowned__');

ALTER TABLE ai_agent
  MODIFY COLUMN agent_name VARCHAR(100) NOT NULL,
  MODIFY COLUMN description VARCHAR(1000) NULL,
  ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__',
  ADD COLUMN is_public TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN source_agent_id VARCHAR(64) NULL,
  ADD COLUMN workspace_config JSON NULL,
  ADD COLUMN workspace_version INT NOT NULL DEFAULT 1,
  ADD COLUMN archived TINYINT NOT NULL DEFAULT 0,
  ADD INDEX idx_agent_owner (owner_user_id, archived, status),
  ADD UNIQUE KEY uk_workspace_public_copy (owner_user_id, source_agent_id);
ALTER TABLE ai_client ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__',
  ADD INDEX idx_client_owner (owner_user_id);
ALTER TABLE ai_client_api ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__';
ALTER TABLE ai_client_model ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__',
  ADD COLUMN platform_enabled TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN source_model_id VARCHAR(64) NULL,
  ADD INDEX idx_model_owner (owner_user_id, platform_enabled);
ALTER TABLE ai_client_system_prompt ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__';
ALTER TABLE ai_client_advisor ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__';
ALTER TABLE ai_client_tool_mcp ADD COLUMN owner_user_id VARCHAR(64) NOT NULL DEFAULT '__legacy_unowned__',
  ADD COLUMN workspace_editable TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN workspace_version INT NOT NULL DEFAULT 1,
  ADD COLUMN source_mcp_id VARCHAR(64) NULL,
  ADD INDEX idx_mcp_owner (owner_user_id, status),
  ADD UNIQUE KEY uk_workspace_public_mcp (owner_user_id, source_mcp_id);

UPDATE ai_agent SET owner_user_id = @workspace_owner;
UPDATE ai_client SET owner_user_id = @workspace_owner;
UPDATE ai_client_api SET owner_user_id = @workspace_owner;
UPDATE ai_client_model SET owner_user_id = @workspace_owner;
UPDATE ai_client_system_prompt SET owner_user_id = @workspace_owner;
UPDATE ai_client_advisor SET owner_user_id = @workspace_owner;
UPDATE ai_client_tool_mcp SET owner_user_id = @workspace_owner;
-- Only the three explicitly selected general agents are published. Publication
-- grants use through an isolated runtime copy, never access to raw credentials.
UPDATE ai_agent SET is_public = 1 WHERE agent_id IN ('8011','8012','8013')
  AND owner_user_id <> '__legacy_unowned__';
-- Offer the models used by those three agents; no arbitrary private API export.
UPDATE ai_client_model m JOIN ai_client_config c
  ON c.target_type = 'model' AND c.target_id = m.model_id AND c.source_type = 'client'
  JOIN ai_agent_flow_config f ON f.client_id = c.source_id
  JOIN ai_agent a ON a.agent_id = f.agent_id
  SET m.platform_enabled = 1 WHERE a.is_public = 1 AND m.status = 1 AND c.status = 1;
