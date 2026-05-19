-- V032: 给 9001 calendar-mcp 加 HTTPS_PROXY，跟 V030 GitHub 代理完全同款做法
-- 原因：@cocal/google-calendar-mcp 调 https://www.googleapis.com/calendar/v3/...，国内裸网不通
--      8006 日程规划 agent 的 calendar 工具因此一直不可用（装配阶段虽能起 npx，但调 API 全 timeout）
--
-- 安全性说明（与 V030 一致）：
--   - HTTPS_PROXY 仅在 npx 子进程 env 内生效（ProcessBuilder.environment() 显式 set）
--   - 不影响 Java JVM 主进程（LLM 网关走 RestTemplate，不读子进程 env）
--   - 不影响其他 MCP（每个 MCP 进程独立 env）
--
-- 运行前提：
--   - clash verge 在跑且监听 7897
--   - clash 规则模式下 www.googleapis.com / accounts.google.com 走代理（默认行为）
--   - 测试时 LLM 网关域名（xiaomimimo.com 等）必须走 DIRECT，否则会复现之前 V030 把 LLM 链路也劫持的问题
--   - Java 程序所在 shell 没开系统级代理（Windows 设置 → 网络 → 代理 → 关）
--
-- 验证代理可达：
--   curl -x http://127.0.0.1:7897 -I https://www.googleapis.com/calendar/v3/users/me/calendarList -m 5
--   期望 HTTP/2 401（OAuth 缺失，但代理通了）；不应是 timeout / connection refused
--
-- 撤回方法：
--   UPDATE ai_client_tool_mcp SET transport_config='{...同 V031 calendar 段...}' WHERE mcp_id='9001';
--   （即去掉 env 里的 HTTPS_PROXY/HTTP_PROXY 两个字段）

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"calendar-mcp":{"command":"cmd","args":["/c","npx","@cocal/google-calendar-mcp"],"env":{"GOOGLE_OAUTH_CREDENTIALS":"${GOOGLE_OAUTH_CREDENTIALS_JSON}","HTTPS_PROXY":"http://127.0.0.1:7897","HTTP_PROXY":"http://127.0.0.1:7897"}}}'
WHERE mcp_id='9001';

-- 验证（执行后看输出，env 字段里应该出现 HTTPS_PROXY）
SELECT mcp_id, mcp_name, transport_config FROM ai_client_tool_mcp WHERE mcp_id='9001';
