package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 精准执行节点
 *
 * @author TAgent
 * 2025/7/27 16:42
 */
@Slf4j
@Service
public class Step2PrecisionExecutorNode extends AbstractExecuteSupport{

    @Resource
    private AgentToolRegistry agentToolRegistry;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
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

        String executionPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(), requestParameter.getMessage(), analysisResult)
                + githubRepositorySearchGuidance();

        // 注入当前 agent 真实工具清单，防止 LLM 幻觉不存在的工具
        String executorClientId = aiAgentClientFlowConfigVO.getClientId();
        if (agentToolRegistry != null && executorClientId != null) {
            executionPrompt += "\n\n**【可用工具清单】**\n" + agentToolRegistry.describeToolsForPrompt(executorClientId);
        }

        // P1.2 Reflexion：上一轮 Step3 评审 FAIL 把 critique 喂回来，前置到 prompt 让模型针对反馈修正
        String critique = dynamicContext.getValue(Step3QualitySupervisorNode.CTX_REFLEXION_CRITIQUE);
        if (critique != null && !critique.isBlank()) {
            executionPrompt = "【Reflexion - 上一次执行未通过质量检查，请针对以下反馈修正后重新执行】\n"
                    + critique
                    + "\n\n--------\n原任务上下文：\n"
                    + executionPrompt;
            log.info("🔁 Reflexion: 注入 critique 到 Step2 prompt");
            // 取走就清掉，避免下一轮还带着；下一轮 Step3 失败会重新塞
            dynamicContext.setValue(Step3QualitySupervisorNode.CTX_REFLEXION_CRITIQUE, null);
        }

        // 获取对话客户端
        ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());

        // lambda 需要 effectively final，前面有可能被 reflexion 改写过；这里复制一份
        final String finalPrompt = executionPrompt;
        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"精准执行 已完成"）
        ChatClient.ChatClientRequestSpec spec2 = chatClient
                .prompt(finalPrompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                        .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(requestParameter, "auto-step2-execution"))
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024));
        if (step2MaxTokens > 0) spec2 = spec2.options(ChatOptions.builder().maxTokens(step2MaxTokens).build());
        String executionResult = callStepWithStreaming(
                spec2, dynamicContext, "step2_precision_executor", "精准执行", finalPrompt, requestParameter.getSessionId());

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

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("step3QualitySupervisorNode");
    }
    
    /**
     * 解析执行结果
     */
    private String githubRepositorySearchGuidance() {
        return """

                [GitHub repository search guidance]
                If you need to search GitHub repositories, prefer English technical queries and GitHub qualifiers.
                Do not pass broad Chinese tutorial/resource phrases directly as the GitHub query unless the user explicitly asks to search only Chinese repositories.
                Examples: `spring-boot learning language:Java stars:>500`, `spring-boot examples language:Java stars:>500`, `spring-boot tutorial language:Java stars:>500`.
                Use page=1 and perPage<=10 for repository recommendations.
                """;
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
