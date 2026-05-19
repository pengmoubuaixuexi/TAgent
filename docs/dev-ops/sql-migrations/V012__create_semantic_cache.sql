-- V012: Semantic Cache table for PgVector (P2.4 13.1)
-- Stores query embeddings + responses for similarity-based cache lookup.
-- Cosine similarity > 0.95 → direct return without LLM call.
-- Table managed in PostgreSQL (pgvector), not MySQL.
-- PgVectorStore auto-creates the table; this script is for manual setup / reference.
CREATE TABLE IF NOT EXISTS public.semantic_cache (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,           -- original user query
    metadata JSONB,                  -- {"response": "...", "model": "...", "hit_count": 0}
    embedding VECTOR(1024),          -- BAAI/bge-large-zh-v1.5
    created_at TIMESTAMP DEFAULT NOW(),
    last_accessed TIMESTAMP DEFAULT NOW()
);

-- Index for cosine similarity search (pgvector IVF flat for <100k rows)
CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding
    ON public.semantic_cache USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 10);
