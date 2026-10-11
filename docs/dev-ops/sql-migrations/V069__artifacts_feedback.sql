-- Personal outcomes keep their originating run/Agent version and immutable revisions.
CREATE TABLE ai_run_artifact (
  artifact_id VARCHAR(64) NOT NULL PRIMARY KEY,
  owner_user_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(128) NOT NULL,
  agent_id VARCHAR(64) NULL,
  agent_version INT NULL,
  title VARCHAR(160) NOT NULL,
  format VARCHAR(16) NOT NULL,
  current_revision INT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_artifact_owner_run (owner_user_id,run_id,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_run_artifact_revision (
  artifact_id VARCHAR(64) NOT NULL,
  revision INT NOT NULL,
  title VARCHAR(160) NOT NULL,
  format VARCHAR(16) NOT NULL,
  content_json JSON NOT NULL,
  content_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (artifact_id,revision),
  CONSTRAINT fk_artifact_revision_head FOREIGN KEY (artifact_id) REFERENCES ai_run_artifact(artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_run_feedback (
  owner_user_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(128) NOT NULL,
  agent_id VARCHAR(64) NULL,
  agent_version INT NULL,
  revision INT NOT NULL,
  rating VARCHAR(16) NOT NULL,
  comment_text TEXT NOT NULL,
  corrected_answer MEDIUMTEXT NOT NULL,
  eval_dataset_id VARCHAR(64) NULL,
  eval_case_id VARCHAR(64) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (owner_user_id,run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
