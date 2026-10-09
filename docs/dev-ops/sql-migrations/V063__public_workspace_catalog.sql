-- Apply once after V062. Back up MySQL before the DDL.
-- No new tables. Publication grants use, never credential access.
SET NAMES utf8mb4;
ALTER TABLE ai_client_tool_mcp ADD COLUMN is_public TINYINT NOT NULL DEFAULT 0,
  ADD INDEX idx_mcp_public (is_public,status);

-- Explicitly reviewed general-purpose services. File/Git/ops/account actions stay private.
UPDATE ai_client_tool_mcp m JOIN admin_user u ON u.user_id=m.owner_user_id AND u.role='ADMIN'
SET m.is_public=1
WHERE m.source_mcp_id IS NULL AND m.status=1
  AND m.mcp_id IN ('5005','5006','9003','9004','9005','9007','9009','9012','9013','9016','9017');

-- Existing administrator agents become public; users' personal agents are untouched.
UPDATE ai_agent a JOIN admin_user u ON u.user_id=a.owner_user_id AND u.role='ADMIN'
SET a.is_public=1
WHERE a.source_agent_id IS NULL AND a.status=1 AND a.archived=0;

UPDATE ai_client_model m JOIN ai_client_config c
  ON c.target_type='model' AND c.target_id=m.model_id AND c.source_type='client' AND c.status=1
JOIN ai_agent_flow_config f ON f.client_id=c.source_id AND f.status=1
JOIN ai_agent a ON a.agent_id=f.agent_id
SET m.platform_enabled=1 WHERE a.is_public=1 AND a.status=1 AND a.archived=0 AND m.status=1;

-- Sharing can be withdrawn without deleting any data:
-- UPDATE ai_client_tool_mcp SET is_public=0 WHERE mcp_id='...';
-- UPDATE ai_agent SET is_public=0 WHERE agent_id='...';
