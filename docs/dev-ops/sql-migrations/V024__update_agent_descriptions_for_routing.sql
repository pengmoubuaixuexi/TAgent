-- 更新 agent description：应用领域 + 任务层次
-- 同领域的 agent 必须通过任务层次区分，路由器才能正确选择

-- 85374287 (Fixed)：简单、单轮能完成的任务
UPDATE ai_agent SET description = '通用问答 — 简单问答、代码生成、翻译、信息查询' WHERE agent_id = '85374287';

-- 1234 (Auto)：需要多轮迭代的复杂任务
UPDATE ai_agent SET description = '通用问答 — 多维度分析、复杂推理、长文写作、方案设计' WHERE agent_id = '1234';
