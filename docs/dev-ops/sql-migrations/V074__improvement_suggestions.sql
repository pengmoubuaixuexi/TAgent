-- V074: improvement suggestions from run reviews: diagnosis, proposed changes, draft, validation and the owner's decision.
-- Target: MySQL 8.0, database `ai-agent-station-study`. Validation runs a hidden candidate agent (ai_agent.channel='candidate').

CREATE TABLE IF NOT EXISTS ai_improvement_suggestion (
  suggestion_id VARCHAR(64) NOT NULL PRIMARY KEY,
  owner_user_id VARCHAR(128) NOT NULL,
  agent_id VARCHAR(64) NULL,
  agent_version INT NULL COMMENT 'Version the reviewed run used',
  source_kind VARCHAR(16) NOT NULL COMMENT 'RUN (run detail or feedback) or EVAL_CASE',
  source_run_id VARCHAR(128) NOT NULL,
  eval_run_id VARCHAR(64) NULL,
  eval_result_id VARCHAR(64) NULL,
  status VARCHAR(24) NOT NULL COMMENT 'ANALYZING, FAILED, EXTERNAL, NO_CHANGE, PENDING_VALIDATION, VALIDATING, VALIDATION_FAILED, VALIDATED, ADOPTED, REJECTED',
  diagnosis_json JSON NULL COMMENT 'External failure check, problem node, category, evidence trace nodes',
  suggestion_json JSON NULL COMMENT 'Prompt changes per node, and advice the owner applies by hand',
  draft_base_version INT NULL,
  draft_revision INT NULL,
  candidate_agent_id VARCHAR(64) NULL,
  validation_json JSON NULL COMMENT 'Validation dataset, baseline and candidate evaluation runs, comparison',
  error_message VARCHAR(1000) NULL,
  review_model VARCHAR(128) NULL,
  review_tokens BIGINT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_suggestion_owner (owner_user_id, created_at),
  KEY idx_suggestion_run (source_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
