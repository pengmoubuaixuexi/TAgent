-- Apply after V064. Select the intended database in the migration runner.
-- Platform reservations are durable and serialized per user. Unknown usage is never recorded as zero.
CREATE TABLE IF NOT EXISTS ai_model_usage_account (
 user_id VARCHAR(64) NOT NULL PRIMARY KEY,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_usage_day (
 user_id VARCHAR(64) NOT NULL,
 usage_date DATE NOT NULL COMMENT 'UTC day of reservation',
 platform_calls BIGINT NOT NULL DEFAULT 0,
 platform_reserved_tokens BIGINT NOT NULL DEFAULT 0,
 confirmed_tokens BIGINT NOT NULL DEFAULT 0,
 unknown_calls BIGINT NOT NULL DEFAULT 0,
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_usage_run (
 run_id VARCHAR(128) NOT NULL PRIMARY KEY,
 user_id VARCHAR(64) NOT NULL,
 billing_source VARCHAR(16) NOT NULL,
 state VARCHAR(24) NOT NULL,
 deadline_at DATETIME(3) NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_model_usage_run_owner(user_id,state,deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_usage_attempt (
 attempt_id VARCHAR(64) NOT NULL PRIMARY KEY,
 run_id VARCHAR(128) NOT NULL,
 user_id VARCHAR(64) NOT NULL,
 api_id VARCHAR(64) NULL,
 provider_host VARCHAR(255) NOT NULL,
 model_name VARCHAR(255) NOT NULL,
 purpose VARCHAR(64) NOT NULL,
 billing_source VARCHAR(16) NOT NULL COMMENT 'PERSONAL, PLATFORM or SYSTEM',
 usage_date DATE NOT NULL,
 input_estimate BIGINT NOT NULL,
 output_limit BIGINT NOT NULL,
 reserved_tokens BIGINT NOT NULL,
 actual_tokens BIGINT NULL,
 usage_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 outcome VARCHAR(32) NOT NULL DEFAULT 'IN_FLIGHT',
 cost_amount DECIMAL(20,10) NULL COMMENT 'NULL until provider pricing and complete usage are known',
 cost_currency VARCHAR(8) NULL,
 pricing_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 settled_at DATETIME(3) NULL,
 KEY idx_model_usage_attempt_owner(user_id,create_time),
 KEY idx_model_usage_attempt_run(run_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
