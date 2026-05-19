-- V031: 回滚 V030 给 9008/9001 加的 HTTPS_PROXY，恢复 V028b 的原始 transport_config
-- 原因：clash 代理把 LLM 网关流量也劫持了，影响主链路
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- 9008 github-mcp 回滚到 V028b
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"github-mcp":{"command":"cmd","args":["/c","npx","-y","@modelcontextprotocol/server-github"],"env":{"GITHUB_PERSONAL_ACCESS_TOKEN":"${GITHUB_PERSONAL_ACCESS_TOKEN}"}}}'
WHERE mcp_id='9008';

-- 9001 calendar-mcp 回滚到 V028b
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"calendar-mcp":{"command":"cmd","args":["/c","npx","@cocal/google-calendar-mcp"],"env":{"GOOGLE_OAUTH_CREDENTIALS":"${GOOGLE_OAUTH_CREDENTIALS_JSON}"}}}'
WHERE mcp_id='9001';

-- 验证回滚结果（执行后看输出，env 里不应再有 HTTPS_PROXY）
SELECT mcp_id, mcp_name, transport_config FROM ai_client_tool_mcp WHERE mcp_id IN ('9008','9001');
