-- V026: Fix Flow agent 2345 flow_config client_type to match FlowAgentExecuteStrategy expectations
-- Flow strategy expects: TOOL_MCP_CLIENT (step1), PLANNING_CLIENT (step2), EXECUTOR_CLIENT (step3)
-- Current (wrong):       TASK_ANALYZER_CLIENT (step1), PRECISION_EXECUTOR_CLIENT (step2), RESPONSE_ASSISTANT (step3)

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

UPDATE ai_agent_flow_config SET client_type = 'TOOL_MCP_CLIENT'        WHERE agent_id = '2345' AND sequence = 1;
UPDATE ai_agent_flow_config SET client_type = 'PLANNING_CLIENT'         WHERE agent_id = '2345' AND sequence = 2;
UPDATE ai_agent_flow_config SET client_type = 'EXECUTOR_CLIENT'         WHERE agent_id = '2345' AND sequence = 3;
