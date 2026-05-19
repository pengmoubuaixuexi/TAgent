-- V033: 给 9006 finance-mcp 加 HTTPS_PROXY，跟 V030 GitHub / V032 Calendar 完全同款
-- 原因：stockquotes-mcp 调 query2.finance.yahoo.com，Yahoo 自 2021-11-01 阻断中国大陆 IP
--      日志频繁出现 `Failed to get crumb, status 403, statusText: Forbidden`
--
-- 安全性说明（与 V030/V032 一致）：
--   - HTTPS_PROXY 仅在 npx 子进程 env 内生效（mcp-stdio-wrapper.js → spawn 默认透传 env）
--   - 不影响 Java JVM 主进程（LLM 网关走 RestTemplate，不读子进程 env）
--   - 不影响其他 MCP（每个进程独立 env）
--
-- 运行前提：
--   - clash verge 在跑且监听 7897
--   - clash 规则模式下 query2.finance.yahoo.com / query1.finance.yahoo.com 走代理
--   - LLM 网关域名 xiaomimimo.com 走 DIRECT（避免 V030 当时劫持 LLM 链路的坑）
--   - Windows 系统代理设置 → 关
--
-- 验证代理可达：
--   curl -x http://127.0.0.1:7897 -I https://query2.finance.yahoo.com/v1/finance/search?q=SPY -m 5
--   期望 HTTP/2 200（不是 timeout / 403 / connection refused）
--
-- 撤回方法：把 transport_config 改回 V028b 的版本（去掉 env 字段）

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"finance-mcp":{"command":"cmd","args":["/c","node","docs/dev-ops/mcp-wrappers/mcp-stdio-wrapper.js","npx","stockquotes-mcp","--transport","stdio"],"env":{"HTTPS_PROXY":"http://127.0.0.1:7897","HTTP_PROXY":"http://127.0.0.1:7897"}}}'
WHERE mcp_id='9006';

-- 验证（执行后看输出，env 里应该出现 HTTPS_PROXY）
SELECT mcp_id, mcp_name, transport_config FROM ai_client_tool_mcp WHERE mcp_id='9006';
