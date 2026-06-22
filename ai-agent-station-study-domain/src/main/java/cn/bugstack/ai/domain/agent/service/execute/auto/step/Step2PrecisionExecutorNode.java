package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.CritiqueRecord;
import cn.bugstack.ai.domain.agent.service.execute.common.CritiqueType;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 精准执行节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:42
 */
@Slf4j
@Service
public class Step2PrecisionExecutorNode extends AbstractExecuteSupport{

    @Resource
    private AgentToolRegistry agentToolRegistry;

    @Resource
    private McpToolCatalogService mcpToolCatalogService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        // 立即回答：跳过精准执行，直接汇总
        String __finalize = checkFinalizeRoute(requestParameter, dynamicContext);
        if (__finalize != null) return __finalize;
        log.info("\n⚡ 阶段2: 精准任务执行");
        
        // 从动态上下文中获取分析结果
        String analysisResult = dynamicContext.getValue("analysisResult");
        if (analysisResult == null || analysisResult.trim().isEmpty()) {
            log.warn("⚠️ 分析结果为空，使用默认执行策略");
            analysisResult = "执行当前任务步骤";
        }

        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new BizException("auto agent missing flow config: " + AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT.getCode()
                    + " for agentId=" + requestParameter.getAiAgentId());
        }

        String executionPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(), effectiveUserQuestion(requestParameter, dynamicContext), analysisResult);

        // 注入当前 agent 真实工具清单，防止 LLM 幻觉不存在的工具
        String executorClientId = aiAgentClientFlowConfigVO.getClientId();
        List<ToolCallback> dynamicToolCallbacks = mcpToolCatalogService != null
                ? mcpToolCatalogService.resolveDynamicToolCallbacks(requestParameter.getRunId(), requestParameter.getSessionId(), executorClientId,
                        mcpToolCatalogService.needsFor(requestParameter.getSessionId()), requestParameter.getMessage(),
                        agentToolRegistry != null ? agentToolRegistry.getTools(executorClientId) : List.of())
                : List.of();
        if (agentToolRegistry != null && executorClientId != null) {
            executionPrompt += "\n\n**【可用工具清单】**\n" + agentToolRegistry.describeToolsForPrompt(executorClientId, dynamicToolCallbacks);
        }

        // P1.2 / T11 C Reflexion：上一轮 Step3 评审 FAIL 把 critique 喂回来，前置到 prompt 让模型针对反馈修正
        String critique = dynamicContext.getValue(Step3QualitySupervisorNode.CTX_REFLEXION_CRITIQUE);
        Object critiqueRecordValue = dynamicContext.getDataObjects().get(Step3QualitySupervisorNode.CTX_REFLEXION_CRITIQUE_RECORD);
        CritiqueRecord critiqueRecord = critiqueRecordValue instanceof CritiqueRecord record ? record : null;
        String reflexionFeedback = buildReflexionFeedback(critiqueRecord, critique);
        if (hasText(reflexionFeedback)) {
            executionPrompt = "【Reflexion - 上一次执行未通过质量检查，请针对以下反馈修正后重新执行】\n"
                    + reflexionFeedback
                    + "\n\n--------\n原任务上下文：\n"
                    + executionPrompt;
            log.info("🔁 Reflexion: 注入 critique 到 Step2 prompt [structured={}]",
                    critiqueRecord != null && critiqueRecord.getType() != null && critiqueRecord.getType() != CritiqueType.OTHER);
            // 取走就清掉，避免下一轮还带着；下一轮 Step3 失败会重新塞
            dynamicContext.setValue(Step3QualitySupervisorNode.CTX_REFLEXION_CRITIQUE, null);
            dynamicContext.setValue(Step3QualitySupervisorNode.CTX_REFLEXION_CRITIQUE_RECORD, null);
        }

        // 获取对话客户端
        ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());

        // lambda 需要 effectively final，前面有可能被 reflexion 改写过；这里复制一份
        final String finalPrompt = appendCurrentTimeContext(executionPrompt);
        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"精准执行 已完成"）
        // 必须用 OpenAiChatOptions（而非 ChatOptions / 泛化 ToolCallingChatOptions）：
        // 1) DefaultChatClientUtils 仅在 options instanceof ToolCallingChatOptions 时才 setToolContext；
        // 2) OpenAiChatModel.buildRequestPrompt 的 toolContext merge 把 runtime 强转 OpenAiChatOptions，
        //    非 OpenAiChatOptions 会走 else 分支只取默认（空）toolContext → 把 callStepWithStreaming 注入的
        //    sessionId/stepLabel 丢掉 → 工具线程拿不到 → 审批门 APPROVAL_UNAVAILABLE。两关都要过，只有 OpenAiChatOptions 满足。
        org.springframework.ai.openai.OpenAiChatOptions.Builder step2OptionsBuilder =
                org.springframework.ai.openai.OpenAiChatOptions.builder();
        if (step2MaxTokens > 0) {
            step2OptionsBuilder.maxTokens(step2MaxTokens);
        }
        if (!dynamicToolCallbacks.isEmpty()) {
            step2OptionsBuilder.toolCallbacks(toRequestToolCallbacks(executorClientId, dynamicToolCallbacks));
        }
        final ChatClient step2Client = chatClient;
        final org.springframework.ai.openai.OpenAiChatOptions step2Opts = step2OptionsBuilder.build();
        // 引导回复：被打断则折入新想法重做本步（思考不关、工具不变）
        // auto Step2 的 prompt 用 message+analysisResult(不读 currentTask)，故 basePrompt 是常量 supplier；
        // RAG query 用 steerAware，让 step1 引导经 currentTask 也能影响本步检索
        String executionResult = callStepWithSteer(
                p -> step2Client.prompt(p)
                        .advisors(a -> a
                                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                                .param(LTM_RETRIEVAL_QUERY_KEY, steerAwareRetrievalQuery(dynamicContext, requestParameter))
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024))
                        .options(step2Opts),
                dynamicContext, "step2_precision_executor", "精准执行",
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.AUTO_STEP2_EXECUTION,
                () -> finalPrompt, requestParameter.getSessionId());

        if (executionResult == null) throw new BizException("step2: executionResult is null", "LLM returned null for Step2PrecisionExecutorNode");
        // P2.7 16.2：发送 thinking 事件展示执行推理
        sendThinkingEvent(dynamicContext, "精确执行", executionResult, requestParameter.getSessionId());
        parseExecutionResult(dynamicContext, executionResult, requestParameter.getSessionId());

        // 将执行结果保存到动态上下文中，供下一步使用
        dynamicContext.setValue("executionResult", executionResult);
        // P1.2.2：旁路镜像
        mirrorToWorkingMemory(requestParameter.getSessionId(),
                "step2.executionResult." + dynamicContext.getStep(), executionResult);
        
        // 更新执行历史
        String stepSummary = String.format("""
                === 第 %d 步执行记录 ===
                【分析阶段】%s
                【执行阶段】%s
                """, dynamicContext.getStep(), analysisResult, executionResult);
        
        dynamicContext.getExecutionHistory().append(stepSummary);

        recordTransition("step2_precision_executor", dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("step3QualitySupervisorNode");
    }
    
    static String buildReflexionFeedback(CritiqueRecord record, String critique) {
        if (record != null && record.getType() != null && record.getType() != CritiqueType.OTHER) {
            return """
                    【结构化质量反馈】
                    问题类型: %s
                    证据: %s
                    修复建议: %s
                    修复策略: %s
                    """.formatted(
                    record.getType().name(),
                    hasText(record.getEvidence()) ? record.getEvidence() : "未提供",
                    hasText(record.getSuggestion()) ? record.getSuggestion() : "未提供",
                    strategyHint(record.getType())
            ).trim();
        }
        if (record != null && hasText(record.getRawText())) {
            return record.getRawText();
        }
        return hasText(critique) ? critique : null;
    }

    private static String strategyHint(CritiqueType type) {
        return switch (type) {
            case MISSING_TOOL_CALL -> "优先补齐必要工具调用；如果确实不能调用工具，必须明确说明原因。";
            case WRONG_PARAM -> "修正工具参数名、类型、取值范围和必填项，避免重复提交错误参数。";
            case LOGIC_INCONSISTENT -> "重排推理链，先对齐上下文和工具结果，再生成结论。";
            case HALLUCINATION -> "删除无证据内容，只基于上下文、长期记忆和工具返回结果回答。";
            case OTHER -> "按质量反馈逐项修正，保留可验证依据。";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void parseExecutionResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String executionResult, String sessionId) {
        int step = dynamicContext.getStep();
        log.info("\n⚡ === 第 {} 步执行结果 ===", step);
        
        String[] lines = executionResult.split("\n");
        String currentSection = "";
        StringBuilder sectionContent = new StringBuilder();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            if (line.contains("执行目标:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_target";
                sectionContent = new StringBuilder();
                log.info("\n🎯 执行目标:");
                continue;
            } else if (line.contains("执行过程:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_process";
                sectionContent = new StringBuilder();
                log.info("\n🔧 执行过程:");
                continue;
            } else if (line.contains("执行结果:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_result";
                sectionContent = new StringBuilder();
                log.info("\n📈 执行结果:");
                continue;
            } else if (line.contains("质量检查:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_quality";
                sectionContent = new StringBuilder();
                log.info("\n🔍 质量检查:");
                continue;
            }
            
            // 收集当前section的内容
            if (!currentSection.isEmpty()) {
                sectionContent.append(line).append("\n");
                switch (currentSection) {
                    case "execution_target":
                        log.info("   🎯 {}", line);
                        break;
                    case "execution_process":
                        log.info("   ⚙️ {}", line);
                        break;
                    case "execution_result":
                        log.info("   📊 {}", line);
                        break;
                    case "execution_quality":
                        log.info("   ✅ {}", line);
                        break;
                    default:
                        log.info("   📝 {}", line);
                        break;
                }
            }
        }
        
        // 发送最后一个section的内容
        sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
    }
    
    /**
     * 发送执行阶段细分结果到流式输出
     */
    private void sendExecutionSubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                       String subType, String content, String sessionId) {
        // 抽取的通用判断逻辑
        if (!subType.isEmpty() && !content.isEmpty()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createExecutionSubResult(
                    dynamicContext.getStep(), subType, content, sessionId);
            sendSseResult(dynamicContext, result);
        }
    }
    
}
