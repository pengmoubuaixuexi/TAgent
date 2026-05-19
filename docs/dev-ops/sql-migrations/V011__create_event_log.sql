-- V011: 可重放事件日志（P2.8 17.2）
-- 记录每步 LLM 调用 input/output，支持事后重放和调试
SET NAMES utf8mb4;
USE `ai-agent-station-study`;
CREATE TABLE IF NOT EXISTS ai_event_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(200)    NOT NULL COMMENT '会话ID（复合键）',
    step_name       VARCHAR(50)     NOT NULL COMMENT '步骤名称（Step1/Step2/Step3/Step4）',
    step_index      INT             NOT NULL DEFAULT 0 COMMENT 'Reflexion 重做序号（0=首次）',
    input_prompt    TEXT            NOT NULL COMMENT '发往 LLM 的完整 prompt',
    output_text     MEDIUMTEXT      NULL     COMMENT 'LLM 返回文本',
    model           VARCHAR(100)    NULL     COMMENT '使用的模型名称',
    prompt_tokens   INT             NULL     COMMENT 'prompt token 数',
    completion_tokens INT           NULL     COMMENT 'completion token 数',
    latency_ms      BIGINT          NULL     COMMENT '调用耗时（毫秒）',
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 步骤事件日志（可重放）';
