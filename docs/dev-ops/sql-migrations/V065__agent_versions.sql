-- Immutable, credential-free configuration history and an independent draft.
CREATE TABLE ai_agent_version (
  agent_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  config_json JSON NOT NULL,
  config_hash CHAR(64) NOT NULL,
  note VARCHAR(200) NOT NULL DEFAULT '',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (agent_id, version),
  INDEX idx_agent_version_owner (owner_user_id, agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_agent_draft (
  agent_id VARCHAR(64) NOT NULL PRIMARY KEY,
  owner_user_id VARCHAR(64) NOT NULL,
  base_version INT NOT NULL,
  revision INT NOT NULL,
  config_json JSON NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_agent_draft_owner (owner_user_id, agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ai_agent_version(agent_id,version,owner_user_id,config_json,config_hash,note)
SELECT agent_id,workspace_version,owner_user_id,workspace_config,
       SHA2(CAST(workspace_config AS CHAR),256),'Imported existing configuration'
FROM ai_agent WHERE workspace_config IS NOT NULL AND source_agent_id IS NULL;
