-- V040 (2026-05-29): 修正 FLOW step2(PLANNING_CLIENT)系统提示——应为"执行步骤规划器"而非领域设计师
--
-- 背景：FLOW 流程 = step1 工具分析 → step2 步骤规划(产出"第N步 + DEPENDS_ON"，供 step3 正则解析成 DAG)
--       → step3 解析 → step4 逐步执行+总结。Step2PlanningNode 的 user 提示已要求输出可解析的步骤计划，
--       但不设 .system()，沿用 client 默认系统提示。V039 把 8010_p2 写成"行程设计师→输出分日行程框架"，
--       system 优先级 > user，导致 step2 直接吐出最终行程框架、而非步骤计划，step3 无法解析 → 流程断。
-- 修复：把 FLOW 的 p2 改成通用"执行步骤规划器"角色，禁工具/禁执行/禁直接出最终结果，与节点 user 提示一致。
--       领域输出风格只保留在 p3(EXECUTOR/step4 执行+总结)，不在 p2。
-- 备份见 V039 同目录 docs/dev-ops/sql-backups/ai_client_system_prompt_backup_2026-05-29.sql
-- 注意：8013_p2(通用 Flow 模板)未动——用户确认其为正确参照、当前可用，避免误伤。

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

UPDATE ai_client_system_prompt SET prompt_content=
'你是执行计划规划师。基于上一步的工具能力分析，把用户任务拆解为 3-5 个可被程序解析的执行步骤。本阶段只产出步骤计划，禁止调用任何工具、禁止执行、禁止直接给出最终行程或答案；必须严格按调用方要求的 Markdown 步骤格式（第N步 + DEPENDS_ON 依赖）输出，供后续解析为 DAG。'
WHERE prompt_id='8010_p2';

UPDATE ai_client_system_prompt SET prompt_content=
'你是执行计划规划师。基于上一步的工具能力分析，把用户的创作任务拆解为 3-5 个可被程序解析的执行步骤。本阶段只产出步骤计划，禁止调用任何工具、禁止执行、禁止直接产出作品；必须严格按调用方要求的 Markdown 步骤格式（第N步 + DEPENDS_ON 依赖）输出，供后续解析为 DAG。'
WHERE prompt_id='8009_p2';
