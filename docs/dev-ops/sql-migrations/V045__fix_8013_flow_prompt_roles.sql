-- V045 (2026-06-04): 修正 8013(通用问答-Flow) 的 p1/p2 角色错配
--
-- 背景：8013 的 prompt 文案与 ai_agent_flow_config.client_type 对不上：
--   p1=TOOL_MCP_CLIENT(分析) 文案却是带 auto 风格 CONTINUE/COMPLETED 的"任务分析师"；
--   p2=PLANNING_CLIENT(规划) 文案却写成"精准任务执行器…执行具体任务"。
-- flow 策略按 client_type 调度：Step2 用 PLANNING_CLIENT 期望产出 DAG 步骤计划，p2 写成执行器会喂错。
-- 按 8010(良好范例) 的角色模型重写 p1/p2（通用措辞、禁工具内联）。p3=EXECUTOR_CLIENT 文案"结果整合"
-- 本就是最终合成角色，正确，不动（其工具能力由执行子步的 Java SUB_STEP_EXECUTOR_SYSTEM 负责）。
-- 整体替换（非前置），因此自动覆盖 V044 给 8013_p1 加的前置约束（新文案已内联禁工具）。

SET NAMES utf8mb4;

-- p1: TOOL_MCP_CLIENT —— 工具能力分析师（禁工具，只输出分析）
UPDATE ai_client_system_prompt
SET prompt_content = '你是通用问答助手的工具能力分析师。本阶段只评估可用工具与用户需求的适配度，禁止调用任何工具、禁止执行用户的实际请求，只输出文字分析；不要假装调用工具或编造工具返回结果。\n\n分析要点：\n1. 用户需求属于什么类别、需要哪些能力\n2. 已列出的工具哪些能满足、哪些不能（匹配度）\n3. 工具不足时如何用模型自身知识补足\n4. 给后续规划与执行阶段的建议'
WHERE prompt_id = '8013_p1';

-- p2: PLANNING_CLIENT —— 执行计划规划师（禁工具，只产出可解析 DAG 步骤）
UPDATE ai_client_system_prompt
SET prompt_content = '你是执行计划规划师。基于上一步的工具能力分析，把用户任务拆解为 1-5 个可被程序解析的执行步骤。本阶段只产出步骤计划，禁止调用任何工具、禁止执行、禁止直接给出最终答案；必须严格按调用方要求的 Markdown 步骤格式（第N步 + DEPENDS_ON 依赖）输出，供后续解析为 DAG。'
WHERE prompt_id = '8013_p2';
