-- ============================================================================
-- P1.7 Long-Term Memory：用户级语义记忆
-- ----------------------------------------------------------------------------
-- 目的：把用户陈述（"我用 Java"、"喜欢简洁回答"、"邮箱是 xxx"）这种"应该跨会话记住的事实"
--       做成 embedding 写到 PgVector 库（vector_store_long_term_memory）；下次对话前按当前
--       query 召回 top-K 注入 system prompt。
-- ----------------------------------------------------------------------------
-- 元信息表 ai_long_term_memory（MySQL）：保留可读字段、TTL/衰减需要的统计、引用追溯。
-- 向量存储另起一张 PgVector 表（见 PostgreSQL 侧的 init.sql 注释），通过 metadata.memory_id
-- 反向关联到本表的 memory_id。
-- ----------------------------------------------------------------------------
-- 回滚：DROP TABLE ai_long_term_memory;
-- ============================================================================
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_long_term_memory` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    `memory_id`      VARCHAR(64)  NOT NULL                 COMMENT '记忆全局 ID（UUID），与 PgVector metadata 关联',
    `user_id`        VARCHAR(64)  NOT NULL                 COMMENT '用户维度隔离（暂用 sessionId 代理）',
    `tenant_id`      VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户隔离（多租户预留）',
    `topic`          VARCHAR(64)  NULL                     COMMENT '主题分类（preference / fact / decision / skill 等），冲突解决用',
    `content`        TEXT         NOT NULL                 COMMENT '原始陈述文本（被 embed 的内容）',
    `source`         VARCHAR(32)  NOT NULL DEFAULT 'auto'  COMMENT '来源：auto（LLM 自动提取）/ manual（用户显式声明）',
    `source_session` VARCHAR(64)  NULL                     COMMENT '来源对话 sessionId',
    `access_count`   INT          NOT NULL DEFAULT 0       COMMENT '被召回累计次数（衰减用）',
    `last_accessed`  DATETIME     NULL                     COMMENT '最近一次召回时间（衰减用）',
    `archived`       TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '是否归档（0=活跃 1=已归档）',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_id` (`memory_id`),
    KEY `idx_user_archived` (`user_id`, `archived`),
    KEY `idx_topic_user` (`topic`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户级语义记忆元数据（P1.7 Long-Term Memory）';

-- 注：向量数据存在 PgVector 库的 vector_store_long_term_memory 表
-- 建表 SQL（在 PgVector / PostgreSQL 库里执行，不在 MySQL 里）：
--
--   CREATE TABLE IF NOT EXISTS public.vector_store_long_term_memory (
--       id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--       content   TEXT NOT NULL,
--       metadata  JSONB,
--       embedding VECTOR(1024)
--   );
--   CREATE INDEX IF NOT EXISTS idx_ltm_metadata ON public.vector_store_long_term_memory USING gin (metadata);
