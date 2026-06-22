package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 步骤2：执行步骤规划节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/25 10:30
 */
@Slf4j
@Service
public class Step2PlanningNode extends AbstractExecuteSupport {

     @Resource
     private Step3ParseStepsNode step3ParseStepsNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        // 立即回答：跳过规划，直接跳 Step4 整合作答
        String __finalize = checkFinalizeRoute(requestParameter, dynamicContext);
        if (__finalize != null) return __finalize;
        log.info("\n--- 步骤2: 执行步骤规划 ---");

        // 获取配置信息
        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.PLANNING_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new BizException("flow agent missing flow config: " + AiClientTypeEnumVO.PLANNING_CLIENT.getCode()
                    + " for agentId=" + requestParameter.getAiAgentId());
        }

        // 获取规划客户端
        ChatClient planningChatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());
        List<ToolCallback> dynamicToolCallbacks = resolveAgentDynamicToolCallbacks(requestParameter, aiAgentClientFlowConfigVO.getClientId());
        AiAgentClientFlowConfigVO executorConfig = dynamicContext.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode());
        String executorClientId = executorConfig != null ? executorConfig.getClientId() : aiAgentClientFlowConfigVO.getClientId();
        List<ToolCallback> executorDynamicToolCallbacks = resolveAgentDynamicToolCallbacks(requestParameter, executorClientId);
        cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog v2Catalog =
                storeExecutorToolCatalogSnapshot(dynamicContext, FLOW_TOOL_CATALOG_V2_KEY, executorClientId, executorDynamicToolCallbacks, 2);
        List<String> v1ToV2Drift = compareExecutorToolCatalogLineage(dynamicContext, FLOW_TOOL_CATALOG_V1_KEY, v2Catalog);
        if (!v1ToV2Drift.isEmpty()) {
            log.warn("[ExecutorToolCatalog] V1->V2 lineage drift: {}", v1ToV2Drift);
        }

        String mcpToolsAnalysis = dynamicContext.getValue("mcpToolsAnalysis");
        // 引导感知：本步 prompt 包成 Supplier，userRequest 每轮实时取 currentTask（引导后已折入引导，修复"重跑仍用原始询问"）
        final String mcpToolsAnalysisForPrompt = mcpToolsAnalysis;
        final String toolRuntimeForPrompt = renderToolRuntimeForPrompt(v2Catalog, executorClientId);
        java.util.function.Supplier<String> planningPromptSupplier = () -> {
            String planningPrompt = buildStructuredPlanningPrompt(dynamicContext.getCurrentTask(), mcpToolsAnalysisForPrompt, toolRuntimeForPrompt);
            String refined = planningPrompt + "\n\n## ⚠️ 工具映射验证反馈\n" +
                    "\n\n**请根据上述验证反馈重新生成规划，确保：**\n" +
                    "1. 只使用验证报告中列出的有效工具\n" +
                    "2. 工具名称必须完全匹配（区分大小写）\n" +
                    "3. 每个步骤明确指定使用的MCP工具\n" +
                    "4. 避免使用不存在或无效的工具";
            // 规划阶段属非执行步，但若发现工具不够可经豁免提示主动 request_tool 预装给后续执行步（功能关则空串、零注入）。
            // 注意：要真正生效，规划客户端的 DB 系统提示里"禁止调用任何工具"需改成"禁止调用业务/执行类工具"，
            // 否则 system 的硬禁止会压过本 user 豁免，模型把调用写成文本（说而不做）。见 8013_p2。
            return appendCurrentTimeContext(refined + metaToolPromptHint(
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_STEP2_PLANNING,
                    requestParameter.getSessionId()));
        };

        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"步骤规划 已完成"）
        org.springframework.ai.openai.OpenAiChatOptions.Builder step2OptionsBuilder =
                org.springframework.ai.openai.OpenAiChatOptions.builder();
        if (step2MaxTokens > 0) {
            step2OptionsBuilder.maxTokens(step2MaxTokens);
        }
        if (!dynamicToolCallbacks.isEmpty()) {
            step2OptionsBuilder.toolCallbacks(toRequestToolCallbacks(aiAgentClientFlowConfigVO.getClientId(), dynamicToolCallbacks));
        }
        final org.springframework.ai.openai.OpenAiChatOptions step2Opts = step2OptionsBuilder.build();
        final ChatClient step2Client = planningChatClient;
        // 引导回复：被打断则折入新想法重做本步（思考不关、工具不变）
        String planningResult = callStepWithSteer(
                p -> step2Client.prompt().user(p).options(step2Opts),
                dynamicContext, "flow_step2_planning", "步骤规划",
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_STEP2_PLANNING,
                planningPromptSupplier, requestParameter.getSessionId());
        
        log.info("执行步骤规划结果: {}", planningResult);

        // P2.7 16.2：发送 thinking 事件展示规划结果
        sendThinkingEvent(dynamicContext, "步骤规划", planningResult, requestParameter.getSessionId());

        // 保存规划结果到上下文
        dynamicContext.setValue("planningResult", planningResult);
        // P1.2.2：旁路镜像（flow 路径）
        mirrorToWorkingMemory(requestParameter.getSessionId(), "flow.step2.planningResult", planningResult);
        
        // 发送SSE结果
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createAnalysisSubResult(
                dynamicContext.getStep(), 
                "analysis_strategy", 
                planningResult, 
                requestParameter.getSessionId());
        sendSseResult(dynamicContext, result);
        
        // 更新步骤
        dynamicContext.setStep(dynamicContext.getStep() + 1);

        recordTransition("flow_step2_planning", dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 构建结构化的规划提示词
     */
    private String buildStructuredPlanningPrompt(String userRequest, String mcpToolsAnalysis, String toolRuntime) {
        StringBuilder prompt = new StringBuilder();

        // 1. 任务分析部分 - 通用化用户需求分析
        prompt.append("# 智能执行计划生成\n\n");
        prompt.append("## 📋 用户需求分析\n");
        prompt.append("**完整用户请求：**\n");
        prompt.append("```\n");
        prompt.append(userRequest);
        prompt.append("\n```\n\n");
        prompt.append("**⚠️ 重要提醒：** 在生成执行计划时，必须完整保留和传递用户请求中的所有详细信息，包括但不限于：\n");
        prompt.append("- 任务的具体目标和期望结果\n");
        prompt.append("- 涉及的数据、参数、配置等详细信息\n");
        prompt.append("- 特定的业务规则、约束条件或要求\n");
        prompt.append("- 输出格式、质量标准或验收条件\n");
        prompt.append("- 时间要求、优先级或其他执行约束\n\n");

        // 2. 工具能力分析
        prompt.append("## 🔧 MCP工具能力分析结果\n");
        prompt.append(mcpToolsAnalysis).append("\n\n");
        prompt.append("## 🧰 实际可执行工具目录（含参数 schema）\n");
        prompt.append(toolRuntime).append("\n\n");

        // 3. 工具映射验证 - 使用动态获取的工具信息
        prompt.append("## ✅ 工具映射验证要求\n");
        prompt.append("**重要提醒：** 在生成执行步骤时，必须严格遵循以下工具映射规则：\n\n");

        prompt.append("### 可用工具范围\n");
        prompt.append("- 只能使用上方 MCP 分析结果中明确列为可用/有效的工具。\n");
        prompt.append("- 禁止编造工具名、服务名、发布目的地、通知渠道或平台动作。\n");
        prompt.append("- 除非用户明确要求发布、分享或通知，否则不要规划发布/分享/通知步骤。\n");
        prompt.append("- 除非工具明确可用且用户明确要求相关动作，否则不要使用 BaiduSearch、CSDN、Weixin/Wechat 或任何平台写操作工具。\n");
        prompt.append("- 如果没有合适的外部搜索工具，请规划基于已有知识的回答，或只使用当前 Agent 自身领域工具。\n\n");

        prompt.append("### 工具选择原则\n");
        prompt.append("- **精确匹配**: 每个步骤的首选工具和可替代工具都必须使用上述工具清单中的确切函数名称\n");
        prompt.append("- **功能对应**: 根据MCP工具分析结果中的匹配度选择最适合的首选工具，并列出能补齐缺失字段的替代工具\n");
        prompt.append("- **目标优先**: 工具是完成步骤目标的手段；如果首选工具返回字段不足，执行阶段可以改用可替代工具补足\n");
        prompt.append("- **参数完整**: 确保每个工具调用都包含必需的参数说明\n");
        prompt.append("- **依赖关系**: 考虑工具间的数据流转和依赖关系\n\n");

        // 4. 执行计划要求
        prompt.append("## 📝 执行计划要求\n");
        prompt.append("请基于上述用户详细需求、MCP工具分析结果和工具映射验证要求，生成精确的执行计划：\n\n");
        prompt.append("### 核心要求\n");
        prompt.append("1. **完整保留用户需求**: 必须将用户请求中的所有详细信息完整传递到每个执行步骤中\n");
        prompt.append("2. **严格遵循MCP分析结果**: 必须根据工具能力分析中的匹配度和推荐方案制定步骤\n");
        prompt.append("3. **精确工具映射**: 每个步骤必须使用确切的函数名称，不允许使用模糊或错误的工具名\n");
        prompt.append("4. **参数完整性**: 所有工具调用必须包含用户原始需求中的完整参数信息\n");
        prompt.append("5. **依赖关系明确**: 基于MCP分析结果中的执行策略建议安排步骤顺序\n");
        prompt.append("6. **合理粒度**: 避免过度细分，每个步骤应该是完整且独立的功能单元\n\n");

        // 4. 格式规范 - 通用化任务格式
        prompt.append("### 格式规范\n");
        prompt.append("请使用以下Markdown格式生成3-5个执行步骤：\n");
        prompt.append("```markdown\n");
        prompt.append("# 执行步骤规划\n\n");
        prompt.append("[ ] 第1步：[步骤描述]\n");
        prompt.append("[ ] 第2步：[步骤描述]\n");
        prompt.append("[ ] 第3步：[步骤描述]\n");
        prompt.append("...\n\n");
        prompt.append("## 步骤详情\n\n");
        prompt.append("### 第1步：[步骤描述]\n");
        prompt.append("- **优先级**: [HIGH/MEDIUM/LOW]\n");
        prompt.append("- **预估时长**: [分钟数]分钟\n");
        prompt.append("- **目标能力**: [本步骤真正要获取/处理/生成的能力目标，例如“获取景点POI、地址、坐标”]\n");
        prompt.append("- **首选工具**: [优先尝试的工具函数名称；这是建议，不是唯一允许工具]\n");
        prompt.append("- **可替代工具**: [当首选工具字段不足/失败/空结果时可补足目标的工具函数名称；没有则填“无”]\n");
        prompt.append("- **工具匹配度**: [引用MCP分析结果中的匹配度评估]\n");
        // 2026-05-07：DAG 解析依赖此格式，必须严格按指定写法（不要自由发挥）
        prompt.append("- **依赖步骤**: [严格按以下唯一格式之一填写，不要加其他文字 / 不要写\"第X步\" / 不要写\"以及/和/与\"]\n");
        prompt.append("    - 无依赖时填：`DEPENDS_ON: NONE`\n");
        prompt.append("    - 有依赖时填：`DEPENDS_ON: 1` 或 `DEPENDS_ON: 1,2,3`（纯数字 + 半角逗号，禁止其他写法）\n");
        prompt.append("- **执行方法**: [基于MCP分析结果的具体执行策略，包含首选工具参数、失败/缺字段时如何用可替代工具补足]\n");
        prompt.append("- **工具参数**: [详细的参数说明和示例值，必须包含用户原始需求中的所有相关信息]\n");
        prompt.append("- **需求传递**: [明确说明如何将用户的详细要求传递到此步骤中]\n");
        prompt.append("- **预期输出**: [期望的最终结果]\n");
        prompt.append("- **成功标准**: [判断任务完成的标准]\n");
        prompt.append("- **MCP分析依据**: [引用具体的MCP工具分析结论]\n\n");
        prompt.append("```\n\n");

        // 5. 动态规划指导原则
        prompt.append("### 规划指导原则\n");
        prompt.append("请根据用户详细请求和可用工具能力，动态生成合适的执行步骤：\n");
        prompt.append("- **需求完整性原则**: 确保用户请求中的所有详细信息都被完整保留和传递\n");
        prompt.append("- **步骤分离原则**: 每个步骤应该专注于单一功能，避免混合不同类型的操作\n");
        prompt.append("- **工具映射原则**: 每个步骤应明确目标能力、首选工具和可替代工具；不要把首选工具写成唯一硬约束\n");
        prompt.append("- **参数传递原则**: 确保用户的详细要求能够准确传递到工具参数中\n");
        prompt.append("- **依赖关系原则**: 合理安排步骤顺序，确保前置条件得到满足\n");
        prompt.append("- **结果输出原则**: 每个步骤都应有明确的输出结果和成功标准\n\n");

        // 6. 步骤类型指导
        prompt.append("### 步骤类型指导\n");
        prompt.append("根据可用工具和用户需求，常见的步骤类型包括：\n");
        prompt.append("- **数据获取步骤**: 使用搜索、查询等工具获取所需信息\n");
        prompt.append("- **数据处理步骤**: 对获取的信息进行分析、整理和加工\n");
        prompt.append("- **内容生成步骤**: 基于处理后的数据生成目标内容\n");
        prompt.append("- **结果输出步骤**: 默认将生成的内容直接返回给用户；只有用户明确要求时才发布或保存到外部平台\n");
        prompt.append("- **通知反馈步骤**: 只有用户明确要求通知相关方时，才发送执行结果通知\n\n");
        prompt.append("Hard constraint: publish/share/notify/platform-write steps are forbidden unless explicitly requested by the user and the corresponding tool is listed as available in the MCP analysis result.\n\n");

        // 7. 执行要求
        prompt.append("### 执行要求\n");
        prompt.append("1. **步骤编号**: 使用第1步、第2步、第3步...格式\n");
        prompt.append("2. **Markdown格式**: 严格按照上述Markdown格式输出\n");
        prompt.append("3. **步骤描述**: 每个步骤描述要清晰、具体、可执行\n");
        prompt.append("4. **优先级**: 根据步骤重要性和紧急程度设定\n");
        prompt.append("5. **时长估算**: 基于步骤复杂度合理估算\n");
        prompt.append("6. **工具选择**: 从可用工具中选择最适合的首选工具，并尽量给出可替代工具；必须使用完整的函数名称\n");
        prompt.append("7. **依赖关系（关键！系统按此解析 DAG）**: 每个步骤的【依赖步骤】行必须**严格**按以下机器可读格式：\n");
        prompt.append("    - `DEPENDS_ON: NONE` （无依赖）\n");
        prompt.append("    - `DEPENDS_ON: 1`（依赖第1步）\n");
        prompt.append("    - `DEPENDS_ON: 1,2,3`（依赖第1、2、3步，纯数字 + 半角英文逗号 + 无空格）\n");
        prompt.append("    - **禁止**写成 `第1步`/`第1,2步`/`步骤1, 步骤2`/`1和2`/`无` 等其他写法\n");
        prompt.append("    - **禁止**在 DEPENDS_ON 行附加其他描述性文字（不要解释、不要写理由）\n");
        prompt.append("8. **执行细节**: 提供具体可操作的方法，包含详细的参数说明和用户需求传递\n");
        prompt.append("9. **需求传递**: 确保用户的所有详细要求都能准确传递到相应的执行步骤中\n");
        prompt.append("10. **功能独立**: 确保每个步骤功能独立，避免混合不同类型的操作\n");
        prompt.append("11. **工具映射**: 每个步骤必须明确指定目标能力、首选工具、可替代工具；首选工具是建议，不是执行阶段唯一允许工具\n");
        prompt.append("12. **质量标准**: 设定明确的完成标准\n\n");

        // 7. 步骤类型指导
        prompt.append("### 常见步骤类型指导\n");
        prompt.append("- **信息获取步骤**: 使用搜索工具，关注关键词选择和结果筛选\n");
        prompt.append("- **内容处理步骤**: 基于获取的信息进行分析、整理和创作\n");
        prompt.append("- **结果输出步骤**: 默认直接返回给用户；只有用户明确要求发布或保存时，才使用对应平台工具\n");
        prompt.append("- **通知反馈步骤**: 只有用户明确要求通知相关方时，才使用通信工具进行状态通知或结果反馈\n");
        prompt.append("- **数据处理步骤**: 对获取的信息进行分析、转换和处理\n\n");

        // 8. 质量检查
        prompt.append("### 质量检查清单\n");
        prompt.append("生成计划后请确认：\n");
        prompt.append("- [ ] 每个步骤都有明确的序号和描述\n");
        prompt.append("- [ ] 使用了正确的Markdown格式\n");
        prompt.append("- [ ] 步骤描述清晰具体\n");
        prompt.append("- [ ] 优先级设置合理\n");
        prompt.append("- [ ] 时长估算现实可行\n");
        prompt.append("- [ ] 工具选择恰当\n");
        prompt.append("- [ ] 依赖关系清晰\n");
        prompt.append("- [ ] 执行方法具体可操作\n");
        prompt.append("- [ ] 成功标准明确可衡量\n\n");

        prompt.append("现在请开始生成Markdown格式的执行步骤规划：\n");

        return prompt.toString();
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return step3ParseStepsNode;
    }

}
