package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 步骤1：MCP工具能力分析节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/25 09:56
 */
@Slf4j
@Service
public class Step1McpToolsAnalysisNode extends AbstractExecuteSupport {

    @Resource
    private Step2PlanningNode step2PlanningNode;

    /** 2026-05-07：注入真实工具列表 */
    @Resource
    private AgentToolRegistry agentToolRegistry;

    @Resource
    private McpToolCatalogService mcpToolCatalogService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        // 立即回答：跳过分析，直接跳 Step4 整合作答
        String __finalize = checkFinalizeRoute(requestParameter, dynamicContext);
        if (__finalize != null) return __finalize;
        log.info("\n--- 步骤1: MCP工具能力分析（仅分析阶段，不执行用户请求） ---");

        // 获取配置信息
        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.TOOL_MCP_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new BizException("flow agent missing flow config: " + AiClientTypeEnumVO.TOOL_MCP_CLIENT.getCode()
                    + " for agentId=" + requestParameter.getAiAgentId());
        }

        // 获取MCP工具分析客户端
        ChatClient mcpToolsChatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());

        // 2026-05-07：取出 executor client 真实注册的工具列表（Step4 才是真正执行工具的客户端）
        // 找不到 EXECUTOR_CLIENT 配置就退化用当前分析 client 自己的工具集
        AiAgentClientFlowConfigVO executorConfig = dynamicContext.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode());
        String executorClientId = executorConfig != null ? executorConfig.getClientId() : aiAgentClientFlowConfigVO.getClientId();
        List<ToolCallback> dynamicToolCallbacks = mcpToolCatalogService.resolveDynamicToolCallbacks(executorClientId,
                mcpToolCatalogService.needsFor(requestParameter.getSessionId()), requestParameter.getMessage(),
                agentToolRegistry != null ? agentToolRegistry.getTools(executorClientId) : List.of());
        String toolListBlock = agentToolRegistry.describeToolsForPrompt(executorClientId, dynamicToolCallbacks);
        log.info("[Step1] inject tools for executor clientId={} hasTools={}",
                executorClientId, agentToolRegistry.hasAnyTools(executorClientId));

        // 引导感知：本步 prompt 包成 Supplier，每轮按最新 currentTask 重建（引导后 currentTask 已折入引导，修复"重跑仍用原始询问"）
        final String toolListBlockForPrompt = toolListBlock;
        java.util.function.Supplier<String> mcpAnalysisPromptSupplier = () -> appendCurrentTimeContext(String.format(
                """
                        # MCP工具能力分析任务

                        ## 重要说明
                        **本阶段仅进行工具能力分析，不执行用户的实际请求。**

                        ## 实际可用工具（必读）
                        %s

                        ## 用户请求
                        %s

                        ## 分析要求
                        基于上面【实际可用工具】列表（这是当前 Agent 真实装配，不要超出此范围）+ 用户请求，给出工具能力分析：

                        ### 1. 任务匹配度
                        - 用户请求属于什么类别（信息检索 / 内容生成 / 计算 / 工具操作 / 纯对话 等）
                        - 上面列出的工具中，哪些能直接满足？哪些不能？匹配度（高/中/低）

                        ### 2. 工具使用建议（仅针对已列出的工具）
                        - 给出**真实存在**的工具的调用方式、参数提示
                        - **严禁**引用上面列表里没有的工具（如自己脑补 web_search / summarize / run_code 等）

                        ### 3. 降级策略
                        - 如果实际工具不能完成需求，应该如何基于 LLM 自身知识给出合理回复
                        - 哪些信息能直接给（基础知识），哪些必须告知用户"无法检索/无法执行"

                        ### 4. 后续规划建议
                        - 建议规划阶段（Step2）只规划"使用上面列出的真实工具"或"纯知识回答"两种路径
                        - 提醒执行阶段（Step4）：禁止虚构工具调用过程

                        请基于上面给出的真实工具列表进行分析，禁止编造工具。""",
                toolListBlockForPrompt,
                dynamicContext.getCurrentTask()
        ) + metaToolPromptHint(requestParameter.getSessionId()));

        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"MCP 工具分析 已完成"）
        org.springframework.ai.openai.OpenAiChatOptions.Builder step1OptionsBuilder =
                org.springframework.ai.openai.OpenAiChatOptions.builder();
        if (step1MaxTokens > 0) {
            step1OptionsBuilder.maxTokens(step1MaxTokens);
        }
        if (!dynamicToolCallbacks.isEmpty()) {
            step1OptionsBuilder.toolCallbacks(toRequestToolCallbacks(executorClientId, dynamicToolCallbacks));
        }
        final org.springframework.ai.openai.OpenAiChatOptions step1Opts = step1OptionsBuilder.build();
        final ChatClient step1Client = mcpToolsChatClient;
        // V041：把干净的用户问题传给 RAG/LTM advisor，避免它们拿整段工程化 prompt(工具目录+分析模板)做路由/检索
        // 引导回复：被打断则折入新想法重做本步（思考不关、工具不变）；basePrompt 用 Supplier 每轮重建，
        // RAG/LTM query 用 steerAwareRetrievalQuery 每轮实时取 currentTask（引导后即按新任务检索）
        String mcpToolsAnalysis = callStepWithSteer(
                p -> step1Client.prompt().user(p)
                        .options(step1Opts)
                        .advisors(a -> a.param(LTM_RETRIEVAL_QUERY_KEY, steerAwareRetrievalQuery(dynamicContext, requestParameter))),
                dynamicContext, "flow_step1_mcp_tools_analysis", "MCP 工具分析",
                mcpAnalysisPromptSupplier, requestParameter.getSessionId());
        
        log.info("MCP工具分析结果（仅分析，未执行实际操作）: {}", mcpToolsAnalysis);

        // P2.7 16.2：发送 thinking 事件展示工具分析
        sendThinkingEvent(dynamicContext, "工具分析", mcpToolsAnalysis, requestParameter.getSessionId());

        // 保存分析结果到上下文
        dynamicContext.setValue("mcpToolsAnalysis", mcpToolsAnalysis);
        // P1.2.2：旁路镜像到 Working Memory（flow 路径）
        mirrorToWorkingMemory(requestParameter.getSessionId(), "flow.step1.mcpToolsAnalysis", mcpToolsAnalysis);
        
        // 发送SSE结果
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createAnalysisSubResult(
                dynamicContext.getStep(), 
                "analysis_tools", 
                mcpToolsAnalysis, 
                requestParameter.getSessionId());
        sendSseResult(dynamicContext, result);
        
        // 更新步骤
        dynamicContext.setStep(dynamicContext.getStep() + 1);

        recordTransition("flow_step1_mcp_analysis", dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return step2PlanningNode;
    }

}
