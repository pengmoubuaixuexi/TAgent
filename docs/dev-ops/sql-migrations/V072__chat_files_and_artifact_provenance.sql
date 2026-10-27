-- V072: unified chat attachments (uploaded files and artifact references) and artifact revision provenance.
-- Target: MySQL 8.0, database `ai-agent-station-study`. Images keep using ai_chat_attachment (V055/V057).

CREATE TABLE IF NOT EXISTS ai_chat_file (
  attachment_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'Public id, f_ + UUID',
  owner_user_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(255) NULL COMMENT 'Bound when the message is sent; NULL while PENDING',
  conversation_id VARCHAR(320) NULL COMMENT 'tenant:user:session, same key as ai_chat_memory',
  run_id VARCHAR(128) NULL COMMENT 'Run that first received the attachment',
  kind VARCHAR(16) NOT NULL COMMENT 'UPLOAD or ARTIFACT',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING, ACTIVE or PURGED (metadata kept, content removed)',
  file_name VARCHAR(255) NOT NULL,
  mime_type VARCHAR(128) NOT NULL,
  file_kind VARCHAR(16) NOT NULL COMMENT 'TEXT, CODE, MARKDOWN, CSV, SHEET, PDF, DOCUMENT, SLIDES',
  file_size BIGINT NOT NULL DEFAULT 0,
  sha256 CHAR(64) NULL,
  storage_provider VARCHAR(16) NULL COMMENT 'OSS or LOCAL; NULL for artifact references',
  bucket_name VARCHAR(128) NULL,
  object_key VARCHAR(512) NULL COMMENT 'Permanent object key; shared by the same owner and sha256',
  artifact_id VARCHAR(64) NULL,
  artifact_revision INT NULL,
  parse_status VARCHAR(16) NOT NULL COMMENT 'PENDING, READY or FAILED',
  parse_error VARCHAR(500) NULL,
  unit VARCHAR(8) NULL COMMENT 'LINE, PAGE, SLIDE or ROW',
  unit_count INT NULL,
  text_chars BIGINT NULL,
  truncated TINYINT NOT NULL DEFAULT 0,
  structure_json JSON NULL COMMENT 'Sheets, columns and row counts; never the full text',
  preview_text TEXT NULL,
  inline_text MEDIUMTEXT NULL COMMENT 'Full extracted text when small enough to place in context',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  bound_at TIMESTAMP(3) NULL,
  last_used_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  purged_at TIMESTAMP(3) NULL,
  KEY idx_chat_file_owner (owner_user_id, status, created_at),
  KEY idx_chat_file_session (owner_user_id, session_id, status),
  KEY idx_chat_file_conversation (conversation_id),
  KEY idx_chat_file_run (run_id),
  KEY idx_chat_file_cleanup (kind, status, last_used_at),
  KEY idx_chat_file_content (owner_user_id, sha256),
  KEY idx_chat_file_artifact (owner_user_id, artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chat attachments: uploaded files and referenced artifacts';

-- One row per run that received an attachment (first send, retry, redo).
CREATE TABLE IF NOT EXISTS ai_chat_file_run (
  run_id VARCHAR(128) NOT NULL,
  attachment_id VARCHAR(64) NOT NULL,
  position INT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (run_id, attachment_id),
  KEY idx_chat_file_run_attachment (attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Where each artifact revision came from. Existing rows stay NULL (unknown).
ALTER TABLE ai_run_artifact_revision
  ADD COLUMN source_kind VARCHAR(16) NULL COMMENT 'USER (edited on the outcomes page) or AGENT (create_artifact)' AFTER content_hash,
  ADD COLUMN source_run_id VARCHAR(128) NULL COMMENT 'Run that produced this revision' AFTER source_kind,
  ADD COLUMN base_revision INT NULL COMMENT 'Revision this one was edited from' AFTER source_run_id,
  ADD COLUMN source_attachment_id VARCHAR(64) NULL COMMENT 'Artifact reference attachment the run was given' AFTER base_revision,
  ADD COLUMN source_call_hash CHAR(64) NULL COMMENT 'Replay key of the tool call that saved it' AFTER source_attachment_id,
  ADD KEY idx_artifact_revision_source (artifact_id, source_run_id);
