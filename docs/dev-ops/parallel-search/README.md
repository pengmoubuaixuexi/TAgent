# Parallel Search MCP（可选）

通过 TAgent 已有的 `streamable-http` 装配路径接入 [Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)，提供 `web_search` 和 `web_fetch`。`https://search.parallel.ai/mcp` 支持匿名免费使用，不需要 Parallel API Key；免费层有速率限制，适合探索和轻量使用。这里只提供可选配置，不替换现有搜索服务。

## 配置与绑定

在自己的开发数据库中新增 MCP 记录，先确认 `parallel-search` 未被占用（如已占用，请为新记录选择其他 ID）。不要在生产库直接试跑示例：

```sql
INSERT INTO ai_client_tool_mcp
    (mcp_id, mcp_name, transport_type, transport_config, request_timeout, status, create_time, update_time)
VALUES
    ('parallel-search', 'parallel-search', 'streamable-http',
     '{"url":"https://search.parallel.ai/mcp","headers":{"User-Agent":"TAgent/parallel-search-example"}}',
     180, 1, NOW(), NOW());
```

传输配置与本目录的 [`transport.json`](transport.json) 相同，不添加认证头。超时 `180` 在当前装配代码中按秒读取。

显式绑定需要联网搜索的模型，将 `<模型 ID>` 替换为真实 ID（若更换了 MCP ID，也同步修改 `target_id`）：

```sql
INSERT INTO ai_client_config
    (source_type, source_id, target_type, target_id, ext_param, status, create_time, update_time)
VALUES
    ('model', '<模型 ID>', 'tool_mcp', 'parallel-search', '""', 1, NOW(), NOW());
```

绑定作用于所有使用该模型的 Client，请先确认范围；需要隔离时使用独立模型记录。不要修改或删除已有绑定，也不要重复插入。重启应用后重新装配目标 Agent，可在 `agent-config.html` 查看其模型和 MCP 关系。

可在对话页尝试：“搜索 Spring AI MCP 客户端文档，返回来源链接；读取 https://docs.parallel.ai/integrations/mcp/search-mcp 并总结匿名接入方式。”模型是否调用工具取决于模型与 Agent 配置。建议在同一会话的搜索和读取调用中复用稳定的 `session_id`；不要编造 `model_name`。

## 可复现的 MCP smoke test

安装 Java 17 和 Maven，从仓库根目录运行（依赖由项目 POM 声明）：

```bash
# 本地 HTTP fixture：验证实际装配、发现、工具执行及请求头，不调用外部服务
mvn -pl ai-agent-station-study-app -am -Dtest=ParallelSearchMcpTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test

# 真实匿名端点：同一 loader 读取 transport.json，搜索并读取网页
mvn -pl ai-agent-station-study-app -am -Dtest=ParallelSearchMcpTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dparallel.search.live=true test
```

测试使用 mock DAO 代替数据库，经 `AgentRepository.queryAiClientToolMcpVOByMcpId` 解析配置，再由 `AiClientToolMcpNode.createMcpSyncClient` 创建客户端。无需启动 MySQL、Redis、应用或 LLM。真实测试直接执行 MCP 工具，断言结果包含来源 URL 和非空摘录；它不验证 LLM 的 Agent 推理循环。默认测试不访问外网。真实测试会消耗免费层额度，限流或服务不可用时会失败，不应自动反复重试。
