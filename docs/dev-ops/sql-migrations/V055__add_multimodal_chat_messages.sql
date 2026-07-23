-- V055: provider-neutral text + image ChatMemory and model capabilities.
-- Target: MySQL 8.0, database `ai-agent-station-study`.

ALTER TABLE ai_client_model
    ADD COLUMN capabilities_json JSON NULL
        COMMENT 'Model capabilities, e.g. TEXT/IMAGE input and URL/BASE64 sources'
        AFTER tier;

ALTER TABLE ai_chat_memory
    ADD COLUMN content_parts JSON NULL
        COMMENT 'Provider-neutral message parts; image parts reference attachment_id and never contain Base64'
        AFTER content,
    ADD COLUMN media_count INT NOT NULL DEFAULT 0
        COMMENT 'Number of image/media parts in this message'
        AFTER content_parts;

CREATE TABLE ai_chat_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    attachment_id VARCHAR(64) NOT NULL COMMENT 'Stable public attachment id',
    conversation_id VARCHAR(200) NOT NULL COMMENT 'Composite ChatMemory conversation id',
    user_id VARCHAR(64) NULL COMMENT 'Owner user id',
    run_id VARCHAR(128) NULL COMMENT 'Run that created the attachment',
    source_type VARCHAR(16) NOT NULL COMMENT 'URL or BASE64',
    source_url TEXT NULL COMMENT 'Original HTTP(S) URL for URL images',
    image_data LONGBLOB NULL COMMENT 'Decoded bytes for local BASE64 uploads',
    mime_type VARCHAR(128) NOT NULL COMMENT 'image/* MIME type',
    original_name VARCHAR(512) NULL COMMENT 'Original display name',
    file_size BIGINT NULL COMMENT 'Decoded byte size when known',
    sha256 CHAR(64) NULL COMMENT 'Content or URL SHA-256',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_attachment_id (attachment_id),
    KEY idx_chat_attachment_conversation (conversation_id),
    KEY idx_chat_attachment_user (user_id),
    KEY idx_chat_attachment_run (run_id),
    KEY idx_chat_attachment_sha256 (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Provider-neutral image attachments referenced by ChatMemory';

-- Existing configurations are conservative by default: null/legacy means text-only.
UPDATE ai_client_model
SET capabilities_json = JSON_OBJECT(
        'inputModalities', JSON_ARRAY('TEXT'),
        'imageSources', JSON_ARRAY()
    )
WHERE capabilities_json IS NULL;

-- Add an opt-in MiMo vision-capable model using the same API configuration as
-- the existing model 1000. Existing clients are intentionally not rewired.
INSERT INTO ai_client_model (
    model_id, api_id, model_usage, model_name, model_type,
    status, create_time, update_time, tier, capabilities_json
)
SELECT
    'mimo-v2.5-vision', api_id, '多模态图片理解', 'mimo-v2.5', model_type,
    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'large',
    JSON_OBJECT(
        'inputModalities', JSON_ARRAY('TEXT', 'IMAGE'),
        'imageSources', JSON_ARRAY('URL', 'BASE64')
    )
FROM ai_client_model
WHERE model_id = '1000'
  AND NOT EXISTS (
      SELECT 1 FROM ai_client_model WHERE model_id = 'mimo-v2.5-vision'
  )
LIMIT 1;

-- Make the default generic Fixed Agent (8011 -> client 48376249) immediately
-- usable for image questions without changing Auto/Flow or vertical Agents.
UPDATE ai_client_model
SET model_name = 'mimo-v2.5',
    model_usage = '通用问答（含图片理解）',
    capabilities_json = JSON_OBJECT(
        'inputModalities', JSON_ARRAY('TEXT', 'IMAGE'),
        'imageSources', JSON_ARRAY('URL', 'BASE64')
    )
WHERE model_id = '48376249_m'
  AND model_name = 'mimo-v2.5-pro';
