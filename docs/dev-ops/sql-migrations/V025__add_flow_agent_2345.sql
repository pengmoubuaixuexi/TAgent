-- V025: 新增 Flow agent 2345 + 更新三个 agent 的 description
-- 按 1234 的模式创建独立的 api / model / prompt / client / config 链

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- ============================================================
-- 1. 更新 description
-- ============================================================
UPDATE ai_agent SET description = '通用问答 — 闲聊问候、事实问答、代码片段、翻译、格式化等一次回答即可的任务'
WHERE agent_id = '85374287';

UPDATE ai_agent SET description = '通用问答 — 需要深入分析、多步推理、多维度对比、方案设计等需要反复思考的任务'
WHERE agent_id = '1234';

-- ============================================================
-- 2. API（复用 1234 的地址和 key，新建一条记录）
-- ============================================================
INSERT INTO ai_client_api (api_id, base_url, api_key, completions_path, embeddings_path, status, create_time, update_time)
VALUES ('2344_api', 'https://z.apiyihe.org', '${OPENAI_API_KEY}', '/v1/chat/completions', '/v1/embeddings', 1, NOW(), NOW());

-- ============================================================
-- 3. Model（3 个，分别给 3 个 client 用）
-- ============================================================
INSERT INTO ai_client_model (model_id, api_id, model_usage, model_name, model_type, status, create_time, update_time)
VALUES
('2345_m1', '2344_api', 'Flow-Step1-任务分析', 'gpt-4o-mini', 'openai', 1, NOW(), NOW()),
('2345_m2', '2344_api', 'Flow-Step2-任务执行', 'gpt-4o', 'openai', 1, NOW(), NOW()),
('2345_m3', '2344_api', 'Flow-Step3-结果整合', 'gpt-4o-mini', 'openai', 1, NOW(), NOW());

-- ============================================================
-- 4. System Prompt（3 个，分别给 3 个 step）
-- ============================================================
INSERT INTO ai_client_system_prompt (prompt_id, prompt_name, prompt_content, description, status, create_time, update_time)
VALUES
('2345_p1', 'Flow-任务分析',
'你是一个任务分析师。请分析用户需求，制定执行策略。\n\n分析要求：\n1. 理解用户的核心需求\n2. 分解为可执行的步骤\n3. 确定每步的输出要求\n\n输出格式：\n任务分析: [需求理解]\n执行策略: [分步计划]\n完成度评估: [0-100]%%\n任务状态: [CONTINUE/COMPLETED]',
'Flow第一步：分析用户需求', 1, NOW(), NOW()),

('2345_p2', 'Flow-任务执行',
'你是一个精准任务执行器。根据分析师的策略，执行具体任务并产出结果。\n\n执行要求：\n1. 按策略逐步执行\n2. 产出具体、完整的结果\n3. 确保结果直接回答用户问题\n\n输出格式：\n执行目标: [目标]\n执行过程: [步骤]\n执行结果: [具体成果]',
'Flow第二步：执行具体任务', 1, NOW(), NOW()),

('2345_p3', 'Flow-结果整合',
'你是结果整合助手。基于前面的分析和执行结果，给用户一个完整的最终回答。\n\n要求：\n1. 直接回答用户原始问题\n2. 结构化展示\n3. 内容完整、实用\n\n请直接给出最终答案。',
'Flow第三步：整合输出最终结果', 1, NOW(), NOW());

-- ============================================================
-- 5. Client（3 个）
-- ============================================================
INSERT INTO ai_client (client_id, client_name, description, status, create_time, update_time)
VALUES
('234501', 'Flow-任务分析', '分析用户需求，制定执行策略', 1, NOW(), NOW()),
('234502', 'Flow-任务执行', '根据策略执行具体任务', 1, NOW(), NOW()),
('234503', 'Flow-结果整合', '整合结果输出最终回答', 1, NOW(), NOW());

-- ============================================================
-- 6. Client Config（每个 client 关联自己的 model 和 prompt，无 advisor/mcp）
-- ============================================================
INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, ext_param, status, create_time, update_time)
VALUES
-- client 234501 → model 2345_m1 + prompt 2345_p1
('client', '234501', 'model', '2345_m1', NULL, 1, NOW(), NOW()),
('client', '234501', 'prompt', '2345_p1', NULL, 1, NOW(), NOW()),
-- client 234502 → model 2345_m2 + prompt 2345_p2
('client', '234502', 'model', '2345_m2', NULL, 1, NOW(), NOW()),
('client', '234502', 'prompt', '2345_p2', NULL, 1, NOW(), NOW()),
-- client 234503 → model 2345_m3 + prompt 2345_p3
('client', '234503', 'model', '2345_m3', NULL, 1, NOW(), NOW()),
('client', '234503', 'prompt', '2345_p3', NULL, 1, NOW(), NOW());

-- ============================================================
-- 7. Agent
-- ============================================================
INSERT INTO ai_agent (agent_id, agent_name, description, channel, strategy, status, create_time, update_time)
VALUES ('2345', '通用问答-Flow',
        '通用问答 — 需要按阶段依次执行的复合任务，如先调研再分析再输出、先读代码再改代码再写测试',
        'agent', 'flowAgentExecuteStrategy', 1, NOW(), NOW());

-- ============================================================
-- 8. Agent Flow Config（3 步流水线）
-- ============================================================
INSERT INTO ai_agent_flow_config (agent_id, client_id, client_name, client_type, sequence, step_prompt, status, create_time)
VALUES
('2345', '234501', 'Flow-任务分析', 'TASK_ANALYZER_CLIENT', 1,
 '原始用户需求: %s\n当前执行步骤: 第 %d 步 (最大 %d 步)\n历史执行记录:\n%s\n当前任务: %s\n请分析用户需求并制定执行策略。',
 1, NOW()),

('2345', '234502', 'Flow-任务执行', 'PRECISION_EXECUTOR_CLIENT', 2,
 '用户原始需求: %s\n分析师策略: %s\n请根据策略执行具体任务，产出完整结果。',
 1, NOW()),

('2345', '234503', 'Flow-结果整合', 'RESPONSE_ASSISTANT', 3,
 '用户原始问题: %s\n执行历史:\n%s\n请基于以上执行过程，直接回答用户问题，给出完整的最终答案。',
 1, NOW());
