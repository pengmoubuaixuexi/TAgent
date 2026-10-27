-- Model failover: one catalog entry per owner + connection + model name, and health per connection + model.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_catalog (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_scope VARCHAR(64) NOT NULL COMMENT 'PLATFORM，或个人连接主人的用户 ID；两边永不互借',
  api_id VARCHAR(64) NOT NULL COMMENT 'ai_client_api.api_id',
  model_name VARCHAR(64) NOT NULL COMMENT '请求体中的 model',
  tier VARCHAR(16) NOT NULL COMMENT 'small / medium / large',
  sort_order INT NOT NULL DEFAULT 100 COMMENT '同档位内的切换顺序，小的优先',
  capabilities_json JSON NULL COMMENT '同 ai_client_model.capabilities_json（inputModalities / imageSources）；toolCalling=false 表示不支持工具调用',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_model_catalog_target (owner_scope, api_id, model_name),
  INDEX idx_model_catalog_route (owner_scope, enabled, tier, sort_order),
  CONSTRAINT chk_model_catalog_tier CHECK (tier IN ('small', 'medium', 'large'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型清单：降级候选的唯一来源';

CREATE TABLE IF NOT EXISTS ai_model_health (
  api_id VARCHAR(64) NOT NULL,
  model_name VARCHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'HEALTHY' COMMENT 'HEALTHY / UNHEALTHY',
  consecutive_failures INT NOT NULL DEFAULT 0,
  last_failure_kind VARCHAR(32) NULL COMMENT 'TRANSIENT / RATE_LIMITED / MODEL_UNAVAILABLE / REQUEST_REJECTED',
  last_failure_status INT NULL,
  last_failure_message VARCHAR(255) NULL,
  last_failure_at TIMESTAMP(3) NULL,
  last_success_at TIMESTAMP(3) NULL,
  unhealthy_since TIMESTAMP(3) NULL,
  last_probe_at TIMESTAMP(3) NULL,
  last_notified_at TIMESTAMP(3) NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (api_id, model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型健康状态（按连接 + 模型名）';

-- Tier cleanup. 'l-large' was never recognised and has always been read as medium.
UPDATE ai_client_model SET tier = 'large' WHERE model_name = 'mimo-v2.6-pro' AND tier = 'l-large';
UPDATE ai_client_model SET tier = 'medium' WHERE model_name = 'mimo-v2.6-flash' AND tier IN ('l-large', 'large');
UPDATE ai_client_model SET tier = 'medium' WHERE tier IS NULL OR tier NOT IN ('small', 'medium', 'large');

-- Initial catalog: models published to users on platform connections, and every model on a personal
-- (BYOK) connection under its owner. Per-agent node policy inside capabilities_json is not copied.
INSERT IGNORE INTO ai_model_catalog (owner_scope, api_id, model_name, tier, capabilities_json)
SELECT IF(a.secret_ref IS NULL, 'PLATFORM', a.owner_user_id), m.api_id, m.model_name,
       ELT(MAX(FIELD(m.tier, 'small', 'medium', 'large')), 'small', 'medium', 'large'),
       JSON_OBJECT('inputModalities', COALESCE(ANY_VALUE(JSON_EXTRACT(m.capabilities_json, '$.inputModalities')), JSON_ARRAY('TEXT')),
                   'imageSources', COALESCE(ANY_VALUE(JSON_EXTRACT(m.capabilities_json, '$.imageSources')), JSON_ARRAY()))
FROM ai_client_model m
JOIN ai_client_api a ON a.api_id = m.api_id
WHERE m.status = 1 AND a.status = 1 AND (a.secret_ref IS NOT NULL OR m.platform_enabled = 1)
GROUP BY IF(a.secret_ref IS NULL, 'PLATFORM', a.owner_user_id), m.api_id, m.model_name;
