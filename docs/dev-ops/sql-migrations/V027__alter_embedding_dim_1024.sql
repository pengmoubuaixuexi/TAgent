-- V027: Rebuild all PgVector tables with VECTOR(1024) for BAAI/bge-large-zh-v1.5
-- Previous: text-embedding-3-small (1536-dim) then Qwen/Qwen3-Embedding-8B (4096-dim)
-- Now: BAAI/bge-large-zh-v1.5 (1024-dim, Chinese-optimized, supports ivfflat index)
--
-- Note: ivfflat index max 2000 dimensions; 1024 is well within limit.

-- 1. RAG knowledge vector store
DROP TABLE IF EXISTS vector_store CASCADE;
CREATE TABLE vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1024)
);
CREATE INDEX idx_vector_store_embedding ON vector_store USING ivfflat (embedding vector_cosine_ops) WITH (lists='10');

-- 2. Long-term memory vector store
DROP TABLE IF EXISTS ltm_memory_store CASCADE;
CREATE TABLE ltm_memory_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1024)
);
CREATE INDEX idx_ltm_memory_embedding ON ltm_memory_store USING ivfflat (embedding vector_cosine_ops) WITH (lists='10');

-- 3. Semantic cache
DROP TABLE IF EXISTS semantic_cache CASCADE;
CREATE TABLE semantic_cache (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1024),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
    last_accessed TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
    user_id VARCHAR(64)
);
CREATE INDEX idx_semantic_cache_embedding ON semantic_cache USING ivfflat (embedding vector_cosine_ops) WITH (lists='10');
