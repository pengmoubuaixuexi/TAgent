-- ============================================================================
-- P2.5 14.5 Audit Log：LLM 调用审计表
-- ----------------------------------------------------------------------------
-- 记录每次 LLM 调用的输入摘要 / 输出摘要 / token / cost / 状态，
-- 用于合规、成本分析、异常排查。
-- ============================================================================
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_llm_audit_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    `trace_id`        VARCHAR(64)  NULL                     COMMENT '分布式追踪 ID',
    `session_id`      VARCHAR(200) NULL                     COMMENT '会话 ID',
    `user_id`         VARCHAR(64)  NULL                     COMMENT '用户 ID',
    `tenant_id`       VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `step_name`       VARCHAR(64)  NOT NULL                 COMMENT '调用来源（step1/step2/...）',
    `model`           VARCHAR(64)  NOT NULL                 COMMENT '模型名',
    `prompt_hash`     VARCHAR(64)  NULL                     COMMENT 'prompt SHA-256（去重/审计用）',
    `prompt_snippet`  VARCHAR(500) NULL                     COMMENT '输入摘要（前 500 字符）',
    `output_snippet`  VARCHAR(500) NULL                     COMMENT '输出摘要（前 500 字符）',
    `prompt_tokens`   INT          NOT NULL DEFAULT 0       COMMENT '输入 token',
    `completion_tokens` INT        NOT NULL DEFAULT 0       COMMENT '输出 token',
    `cost_usd`        DECIMAL(10,6) NOT NULL DEFAULT 0.000000 COMMENT '成本（USD）',
    `latency_ms`      BIGINT       NOT NULL DEFAULT 0       COMMENT '延迟（毫秒）',
    `success`         TINYINT(1)   NOT NULL DEFAULT 1       COMMENT '是否成功',
    `error_msg`       VARCHAR(500) NULL                     COMMENT '错误信息',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session` (`session_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_created` (`created_at`),
    KEY `idx_step_model` (`step_name`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='LLM 调用审计日志（P2.5 14.5）';
