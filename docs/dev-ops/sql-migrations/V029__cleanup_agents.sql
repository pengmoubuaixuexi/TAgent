-- V029: 整理 Agent — 删除旧 agent + 重编号 + 新建替代
-- 删除 7 个旧 agent（1-6, 20099179）及所有关联数据
-- 重编号：85374287→8011, 1234→8012, 2345→8013
-- 新建 3 个技术运维 agent（8014-8016）

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- ============================================================
-- 0. 验证：确认要删除/重编号的数据存在
-- ============================================================
-- 手动执行以下 SELECT 确认数据存在后再执行后续操作：
--
-- SELECT agent_id, agent_name FROM ai_agent WHERE agent_id IN ('1','2','3','4','5','6','20099179');
-- SELECT agent_id, client_id FROM ai_agent_flow_config WHERE agent_id IN ('1','3','4','5','6','20099179');
-- SELECT client_id FROM ai_client WHERE client_id IN ('2101','2102','2103','3101','3102','3103','3104','4101','4102','4103','4104','5101','5102','5103','5104','6101');
-- SELECT agent_id FROM ai_agent WHERE agent_id IN ('85374287','1234','2345');
-- SELECT agent_id, client_id FROM ai_agent_flow_config WHERE agent_id = '1234';
-- SELECT client_id FROM ai_client WHERE client_id LIKE '1234%';
-- SELECT model_id FROM ai_client_model WHERE model_id LIKE '1234%';
-- SELECT prompt_id FROM ai_client_system_prompt WHERE prompt_id LIKE '1234%';

-- ============================================================
-- 1. DELETE 旧 agent 及关联数据（按依赖顺序）
-- ============================================================

-- 1a. Agent 1（智能对话体-Flow）关联数据
DELETE FROM ai_agent_flow_config WHERE agent_id = '1';
DELETE FROM ai_client_config WHERE source_id IN ('2101','2102','2103') OR target_id IN ('2101','2102','2103');
DELETE FROM ai_client WHERE client_id IN ('2101','2102','2103');
DELETE FROM ai_client_system_prompt WHERE prompt_id IN ('6001','6002','6003');
DELETE FROM ai_client_model WHERE model_id IN ('2000','2001');
DELETE FROM ai_client_advisor WHERE advisor_id = '4002';
DELETE FROM ai_agent WHERE agent_id = '1';

-- 1b. Agent 2（智能对话体-MCP）— 无关联数据
DELETE FROM ai_agent WHERE agent_id = '2';

-- 1c. Agent 3（智能对话体-Auto）关联数据
DELETE FROM ai_agent_flow_config WHERE agent_id = '3';
DELETE FROM ai_client_config WHERE source_id IN ('3101','3102','3103','3104') OR target_id IN ('3101','3102','3103','3104');
DELETE FROM ai_client WHERE client_id IN ('3101','3102','3103','3104');
DELETE FROM ai_client_system_prompt WHERE prompt_id IN ('6101','6102','6103','6104');
DELETE FROM ai_client_model WHERE model_id = '3001';
DELETE FROM ai_agent WHERE agent_id = '3';

-- 1d. Agent 4（智能对话体-Auto-ES）关联数据
DELETE FROM ai_agent_flow_config WHERE agent_id = '4';
DELETE FROM ai_client_config WHERE source_id IN ('4101','4102','4103','4104') OR target_id IN ('4101','4102','4103','4104');
DELETE FROM ai_client WHERE client_id IN ('4101','4102','4103','4104');
DELETE FROM ai_client_system_prompt WHERE prompt_id IN ('7101','7102','7103','7104');
DELETE FROM ai_client_model WHERE model_id = '4001';
DELETE FROM ai_agent WHERE agent_id = '4';

-- 1e. Agent 5（智能对话体-Auto-监控）关联数据
DELETE FROM ai_agent_flow_config WHERE agent_id = '5';
DELETE FROM ai_client_config WHERE source_id IN ('5101','5102','5103','5104') OR target_id IN ('5101','5102','5103','5104');
DELETE FROM ai_client WHERE client_id IN ('5101','5102','5103','5104');
DELETE FROM ai_client_system_prompt WHERE prompt_id IN ('8101','8102','8103','8104');
DELETE FROM ai_client_model WHERE model_id = '5001';
DELETE FROM ai_client_advisor WHERE advisor_id = '4003';
DELETE FROM ai_agent WHERE agent_id = '5';

-- 1f. Agent 6（智能对话体-Fixed）关联数据
DELETE FROM ai_agent_flow_config WHERE agent_id = '6';
DELETE FROM ai_client_config WHERE source_id = '6101' OR target_id = '6101';
DELETE FROM ai_client WHERE client_id = '6101';
DELETE FROM ai_client_model WHERE model_id = '6001';
DELETE FROM ai_client_advisor WHERE advisor_id = '4004';
DELETE FROM ai_agent WHERE agent_id = '6';

-- 1g. Agent 20099179（111-测试model）关联数据
-- 注意：client 48376249 被 agent 85374287（即 8011）也使用，不能删
DELETE FROM ai_agent_flow_config WHERE agent_id = '20099179';
DELETE FROM ai_agent WHERE agent_id = '20099179';

-- 1h. 清理旧 model → tool_mcp 的 config 记录（V029 遗漏）
-- V029 删除了旧 model 本身，但没有删除 source_type='model' 的 config 记录
DELETE FROM ai_client_config WHERE source_type = 'model' AND source_id IN ('2000','2001','3001','4001','5001','6001');

-- ============================================================
-- 2. RENUMBER 现有 agent
-- ============================================================

-- 2a. 85374287 → 8011（通用问答-Fixed, fallback agent）
UPDATE ai_agent SET agent_id = '8011' WHERE agent_id = '85374287';
UPDATE ai_agent_flow_config SET agent_id = '8011' WHERE agent_id = '85374287';

-- 2b. 1234 → 8012（通用问答-Auto）
-- 注意：agent 1234 通过 Web UI 创建，关联数据 ID 按命名规则推断
-- 如果以下 UPDATE 影响 0 行，说明实际 ID 不同，需手动查询确认
UPDATE ai_agent SET agent_id = '8012' WHERE agent_id = '1234';
UPDATE ai_agent_flow_config SET agent_id = '8012' WHERE agent_id = '1234';
-- 更新关联的 client（按命名规则 123401-123404）
UPDATE ai_client SET client_id = '801201' WHERE client_id = '123401';
UPDATE ai_client SET client_id = '801202' WHERE client_id = '123402';
UPDATE ai_client SET client_id = '801203' WHERE client_id = '123403';
UPDATE ai_client SET client_id = '801204' WHERE client_id = '123404';
UPDATE ai_agent_flow_config SET client_id = '801201' WHERE client_id = '123401' AND agent_id = '8012';
UPDATE ai_agent_flow_config SET client_id = '801202' WHERE client_id = '123402' AND agent_id = '8012';
UPDATE ai_agent_flow_config SET client_id = '801203' WHERE client_id = '123403' AND agent_id = '8012';
UPDATE ai_agent_flow_config SET client_id = '801204' WHERE client_id = '123404' AND agent_id = '8012';
UPDATE ai_client_config SET source_id = '801201' WHERE source_id = '123401';
UPDATE ai_client_config SET source_id = '801202' WHERE source_id = '123402';
UPDATE ai_client_config SET source_id = '801203' WHERE source_id = '123403';
UPDATE ai_client_config SET source_id = '801204' WHERE source_id = '123404';
-- 更新关联的 model（按命名规则 1234_m1-m4）
UPDATE ai_client_model SET model_id = '8012_m1' WHERE model_id = '1234_m1';
UPDATE ai_client_model SET model_id = '8012_m2' WHERE model_id = '1234_m2';
UPDATE ai_client_model SET model_id = '8012_m3' WHERE model_id = '1234_m3';
UPDATE ai_client_model SET model_id = '8012_m4' WHERE model_id = '1234_m4';
UPDATE ai_client_config SET target_id = '8012_m1' WHERE target_id = '1234_m1';
UPDATE ai_client_config SET target_id = '8012_m2' WHERE target_id = '1234_m2';
UPDATE ai_client_config SET target_id = '8012_m3' WHERE target_id = '1234_m3';
UPDATE ai_client_config SET target_id = '8012_m4' WHERE target_id = '1234_m4';
-- 更新关联的 prompt（按命名规则 1234_p1-p4）
UPDATE ai_client_system_prompt SET prompt_id = '8012_p1' WHERE prompt_id = '1234_p1';
UPDATE ai_client_system_prompt SET prompt_id = '8012_p2' WHERE prompt_id = '1234_p2';
UPDATE ai_client_system_prompt SET prompt_id = '8012_p3' WHERE prompt_id = '1234_p3';
UPDATE ai_client_system_prompt SET prompt_id = '8012_p4' WHERE prompt_id = '1234_p4';
UPDATE ai_client_config SET target_id = '8012_p1' WHERE target_id = '1234_p1';
UPDATE ai_client_config SET target_id = '8012_p2' WHERE target_id = '1234_p2';
UPDATE ai_client_config SET target_id = '8012_p3' WHERE target_id = '1234_p3';
UPDATE ai_client_config SET target_id = '8012_p4' WHERE target_id = '1234_p4';

-- 2c. 2345 → 8013（通用问答-Flow）
UPDATE ai_agent SET agent_id = '8013' WHERE agent_id = '2345';
UPDATE ai_agent_flow_config SET agent_id = '8013' WHERE agent_id = '2345';
-- 更新关联的 client
UPDATE ai_client SET client_id = '801301' WHERE client_id = '234501';
UPDATE ai_client SET client_id = '801302' WHERE client_id = '234502';
UPDATE ai_client SET client_id = '801303' WHERE client_id = '234503';
UPDATE ai_agent_flow_config SET client_id = '801301' WHERE client_id = '234501' AND agent_id = '8013';
UPDATE ai_agent_flow_config SET client_id = '801302' WHERE client_id = '234502' AND agent_id = '8013';
UPDATE ai_agent_flow_config SET client_id = '801303' WHERE client_id = '234503' AND agent_id = '8013';
UPDATE ai_client_config SET source_id = '801301' WHERE source_id = '234501';
UPDATE ai_client_config SET source_id = '801302' WHERE source_id = '234502';
UPDATE ai_client_config SET source_id = '801303' WHERE source_id = '234503';
-- 更新关联的 model
UPDATE ai_client_model SET model_id = '8013_m1' WHERE model_id = '2345_m1';
UPDATE ai_client_model SET model_id = '8013_m2' WHERE model_id = '2345_m2';
UPDATE ai_client_model SET model_id = '8013_m3' WHERE model_id = '2345_m3';
UPDATE ai_client_config SET target_id = '8013_m1' WHERE target_id = '2345_m1';
UPDATE ai_client_config SET target_id = '8013_m2' WHERE target_id = '2345_m2';
UPDATE ai_client_config SET target_id = '8013_m3' WHERE target_id = '2345_m3';
-- 更新关联的 prompt
UPDATE ai_client_system_prompt SET prompt_id = '8013_p1' WHERE prompt_id = '2345_p1';
UPDATE ai_client_system_prompt SET prompt_id = '8013_p2' WHERE prompt_id = '2345_p2';
UPDATE ai_client_system_prompt SET prompt_id = '8013_p3' WHERE prompt_id = '2345_p3';
UPDATE ai_client_config SET target_id = '8013_p1' WHERE target_id = '2345_p1';
UPDATE ai_client_config SET target_id = '8013_p2' WHERE target_id = '2345_p2';
UPDATE ai_client_config SET target_id = '8013_p3' WHERE target_id = '2345_p3';

-- ============================================================
-- 3. 更新 agent description（重编号后）
-- ============================================================
UPDATE ai_agent SET description = '通用问答 — 闲聊问候、事实问答、代码片段、翻译、格式化等一次回答即可的任务' WHERE agent_id = '8011';
UPDATE ai_agent SET description = '通用问答 — 需要深入分析、多步推理、多维度对比、方案设计等需要反复思考的任务' WHERE agent_id = '8012';
UPDATE ai_agent SET description = '通用问答 — 需要按阶段依次执行的复合任务，如先调研再分析再输出、先读代码再改代码再写测试' WHERE agent_id = '8013';

-- ============================================================
-- 4. 新建替代 agent（技术运维方向）
-- ============================================================

-- 4a. 8014: 技术博客助手 (Fixed)
INSERT INTO ai_client_model (model_id, api_id, model_usage, model_name, model_type, tier, status, create_time, update_time)
VALUES ('8014_m', '1001', '技术博客助手', 'gpt-4.1-mini', 'openai', 'small', 1, NOW(), NOW());

INSERT INTO ai_client_system_prompt (prompt_id, prompt_name, prompt_content, description, status, create_time, update_time)
VALUES ('8014_p1', '技术博客助手',
'你是一位技术博客助手，擅长撰写和发布技术文章。\n\n你的能力：\n- 通过 CSDN 发帖工具发布技术博客\n- 通过微信公众号通知工具推送文章\n- 通过 g-search 搜索技术资料和最新动态\n\n回复要求：\n1. 文章结构清晰：标题、摘要、正文、总结\n2. 代码示例完整可运行\n3. 配图和排版美观\n4. 发布后通过微信通知\n5. 支持 Markdown 格式',
'技术博客助手：撰写发布技术文章到CSDN，微信通知', 1, NOW(), NOW());

INSERT INTO ai_client (client_id, client_name, description, status, create_time, update_time)
VALUES ('801401', '技术博客助手', '撰写发布技术博客到CSDN，微信通知', 1, NOW(), NOW());

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, ext_param, status, create_time, update_time)
VALUES
('client', '801401', 'model', '8014_m', NULL, 1, NOW(), NOW()),
('client', '801401', 'prompt', '8014_p1', NULL, 1, NOW(), NOW()),
('client', '801401', 'advisor', '4001', NULL, 1, NOW(), NOW()),
-- MCP: CSDN发帖(5001) + 微信通知(5002) + g-search(5004)
('model', '8014_m', 'tool_mcp', '5001', NULL, 1, NOW(), NOW()),
('model', '8014_m', 'tool_mcp', '5002', NULL, 1, NOW(), NOW()),
('model', '8014_m', 'tool_mcp', '5004', NULL, 1, NOW(), NOW());

INSERT INTO ai_agent (agent_id, agent_name, description, channel, strategy, status, create_time, update_time)
VALUES ('8014', '技术博客助手',
 '技术运维 — 技术博客：撰写技术文章、发布到CSDN、微信公众号通知、代码示例、技术资料搜索',
 'agent', 'fixedAgentExecuteStrategy', 1, NOW(), NOW());

INSERT INTO ai_agent_flow_config (agent_id, client_id, client_name, client_type, sequence, step_prompt, status, create_time)
VALUES ('8014', '801401', '技术博客助手', 'TOOL_MCP_CLIENT', 1,
 '原始用户需求: %s\n请撰写并发布技术博客文章。',
 1, NOW());

-- 4b. 8015: 日志分析助手 (Auto, 4步)
INSERT INTO ai_client_model (model_id, api_id, model_usage, model_name, model_type, tier, status, create_time, update_time)
VALUES ('8015_m', '1001', '日志分析助手', 'gpt-4.1', 'openai', 'large', 1, NOW(), NOW());

INSERT INTO ai_client_system_prompt (prompt_id, prompt_name, prompt_content, description, status, create_time, update_time)
VALUES
('8015_p1', '日志分析-需求分析',
'你是日志分析需求分析师。分析用户的日志查询需求。\n\n工具使用：\n- elasticsearch: 查询日志数据\n- g-search: 搜索相关技术文档\n\n分析要求：\n1. 理解用户要查询什么类型的日志\n2. 确定时间范围和过滤条件\n3. 识别关键词和错误模式\n4. 制定查询策略\n\n输出：查询需求分析 + ES 查询策略',
'日志分析Step1：分析需求+制定查询策略', 1, NOW(), NOW()),

('8015_p2', '日志分析-数据检索',
'你是日志数据检索员。执行具体的 ES 查询。\n\n工具使用：\n- elasticsearch: 执行日志查询\n- g-search: 搜索错误解决方案\n\n检索要求：\n1. 按策略执行 ES 查询\n2. 统计错误类型和频率\n3. 提取关键日志片段\n4. 识别异常模式\n\n输出：查询结果 + 错误统计 + 异常模式',
'日志分析Step2：执行ES查询+统计分析', 1, NOW(), NOW()),

('8015_p3', '日志分析-根因分析',
'你是日志分析质检员。分析错误根因。\n\n分析要点：\n1. 错误发生的频率和趋势\n2. 关联不同日志找根因\n3. 评估影响范围\n4. 建议修复方向\n\n输出：根因分析 + 影响评估 + 修复建议',
'日志分析Step3：分析根因+评估影响', 1, NOW(), NOW()),

('8015_p4', '日志分析-报告输出',
'你是日志分析报告撰写者。输出最终分析报告。\n\n输出格式：\n1. 概览：日志时间范围、总量、错误率\n2. 错误分类：按类型统计\n3. 根因分析：关键错误的原因\n4. 影响评估：受影响的服务和用户\n5. 修复建议：优先级排序的修复方案\n\n要求：数据准确、建议可执行。',
'日志分析Step4：输出分析报告', 1, NOW(), NOW());

INSERT INTO ai_client (client_id, client_name, description, status, create_time, update_time)
VALUES
('801501', '日志分析-需求分析', '分析日志查询需求+制定策略', 1, NOW(), NOW()),
('801502', '日志分析-数据检索', '执行ES查询+统计分析', 1, NOW(), NOW()),
('801503', '日志分析-根因分析', '分析错误根因+评估影响', 1, NOW(), NOW()),
('801504', '日志分析-报告输出', '输出最终分析报告', 1, NOW(), NOW());

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, ext_param, status, create_time, update_time)
VALUES
('client', '801501', 'model', '8015_m', NULL, 1, NOW(), NOW()),
('client', '801501', 'prompt', '8015_p1', NULL, 1, NOW(), NOW()),
('client', '801501', 'advisor', '4001', NULL, 1, NOW(), NOW()),

('client', '801502', 'model', '8015_m', NULL, 1, NOW(), NOW()),
('client', '801502', 'prompt', '8015_p2', NULL, 1, NOW(), NOW()),
('client', '801502', 'advisor', '4001', NULL, 1, NOW(), NOW()),

('client', '801503', 'model', '8015_m', NULL, 1, NOW(), NOW()),
('client', '801503', 'prompt', '8015_p3', NULL, 1, NOW(), NOW()),
('client', '801503', 'advisor', '4001', NULL, 1, NOW(), NOW()),

('client', '801504', 'model', '8015_m', NULL, 1, NOW(), NOW()),
('client', '801504', 'prompt', '8015_p4', NULL, 1, NOW(), NOW()),
('client', '801504', 'advisor', '4001', NULL, 1, NOW(), NOW()),

-- MCP: elasticsearch(5007) + g-search(5004)
('model', '8015_m', 'tool_mcp', '5007', NULL, 1, NOW(), NOW()),
('model', '8015_m', 'tool_mcp', '5004', NULL, 1, NOW(), NOW());

INSERT INTO ai_agent (agent_id, agent_name, description, channel, strategy, status, create_time, update_time)
VALUES ('8015', '日志分析助手',
 '技术运维 — 日志分析：Elasticsearch日志检索、错误统计、根因分析、调用量统计、异常排查、性能分析',
 'agent', 'autoAgentExecuteStrategy', 1, NOW(), NOW());

INSERT INTO ai_agent_flow_config (agent_id, client_id, client_name, client_type, sequence, step_prompt, status, create_time)
VALUES
('8015', '801501', '日志分析-需求分析', 'TASK_ANALYZER_CLIENT', 1,
 '原始用户需求: %s\n当前执行步骤: 第 %d 步 (最大 %d 步)\n历史执行记录:\n%s\n当前任务: %s\n请分析用户的日志查询需求，制定 ES 查询策略。',
 1, NOW()),
('8015', '801502', '日志分析-数据检索', 'PRECISION_EXECUTOR_CLIENT', 2,
 '用户原始需求: %s\n分析师策略: %s\n请执行 ES 查询，统计错误类型和频率。',
 1, NOW()),
('8015', '801503', '日志分析-根因分析', 'QUALITY_SUPERVISOR_CLIENT', 3,
 '用户原始需求: %s\n执行结果: %s\n请分析错误根因，评估影响范围。',
 1, NOW()),
('8015', '801504', '日志分析-报告输出', 'RESPONSE_ASSISTANT', 4,
 '用户原始问题: %s\n执行历史:\n%s\n请输出最终的日志分析报告。',
 1, NOW());

-- 4c. 8016: 监控运维助手 (Auto, 4步)
INSERT INTO ai_client_model (model_id, api_id, model_usage, model_name, model_type, tier, status, create_time, update_time)
VALUES ('8016_m', '1001', '监控运维助手', 'gpt-4.1', 'openai', 'large', 1, NOW(), NOW());

INSERT INTO ai_client_system_prompt (prompt_id, prompt_name, prompt_content, description, status, create_time, update_time)
VALUES
('8016_p1', '监控运维-需求分析',
'你是监控运维需求分析师。分析用户的监控查询需求。\n\n工具使用：\n- grafana: 查询监控面板数据\n- g-search: 搜索运维文档和最佳实践\n\n分析要求：\n1. 理解用户要监控什么指标\n2. 确定时间范围和告警阈值\n3. 识别关键性能指标\n4. 制定查询策略\n\n输出：监控需求分析 + 查询策略',
'监控运维Step1：分析需求+制定查询策略', 1, NOW(), NOW()),

('8016_p2', '监控运维-数据查询',
'你是监控数据查询员。执行具体的 Grafana 查询。\n\n工具使用：\n- grafana: 查询监控面板和告警\n- g-search: 搜索性能优化方案\n\n查询要求：\n1. 按策略查询监控数据\n2. 分析指标趋势\n3. 识别异常波动\n4. 关联不同指标\n\n输出：监控数据 + 趋势分析 + 异常识别',
'监控运维Step2：查询Grafana数据+分析趋势', 1, NOW(), NOW()),

('8016_p3', '监控运维-告警分析',
'你是监控运维质检员。分析告警和性能问题。\n\n分析要点：\n1. 告警触发原因\n2. 性能瓶颈定位\n3. 资源使用情况\n4. 优化建议\n\n输出：告警分析 + 性能瓶颈 + 优化建议',
'监控运维Step3：分析告警+定位瓶颈', 1, NOW(), NOW()),

('8016_p4', '监控运维-报告输出',
'你是监控运维报告撰写者。输出最终运维报告。\n\n输出格式：\n1. 概览：监控时间范围、关键指标\n2. 告警汇总：触发的告警和处理状态\n3. 性能分析：瓶颈和优化建议\n4. 资源使用：CPU/内存/磁盘/网络\n5. 运维建议：优先级排序的改进方案\n\n要求：数据准确、建议可执行。',
'监控运维Step4：输出运维报告', 1, NOW(), NOW());

INSERT INTO ai_client (client_id, client_name, description, status, create_time, update_time)
VALUES
('801601', '监控运维-需求分析', '分析监控查询需求+制定策略', 1, NOW(), NOW()),
('801602', '监控运维-数据查询', '查询Grafana数据+分析趋势', 1, NOW(), NOW()),
('801603', '监控运维-告警分析', '分析告警+定位性能瓶颈', 1, NOW(), NOW()),
('801604', '监控运维-报告输出', '输出最终运维报告', 1, NOW(), NOW());

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, ext_param, status, create_time, update_time)
VALUES
('client', '801601', 'model', '8016_m', NULL, 1, NOW(), NOW()),
('client', '801601', 'prompt', '8016_p1', NULL, 1, NOW(), NOW()),
('client', '801601', 'advisor', '4001', NULL, 1, NOW(), NOW()),

('client', '801602', 'model', '8016_m', NULL, 1, NOW(), NOW()),
('client', '801602', 'prompt', '8016_p2', NULL, 1, NOW(), NOW()),
('client', '801602', 'advisor', '4001', NULL, 1, NOW(), NOW()),

('client', '801603', 'model', '8016_m', NULL, 1, NOW(), NOW()),
('client', '801603', 'prompt', '8016_p3', NULL, 1, NOW(), NOW()),
('client', '801603', 'advisor', '4001', NULL, 1, NOW(), NOW()),

('client', '801604', 'model', '8016_m', NULL, 1, NOW(), NOW()),
('client', '801604', 'prompt', '8016_p4', NULL, 1, NOW(), NOW()),
('client', '801604', 'advisor', '4001', NULL, 1, NOW(), NOW()),

-- MCP: grafana(5008) + g-search(5004)
('model', '8016_m', 'tool_mcp', '5008', NULL, 1, NOW(), NOW()),
('model', '8016_m', 'tool_mcp', '5004', NULL, 1, NOW(), NOW());

INSERT INTO ai_agent (agent_id, agent_name, description, channel, strategy, status, create_time, update_time)
VALUES ('8016', '监控运维助手',
 '技术运维 — 监控运维：Grafana监控面板查询、告警分析、性能瓶颈定位、资源使用监控、运维报告生成',
 'agent', 'autoAgentExecuteStrategy', 1, NOW(), NOW());

INSERT INTO ai_agent_flow_config (agent_id, client_id, client_name, client_type, sequence, step_prompt, status, create_time)
VALUES
('8016', '801601', '监控运维-需求分析', 'TASK_ANALYZER_CLIENT', 1,
 '原始用户需求: %s\n当前执行步骤: 第 %d 步 (最大 %d 步)\n历史执行记录:\n%s\n当前任务: %s\n请分析用户的监控查询需求，制定 Grafana 查询策略。',
 1, NOW()),
('8016', '801602', '监控运维-数据查询', 'PRECISION_EXECUTOR_CLIENT', 2,
 '用户原始需求: %s\n分析师策略: %s\n请查询 Grafana 监控数据，分析指标趋势。',
 1, NOW()),
('8016', '801603', '监控运维-告警分析', 'QUALITY_SUPERVISOR_CLIENT', 3,
 '用户原始需求: %s\n执行结果: %s\n请分析告警原因，定位性能瓶颈。',
 1, NOW()),
('8016', '801604', '监控运维-报告输出', 'RESPONSE_ASSISTANT', 4,
 '用户原始问题: %s\n执行历史:\n%s\n请输出最终的运维分析报告。',
 1, NOW());

-- ============================================================
-- 完成！
-- 删除：7 agent + 关联的 client/model/prompt/advisor/config/flow_config
-- 重编号：3 agent（85374287→8011, 1234→8012, 2345→8013）+ 所有关联数据
-- 新建：3 agent（8014-8016）+ 完整的 client/model/prompt/config/flow_config
-- ============================================================
