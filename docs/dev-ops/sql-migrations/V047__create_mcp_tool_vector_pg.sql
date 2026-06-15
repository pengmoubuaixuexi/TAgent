-- V047 (2026-06-05): 动态补工具的语义匹配——把工具向量存进 PgVector(Postgres)，复用现有 pgvector 基建。
--
-- 背景：纯词法 BM25 在"同义词错位(联网 vs 网络) + 多个'搜索X'工具"下结构性分不开。改用 embedding 语义匹配。
-- 向量存 PgVector(而非内存)的好处：刷新目录时 embed 一次持久化，之后只查——不必每次 app 启动/每次跑测试都把
-- 200+ 个工具重新 embed 一遍；且复用 RAG/LTM/semantic_cache 同一套 pgVectorJdbcTemplate + VECTOR(1024) 模式。
--
-- 维度 1024 = BAAI/bge-large-zh-v1.5（与 vector_store / ltm_memory_store / semantic_cache 一致，见 V027）。
-- ⚠️ 这是 Postgres(ai-rag-knowledge @127.0.0.1:15432) 的迁移，不是 MySQL。

CREATE EXTENSION IF NOT EXISTS vector;

DROP TABLE IF EXISTS mcp_tool_vector CASCADE;

CREATE TABLE mcp_tool_vector (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mcp_id          VARCHAR(64)  NOT NULL,
    tool_name       VARCHAR(255) NOT NULL,
    mcp_name        VARCHAR(128),
    description_zh  TEXT,
    intent_zh       TEXT,
    content         TEXT NOT NULL,          -- 实际拿去 embed 的文本(zh + intent + 工具名 + mcp 名)
    embedding       VECTOR(1024),
    updated_at      TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
    UNIQUE (mcp_id, tool_name)
);

CREATE INDEX idx_mcp_tool_vector_tool_name ON mcp_tool_vector (tool_name);

-- ⚠️ 不建 ivfflat 近似索引：当前工具量小(~200)，ivfflat(lists=10,probes=1) 只扫 1/10 的行、会漏掉真正最近邻，
--    伤召回；顺序扫描 200 个向量是精确且微秒级的。等工具量上千再加：
--    CREATE INDEX idx_mcp_tool_vector_embedding ON mcp_tool_vector USING ivfflat (embedding vector_cosine_ops) WITH (lists='N');
