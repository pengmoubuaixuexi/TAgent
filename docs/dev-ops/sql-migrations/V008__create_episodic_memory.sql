-- ============================================================================
-- P2.1 Episodic Memory：跨会话摘要记忆
-- ----------------------------------------------------------------------------
-- 目的：每次会话结束时 LLM 生成 1-2 句"这次聊了什么"摘要，存到用户维度。
--       新会话开场时注入"上次我们聊过 X"帮助模型理解用户历史对话上下文。
-- ----------------------------------------------------------------------------
-- 回滚：DROP TABLE ai_episodic_memory;
-- ============================================================================
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_episodic_memory` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    `user_id`        VARCHAR(64)  NOT NULL                 COMMENT '用户维度隔离',
    `tenant_id`      VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户隔离',
    `session_id`     VARCHAR(200) NOT NULL                 COMMENT '来源对话 sessionId',
    `topic`          VARCHAR(128) NULL                     COMMENT '主题/意图（可选）',
    `summary`        VARCHAR(512) NOT NULL                 COMMENT '1-2 句会话摘要',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at` DESC),
    KEY `idx_tenant_user` (`tenant_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='跨会话摘要记忆（P2.1 Episodic Memory）';
