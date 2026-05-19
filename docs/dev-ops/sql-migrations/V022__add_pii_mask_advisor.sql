-- V022: Add PiiMask advisor to ai_client_advisor (disabled by default, not linked in ai_client_config).
-- PII masking is now opt-in: link this advisor to a client via ai_client_config when needed.
-- order_num=-60 ensures it runs before PromptInjectionDefense (order=-50).
SET NAMES utf8mb4;

INSERT IGNORE INTO ai_client_advisor (advisor_id, advisor_name, advisor_type, order_num, ext_param, status)
VALUES ('4005', 'PII脱敏', 'PiiMask', -60, NULL, 1);
