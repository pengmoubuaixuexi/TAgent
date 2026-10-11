-- Personal OpenAI-compatible connections. Requires V062 ownership columns.
-- MODEL_SECRET_MASTER_KEY must be a deployment-managed Base64-encoded 32-byte key.
-- Existing platform credentials remain compatible; new personal secrets are never stored in api_key.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_secret (
    secret_ref VARCHAR(64) NOT NULL PRIMARY KEY,
    api_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    ciphertext TEXT NOT NULL,
    credential_version INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    verified_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_secret_api (api_id),
    KEY idx_model_secret_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_user_model_policy (
    user_id VARCHAR(64) NOT NULL PRIMARY KEY,
    platform_auxiliaries_enabled TINYINT NOT NULL DEFAULT 0,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='ai_client_api' AND column_name='secret_ref');
SET @ddl = IF(@exists=0,'ALTER TABLE ai_client_api ADD COLUMN secret_ref VARCHAR(64) NULL AFTER api_key','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Rollback: revoke personal connections before removing these tables/column. Never export decrypted keys.
