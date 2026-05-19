-- V030: 给 GitHub MCP 加 HTTPS_PROXY，解决国内 api.github.com 30s 超时问题
-- 仅修改 9008 github-mcp，不动其他 MCP（calendar 没测过、其他都是国内可达）
--
-- 安全性说明：
--   - HTTPS_PROXY 仅在 npx 子进程 env 内生效（ProcessBuilder.environment() 显式 set）
--   - 不影响 Java JVM 主进程（LLM 调用走 RestTemplate，不读子进程 env）
--   - 不影响其他国内 MCP（每个 MCP 进程独立 env，不传 HTTPS_PROXY）
--
-- 运行前提：
--   - clash verge 在跑且监听 7897
--   - clash 规则模式下 api.github.com 走代理（默认行为）
--   - 测试时 Java 程序所在 shell 没开系统级代理（Windows 设置 → 网络 → 代理 → 关）
--
-- 验证代理可达：
--   curl -x http://127.0.0.1:7897 -I https://api.github.com -m 5
--   期望返回 HTTP/2 200 或 301/302（不是 timeout / connection refused）
--
-- 撤回方法：执行 docs/dev-ops/sql-migrations/V031__revert_https_proxy_from_github_calendar_mcp.sql
SET NAMES utf8mb4;
USE `ai-agent-station-study`;

UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"github-mcp":{"command":"cmd","args":["/c","npx","-y","@modelcontextprotocol/server-github"],"env":{"GITHUB_PERSONAL_ACCESS_TOKEN":"${GITHUB_PERSONAL_ACCESS_TOKEN}","HTTPS_PROXY":"http://127.0.0.1:7897","HTTP_PROXY":"http://127.0.0.1:7897"}}}'
WHERE mcp_id='9008';

-- 验证（执行后看输出，env 字段里应该出现 HTTPS_PROXY）
SELECT mcp_id, mcp_name, transport_config FROM ai_client_tool_mcp WHERE mcp_id='9008';
