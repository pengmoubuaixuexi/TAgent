-- V044 (2026-06-04): 给 auto/flow 的非执行步骤系统提示补"禁止调用工具"硬约束
--
-- 背景：E2E100(2026-06-05)发现 auto 的分析/质检/汇总步越权调工具 ~102 次。
-- 根因之一是这些步骤的系统提示缺少工具约束（8012 全缺、8008_p4、8013_p1/p3、8015/8016 p3/p4）；
-- 而 8006/8007/8008_p1/p3、8009/8010 这些有"禁止调用任何工具"的步骤基本守住。
-- 只改"非执行步"（分析/规划/质检/汇总）；执行步（auto 的 p2、flow 的执行步）该调工具，不动。
--
-- 用 CONCAT 前置一段醒目的硬约束块，不改原内容；WHERE 加 NOT LIKE 守卫，重复执行不会叠加。
-- 约束同时覆盖"不要假装调用工具/编造工具返回"以防没装工具时的幻觉式调用。

SET NAMES utf8mb4;

-- ===== 分析步（只输出分析，禁工具）=====
UPDATE ai_client_system_prompt
SET prompt_content = CONCAT('【本阶段硬性约束】本步只做需求分析/策略规划，禁止调用任何工具、禁止执行用户的实际请求，只输出文字分析；即使你认为需要工具也不要调用，更不要假装调用工具或编造工具返回结果（工具留给后续执行阶段）。\n\n', prompt_content)
WHERE prompt_id IN ('8012_p1', '8013_p1') AND prompt_content NOT LIKE '%禁止调用任何工具%';

-- ===== 质检步（只输出审查意见，禁工具）=====
UPDATE ai_client_system_prompt
SET prompt_content = CONCAT('【本阶段硬性约束】本步只做质量审查，禁止调用任何工具、禁止执行，只输出审查意见；即使你认为需要核实也不要调用工具，更不要假装调用工具或编造工具返回结果。\n\n', prompt_content)
WHERE prompt_id IN ('8012_p3', '8015_p3', '8016_p3') AND prompt_content NOT LIKE '%禁止调用任何工具%';

-- ===== 汇总步（auto RESPONSE_ASSISTANT，只基于已有结果作答，禁工具）=====
-- 注意按 ai_agent_flow_config 的 client_type 判定，不能看 prompt 文案：
--   auto p4 = RESPONSE_ASSISTANT(汇总，禁工具) ；flow p3 = EXECUTOR_CLIENT(执行步，留工具，不在此列)。
-- 8013_p3 是 flow 的 EXECUTOR_CLIENT(其文案"结果整合"有误导)，不加约束。
-- 8013_p2 是 flow 的 PLANNING_CLIENT(本应禁工具)，但它的文案被写成"精准任务执行器…执行具体任务"，
--   与 PLANNING 角色矛盾；加"禁工具"会自相矛盾，故此处不动，单独标记待重写 8013_p2 文案（flow 本轮 0 越权，优先级低）。
UPDATE ai_client_system_prompt
SET prompt_content = CONCAT('【本阶段硬性约束】本步只做最终整理与汇总，禁止调用任何工具、禁止执行，只基于前面阶段已有结果作答；不要调用工具，更不要假装调用工具或编造工具返回结果。\n\n', prompt_content)
WHERE prompt_id IN ('8008_p4', '8012_p4', '8015_p4', '8016_p4') AND prompt_content NOT LIKE '%禁止调用任何工具%';
