-- Local v2 knowledge ingestion state. Back up MySQL before applying.
-- Source bytes live in the private application data volume, never in the static web root.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS ai_rag_ingestion_task (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL,
    rag_id VARCHAR(64) NOT NULL,
    knowledge_tag VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    source_path VARCHAR(255) NOT NULL,
    dedup_key CHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    stage VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    progress INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    lease_token VARCHAR(64) NULL,
    lease_until DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_rag_ingestion_dedup (dedup_key),
    KEY idx_rag_ingestion_owner (owner_user_id, updated_at),
    KEY idx_rag_ingestion_work (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
