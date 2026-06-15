-- V041: MCP tool catalog for dynamic tool discovery.
-- This project keeps SQL migrations as manual/idempotent scripts.

CREATE TABLE IF NOT EXISTS `ai_mcp_tool_catalog` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    `mcp_id` VARCHAR(64) NOT NULL COMMENT 'MCP id from ai_client_tool_mcp',
    `mcp_name` VARCHAR(128) DEFAULT NULL COMMENT 'MCP display name',
    `tool_name` VARCHAR(255) NOT NULL COMMENT 'MCP tool name',
    `tool_description` TEXT DEFAULT NULL COMMENT 'Tool description exposed to LLM',
    `tool_description_zh` TEXT DEFAULT NULL COMMENT 'Chinese display description for routing/UI',
    `input_schema_json` MEDIUMTEXT DEFAULT NULL COMMENT 'Tool input JSON schema',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1 enabled, 0 disabled',
    `last_seen_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'last catalog refresh time',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_tool` (`mcp_id`, `tool_name`),
    KEY `idx_tool_name` (`tool_name`),
    KEY `idx_mcp_id` (`mcp_id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP tool catalog for dynamic tool discovery';
