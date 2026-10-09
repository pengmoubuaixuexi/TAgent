# Parallel Search MCP（可选）

通过 TAgent 已有的 `streamable-http` 装配路径接入 [Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)，提供 `web_search` 和 `web_fetch`。`https://search.parallel.ai/mcp` 支持匿名免费使用，不需要 Parallel API Key；免费层有速率限制，适合探索和轻量使用。这里只提供可选配置，不替换现有搜索服务。

## 管理员手动配置与绑定

以下 SQL 用于已完成 V062、V063 的 v1.28.0 开发数据库，供管理员给自己的原始模型和 Agent 添加私有连接；迁移顺序见[工作区说明](../USER_AGENT_WORKSPACE.md#本地与生产迁移顺序)。不要在生产库直接试跑，也不要用这些 SQL 修改个人工作区生成的模型副本。

在同一 SQL 会话中填写实际管理员的 `admin_user.user_id`（不是用户名）和模型 ID，先执行只读校验：

```sql
SET @parallel_owner = '<实际管理员 user_id>';
SET @parallel_model = '<该管理员的原始模型 ID>';
SET @parallel_mcp = 'parallel-search';

-- 必须返回一行：管理员启用，模型启用且归该管理员所有。
SELECT u.user_id, m.model_id
FROM admin_user u
JOIN ai_client_model m ON m.owner_user_id = u.user_id
WHERE u.user_id = @parallel_owner AND u.role = 'ADMIN' AND u.status = 1
  AND m.model_id = @parallel_model AND m.status = 1 AND m.source_model_id IS NULL;

-- 以下两次查询都必须为空；否则先换一个未使用的 MCP ID，再重新校验。
SELECT mcp_id FROM ai_client_tool_mcp WHERE mcp_id = @parallel_mcp;
SELECT id FROM ai_client_config
WHERE source_type = 'model' AND source_id = @parallel_model
  AND target_type = 'tool_mcp' AND target_id = @parallel_mcp;
```

仅在校验符合预期后继续。目标 Agent 和 Client 也必须属于同一管理员；模型绑定影响所有直接使用该模型的 Client，需要隔离时先创建独立的管理员模型。暂停相关运行后执行新增，任一步失败时执行 `ROLLBACK`，不要继续提交：

```sql
START TRANSACTION;
INSERT INTO ai_client_tool_mcp
    (mcp_id, mcp_name, transport_type, transport_config, request_timeout, status,
     owner_user_id, is_public, create_time, update_time)
VALUES
    (@parallel_mcp, 'parallel-search', 'streamable-http',
     '{"url":"https://search.parallel.ai/mcp","headers":{"User-Agent":"TAgent/parallel-search-example"}}',
     180, 1, @parallel_owner, 0, NOW(), NOW());

INSERT INTO ai_client_config
    (source_type, source_id, target_type, target_id, ext_param, status, create_time, update_time)
VALUES
    ('model', @parallel_model, 'tool_mcp', @parallel_mcp, '""', 1, NOW(), NOW());
COMMIT;
```

必须填写 `owner_user_id`：省略后会使用不可访问的 `__legacy_unowned__`，管理员也无法调用。`is_public=0` 保留管理员私用，不向普通用户分配连接。不要修改或删除已有绑定，也不要重复插入。重启应用后重新装配目标 Agent，管理员可在 `agent-config.html` 查看关系。

传输配置与本目录的 [`transport.json`](transport.json) 相同，不添加认证头。超时 `180` 按秒读取。该配置走管理员维护路径，不需要开放个人 MCP 域名白名单。

可在对话页尝试：“搜索 Spring AI MCP 客户端文档，返回来源链接；读取 https://docs.parallel.ai/integrations/mcp/search-mcp 并总结匿名接入方式。”模型是否调用工具取决于模型与 Agent 配置。建议在同一会话的搜索和读取调用中复用稳定的 `session_id`；不要编造 `model_name`。

## 普通用户工作区与可选公共工具池

- **个人连接**：先由管理员在 `agent.workspace.mcp-allowed-hosts` 中加入精确域名 `search.parallel.ai`，保留已有可信域名。用户在 `/user-agents.html` 的“我的 MCP”添加 Streamable HTTP 连接，地址填 `https://search.parallel.ai/mcp`，令牌留空，再在执行节点选择连接并保存。工作区会生成本人拥有的连接和节点授权；不能靠给平台源模型插入 SQL 绑定来替代此流程，`agent-config.html` 也不向普通用户开放。
- **公共工具池（管理员另行选择）**：本示例默认不共享。管理员确认愿意向用户开放服务及其额度后，可将该源连接的 `is_public` 设为 `1`。用户登录或刷新登录态后获得本人的平台连接副本；已有个人节点需启用公共工具池并重新保存，才能纳入新连接。平台托管连接不要求用户配置个人域名白名单，副本不会自动同步后续源配置修改。完整边界见[公共工具目录说明](../USER_AGENT_WORKSPACE.md#公共工具目录与检索修复v063)。

## 可复现的 MCP smoke test

安装 Java 17 和 Maven，从仓库根目录运行（依赖由项目 POM 声明）：

```bash
# 本地 HTTP fixture：验证实际装配、发现、工具执行及请求头，不调用外部服务
mvn -pl ai-agent-station-study-app -am -Dtest=ParallelSearchMcpTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test

# 真实匿名端点：同一 loader 读取 transport.json，搜索并读取网页
mvn -pl ai-agent-station-study-app -am -Dtest=ParallelSearchMcpTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dparallel.search.live=true test
```

测试使用 mock DAO 代替数据库，经 `AgentRepository.queryAiClientToolMcpVOByMcpId` 解析配置，再由 `AiClientToolMcpNode.createMcpSyncClient` 创建客户端。无需启动 MySQL、Redis、应用或 LLM。真实测试直接执行 MCP 工具，断言结果包含来源 URL 和非空摘录；它不验证 LLM 的 Agent 推理循环或完整运行时所有权校验。默认测试不访问外网。真实测试会消耗免费层额度，限流或服务不可用时会失败，不应自动反复重试。
