-- V046 (2026-06-05): doc2query —— 给工具目录加"意图/示例查询/同义能力词"列，桥接"意图词 vs 能力词"的词汇鸿沟。
--
-- 背景：BM25 是纯词面匹配。用户 query(need) 说的是意图/领域词(搜景点攻略、找程序员书籍)，
-- 工具描述说的是能力/机制词(联网搜索引擎返回网页)，两套词不重叠 → 匹配不到，还会抓巧合共现词出垃圾。
-- 解法(doc2query)：刷新翻译工具描述时，同一次 LLM 调用顺带给每个工具生成一串"典型用户意图/示例查询/
-- 同义能力词"，落到 tool_intent_zh，一起进 BM25 索引。这样工具文档里就真出现了"景点/攻略/书籍/实时资讯"，
-- 意图 query 能词面命中。成本全在刷新期(手动、批量)，不碰每请求配额。
--
-- 用户授权直接删表重建(目录数据全部可由 POST /tool-catalog/refresh 从 MCP 重新拉取+翻译生成)。
-- 删表后所有行清空 → 下次 refresh 全量重译并生成 intent。

DROP TABLE IF EXISTS `ai_mcp_tool_catalog`;

CREATE TABLE `ai_mcp_tool_catalog` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    `mcp_id` VARCHAR(64) NOT NULL COMMENT 'MCP id from ai_client_tool_mcp',
    `mcp_name` VARCHAR(128) DEFAULT NULL COMMENT 'MCP display name',
    `tool_name` VARCHAR(255) NOT NULL COMMENT 'MCP tool name',
    `tool_description` TEXT DEFAULT NULL COMMENT 'Tool description exposed to LLM (raw upstream)',
    `tool_description_zh` TEXT DEFAULT NULL COMMENT 'Chinese purpose description for routing/UI/BM25',
    `tool_intent_zh` TEXT DEFAULT NULL COMMENT 'doc2query: typical user intents / example queries / capability synonyms (zh), for BM25 recall',
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
