-- =============================================================================
-- 2026-07-03 新增：百度千帆 web-search MCP（Streamable HTTP transport）
-- =============================================================================
-- 依赖代码（已实现，需重启 app 生效）：
--   AiClientToolMcpVO.TransportConfigStreamableHttp{url, headers}
--   AgentRepository.parseMcpTransportConfig      → 'streamable-http' 解析分支
--   AiClientToolMcpNode.createMcpSyncClient       → case "streamable-http"
--       （WebClientStreamableHttpTransport + WebClient.defaultHeader 注入认证头）
--
-- 说明：
--   1) 按“新增一个”写，不改动/删除任何原有 MCP 记录。
--   2) 用 header 认证（Authorization: Bearer），不在 URL 带 key——
--      streamable-http 的 URL query 参数会被 strip（Spring AI 已知问题 #6505），必须走 header。
--   3) 把 transport_config 里的 "Bearer xxxxx" 换成你真实的千帆 API Key。
--   4) mcp_id='5009' 若与现网已有记录冲突，改成未占用的值。
--   5) 本脚本只新增 MCP 定义。要让某个 Agent 真正用上，还需在 ai_client_config
--      把此 mcp_id 绑到对应 client（原 baidu-search 绑在哪个 client 请照此改绑/新增）。
-- =============================================================================

INSERT INTO `ai_client_tool_mcp`
    (`mcp_id`, `mcp_name`, `transport_type`, `transport_config`, `request_timeout`, `status`, `create_time`, `update_time`)
VALUES
    ('5009', '百度千帆实时联网搜索(web-search)', 'streamable-http',
     '{"url":"https://qianfan.baidubce.com/v2/tools/web-search/mcp","headers":{"Authorization":"Bearer xxxxx"}}',
     180, 1, NOW(), NOW());

-- 验证 MCP 定义已插入
SELECT `mcp_id`, `mcp_name`, `transport_type`, `transport_config`
FROM `ai_client_tool_mcp`
WHERE `transport_type` = 'streamable-http';

-- =============================================================================
-- 绑定到 Agent 8011（让它能调用 web-search）
-- =============================================================================
-- 装配链：agent → client → model → tool_mcp（MCP 绑在 model 级别，见 AgentRepository.AiClientToolMcpVOByClientIds）。
-- agent→client 的映射不在 ai_client_config（该表无 agent 级），所以下面需要你填 8011 实际用的 client_id。
--
-- 先查出 8011 用的 client 和 model（在你运行的库里执行，拿到 client_id / model_id）：
--   -- 8011 走哪个 client：看你的 agent 装配配置（ai_agent 相关表 / armory 装配日志）
--   -- 该 client 关联哪些 model：
--   SELECT * FROM `ai_client_config` WHERE source_type='client' AND source_id='<8011的client_id>' AND target_type='model' AND status=1;
--
-- 方式 A（推荐，填 client_id，子查询自动绑到该 client 的所有 model，且防重复）：
INSERT INTO `ai_client_config`
    (`source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`, `create_time`, `update_time`)
SELECT DISTINCT 'model', cc.`target_id`, 'tool_mcp', '5009', '""', 1, NOW(), NOW()
FROM `ai_client_config` cc
WHERE cc.`source_type` = 'client'
  AND cc.`source_id`   = '<填 8011 的 client_id>'
  AND cc.`target_type` = 'model'
  AND cc.`status`      = 1
  AND NOT EXISTS (
      SELECT 1 FROM `ai_client_config` x
      WHERE x.`source_type`='model' AND x.`source_id`=cc.`target_id`
        AND x.`target_type`='tool_mcp' AND x.`target_id`='5009');

-- 方式 B（若你已知 8011 用的 model_id，直接绑）：
-- INSERT INTO `ai_client_config`
--     (`source_type`,`source_id`,`target_type`,`target_id`,`ext_param`,`status`,`create_time`,`update_time`)
-- VALUES ('model', '<8011的model_id>', 'tool_mcp', '5009', '""', 1, NOW(), NOW());
