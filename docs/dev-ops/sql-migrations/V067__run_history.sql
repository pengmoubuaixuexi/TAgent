-- Select the target database before running. Runtime history is independent of Redis TTL.
CREATE TABLE IF NOT EXISTS ai_run_history (
  run_id VARCHAR(128) NOT NULL PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(255) NOT NULL,
  agent_id VARCHAR(128) NULL,
  agent_version INT NULL,
  strategy VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL,
  question MEDIUMTEXT NOT NULL,
  answer MEDIUMTEXT NULL,
  events_json MEDIUMTEXT NULL,
  events_truncated TINYINT NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  instance_id VARCHAR(64) NOT NULL,
  heartbeat_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_run_history_owner (user_id, created_at),
  KEY idx_run_history_heartbeat (status, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
