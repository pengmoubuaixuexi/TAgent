-- V015: Add user_id columns for multi-tenant isolation
-- MySQL
ALTER TABLE ai_chat_memory ADD COLUMN IF NOT EXISTS user_id VARCHAR(64) DEFAULT NULL COMMENT '用户ID' AFTER conversation_id;
ALTER TABLE ai_chat_memory_summary ADD COLUMN IF NOT EXISTS user_id VARCHAR(64) DEFAULT NULL COMMENT '用户ID' AFTER conversation_id;
ALTER TABLE ai_client_rag_order ADD COLUMN IF NOT EXISTS user_id VARCHAR(64) DEFAULT NULL COMMENT '用户ID' AFTER rag_id;
ALTER TABLE ai_parent_document ADD COLUMN IF NOT EXISTS user_id VARCHAR(64) DEFAULT NULL COMMENT '用户ID' AFTER knowledge_tag;
ALTER TABLE ai_event_log ADD COLUMN IF NOT EXISTS user_id VARCHAR(64) DEFAULT NULL COMMENT '用户ID' AFTER session_id;

-- PostgreSQL: semantic_cache needs user_id for cache isolation
-- Run against ai-rag-knowledge database:
ALTER TABLE public.semantic_cache ADD COLUMN IF NOT EXISTS user_id VARCHAR(64) DEFAULT NULL;
