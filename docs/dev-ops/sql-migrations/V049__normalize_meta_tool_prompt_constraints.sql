-- V049 (2026-06-19): 元工具(ask_user/request_tool)权限从 DB 系统提示里去硬编码，统一交给代码侧"条件豁免"
--                     (metaToolPromptHint) 在 user 消息按运行时开关注入。
--
-- 背景：
--   V048 给【分析步】系统提示写死了"ask_user 除外…应调用 ask_user"。这是静态文案，不跟运行时开关走：
--     - 关掉 ask_user 后，分析步提示仍叫模型用 ask_user（请求里却不广播）→ 误导/幻觉。
--   而各非执行步提示里的"禁止调用任何工具"是 system 角色硬禁止，会压过 user 侧的 request_tool 条件豁免
--     → 模型把 request_tool 调用"写成文本"(说而不做)。
--
-- 修法（与代码侧 metaToolPromptHint 配套，后者：ask_user 开关开+本会话有额度才提 ask_user、request_tool 开才提）：
--   1. 分析步(8012_p1 auto / 8013_p1 flow)：删掉 V048 写入的 ask_user 硬编码整句 → 收成"禁止调用业务/执行类工具…只输出文字分析。"
--   2. 所有"有条件豁免的非执行步"(全部 p1 分析 + flow p2 规划 + auto p3 质检)：把"禁止调用任何工具"→"禁止调用业务/执行类工具"
--      （只挡业务/执行类工具，不再封死元工具 → user 侧条件豁免才能生效；关时豁免为空串、零提及、零幻觉）。
--   3. p4 输出步(8012_p4/8008_p4/8015_p4/8016_p4)【不动】：输出步无豁免、应直接作答，保留"禁止调用任何工具"。
--      request_tool 在 DB 里本就没有，无需删。
--
-- 幂等：REPLACE 找不到旧串即 no-op；先做 2 条 surgical（消除其内的"禁止调用任何工具"），再做 16 条范围替换，互不重叠。
-- 改 DB 提示后需重启应用。

SET NAMES utf8mb4;

-- ===== auto 需求分析步 8012_p1：删 ask_user 硬编码 =====
UPDATE ai_client_system_prompt
SET prompt_content = REPLACE(prompt_content,
  '禁止调用任何工具（ask_user 除外——ask_user 是向用户澄清的手段，不视为执行工具）；当你缺少完成任务所必需的关键信息、或对用户意图存在多种合理理解需用户拍板时，应调用 ask_user，把所有需要澄清的问题一次性问全。除 ask_user 外即使你认为需要工具也不要调用，更不要假装调用工具或编造工具返回结果，只输出文字分析。',
  '禁止调用业务/执行类工具，更不要假装调用工具或编造工具返回结果，只输出文字分析。')
WHERE prompt_id = '8012_p1';

-- ===== flow 工具能力分析步 8013_p1：删 ask_user 硬编码 =====
UPDATE ai_client_system_prompt
SET prompt_content = REPLACE(prompt_content,
  '禁止调用任何工具（ask_user 除外——ask_user 是向用户澄清的手段，不视为执行工具）；当你缺少完成任务所必需的关键信息、或对用户意图存在多种合理理解需用户拍板时，应调用 ask_user，把所有需要澄清的问题一次性问全。除 ask_user 外不要调用，也不要假装调用工具或编造工具返回结果，只输出文字分析。',
  '禁止调用业务/执行类工具，也不要假装调用工具或编造工具返回结果，只输出文字分析。')
WHERE prompt_id = '8013_p1';

-- ===== 其余 16 条"有条件豁免的非执行步"：禁止任何工具 → 禁止业务/执行类工具 =====
-- 全部 p1(分析，除上面两条已处理) + flow p2(规划) + auto p3(质检)。p4 输出步不在此列、保持原样。
UPDATE ai_client_system_prompt
SET prompt_content = REPLACE(prompt_content, '禁止调用任何工具', '禁止调用业务/执行类工具')
WHERE prompt_id IN ('8006_p1','8007_p1','8008_p1','8009_p1','8010_p1','8015_p1','8016_p1',
                    '8013_p2','8009_p2','8010_p2',
                    '8012_p3','8006_p3','8007_p3','8008_p3','8015_p3','8016_p3');
