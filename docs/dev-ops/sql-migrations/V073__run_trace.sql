-- V073: complete run traces (every model request including failed ones, tool calls with full results, key run events).
-- Target: MySQL 8.0, database `ai-agent-station-study`. Replaces ai_event_log as the source of run diagnostics;
-- ai_event_log stops being written (agent.event-log.enabled=false) and is kept until the owner backs it up and drops it.

CREATE TABLE IF NOT EXISTS ai_run_trace (
  run_id VARCHAR(128) NOT NULL,
  seq INT NOT NULL COMMENT 'Order within the run, from 1',
  owner_user_id VARCHAR(128) NOT NULL,
  kind VARCHAR(8) NOT NULL COMMENT 'MODEL, TOOL or EVENT',
  name VARCHAR(160) NULL COMMENT 'Model actually used, tool name or event type',
  step VARCHAR(160) NULL COMMENT 'Step or node id, without the retry suffix',
  parent_seq INT NULL COMMENT 'For a platform tool: the model request that asked for it',
  status VARCHAR(32) NULL COMMENT 'OK, ERROR, CANCELLED for models; success, error, blocked, approval_* for tools',
  api_id VARCHAR(64) NULL COMMENT 'Model connection that served the request',
  input_tokens BIGINT NULL,
  output_tokens BIGINT NULL,
  started_at TIMESTAMP(3) NOT NULL,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  payload MEDIUMBLOB NOT NULL COMMENT 'gzip JSON. A model request stores only the messages added to its base request',
  payload_bytes INT NOT NULL COMMENT 'Uncompressed size',
  PRIMARY KEY (run_id, seq),
  KEY idx_run_trace_owner (owner_user_id, started_at),
  KEY idx_run_trace_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_run_trace_blob (
  run_id VARCHAR(128) NOT NULL,
  hash CHAR(64) NOT NULL COMMENT 'sha256 of the text',
  content LONGBLOB NOT NULL COMMENT 'gzip text: system prompts, tool definitions, results over 16K characters',
  bytes INT NOT NULL COMMENT 'Uncompressed size',
  PRIMARY KEY (run_id, hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
