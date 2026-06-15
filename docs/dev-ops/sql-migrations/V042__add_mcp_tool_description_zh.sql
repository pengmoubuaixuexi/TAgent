-- V042: Add Chinese display description for MCP tool catalog.
-- Keep tool_description as the upstream raw MCP description.

ALTER TABLE `ai_mcp_tool_catalog`
    ADD COLUMN IF NOT EXISTS `tool_description_zh` TEXT DEFAULT NULL COMMENT 'Chinese display description for routing/UI'
    AFTER `tool_description`;
