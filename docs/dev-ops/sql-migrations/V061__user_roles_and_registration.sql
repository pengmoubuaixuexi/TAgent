-- Run once before starting the authentication-enabled application.
-- Abort and resolve duplicate usernames before applying this migration.
-- SELECT username, COUNT(*) FROM admin_user GROUP BY username HAVING COUNT(*) > 1;
ALTER TABLE admin_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE admin_user ADD UNIQUE KEY uk_admin_user_username (username);
-- Promote only the existing owner account; never create an admin with a default password.
UPDATE admin_user SET role = 'ADMIN' WHERE username = 'admin';

CREATE TABLE auth_conversation_owner (
    conversation_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_owner_user (user_id)
);
-- Ambiguous or anonymous legacy conversations are deliberately inaccessible.
INSERT INTO auth_conversation_owner(conversation_id, user_id)
SELECT conversation_id,
       CASE WHEN COUNT(DISTINCT NULLIF(user_id,'')) = 1 THEN MAX(user_id) ELSE '__legacy_unowned__' END
FROM ai_chat_memory GROUP BY conversation_id;
-- Runtime APIs use raw session IDs while ChatMemory stores userId:sessionId.
INSERT IGNORE INTO auth_conversation_owner(conversation_id, user_id)
SELECT SUBSTRING_INDEX(conversation_id, ':', -1),
       CASE WHEN COUNT(DISTINCT NULLIF(user_id,'')) = 1 THEN MAX(user_id) ELSE '__legacy_unowned__' END
FROM ai_chat_memory GROUP BY SUBSTRING_INDEX(conversation_id, ':', -1);
