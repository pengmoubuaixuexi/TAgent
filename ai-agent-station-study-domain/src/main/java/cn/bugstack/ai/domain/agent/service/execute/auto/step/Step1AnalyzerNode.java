package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

/**
 * 任务分析节点
 *
 * @author TAgent
 * 2025/7/27 16:36
 */
@Slf4j
@Service
public class Step1AnalyzerNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        log.info("\n🎯 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 获取配置信息
        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new BizException("auto agent missing flow config: " + AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode()
                    + " for agentId=" + requestParameter.getAiAgentId());
        }

        // 第一阶段：任务分析
        log.info("\n📊 阶段1: 任务状态分析");
        String analysisPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                requestParameter.getMessage(),
                dynamicContext.getStep(),
                dynamicContext.getMaxStep(),
                !dynamicContext.getExecutionHistory().isEmpty() ? dynamicContext.getExecutionHistory().toString() : "[首次执行]",
                dynamicContext.getCurrentTask()
        );
        analysisPrompt = analysisPrompt + githubRepositorySearchGuidance();

        ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());

        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"需求分析 已完成"）
        ChatClient.ChatClientRequestSpec spec1 = chatClient
                .prompt(analysisPrompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                        .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(requestParameter, "auto-step1-analysis"))
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024));
        if (step1MaxTokens > 0) spec1 = spec1.options(ChatOptions.builder().maxTokens(step1MaxTokens).build());
        String analysisResult = callStepWithStreaming(
                spec1, dynamicContext, "step1_analyzer", "需求分析", analysisPrompt, requestParameter.getSessionId());

        if (analysisResult == null) throw new BizException("step1: analysisResult is null", "LLM returned null for Step1AnalyzerNode");
        // P2.7 16.2：发送 thinking 事件展示中间推理
        sendThinkingEvent(dynamicContext, "任务分析", analysisResult, requestParameter.getSessionId());
        parseAnalysisResult(dynamicContext, analysisResult, requestParameter.getSessionId());

        // 将分析结果保存到动态上下文中，供下一步使用
        dynamicContext.setValue("analysisResult", analysisResult);
        // P1.2.2：旁路镜像到 Working Memory，按 step 序号区分历史轮（Reflexion 重做时不互覆盖）
        mirrorToWorkingMemory(requestParameter.getSessionId(),
                "step1.analysisResult." + dynamicContext.getStep(), analysisResult);

        // 检查是否已完成
        if (analysisResult.contains("任务状态: COMPLETED") ||
                analysisResult.contains("完成度评估: 100%")) {
            dynamicContext.setCompleted(true);
            log.info("✅ 任务分析显示已完成！");
            return router(requestParameter, dynamicContext);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 如果任务已完成或达到最大步数，进入总结阶段
        if (dynamicContext.isCompleted() || dynamicContext.getStep() > dynamicContext.getMaxStep()) {
            return getBean("step4LogExecutionSummaryNode");
        }
        
        // 否则继续执行下一步
        return getBean("step2PrecisionExecutorNode");
    }

    private String githubRepositorySearchGuidance() {
        return """

                [GitHub repository search guidance]
                If you need to search GitHub repositories, prefer English technical queries and GitHub qualifiers.
                Do not pass broad Chinese tutorial/resource phrases directly as the GitHub query unless the user explicitly asks to search only Chinese repositories.
                Examples: `spring-boot learning language:Java stars:>500`, `spring-boot examples language:Java stars:>500`, `spring-boot tutorial language:Java stars:>500`.
                Use page=1 and perPage<=10 for repository recommendations.
                """;
    }

    private void parseAnalysisResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String analysisResult, String sessionId) {
        int step = dynamicContext.getStep();
        log.info("\n📊 === 第 {} 步分析结果 ===", step);
        
        String[] lines = analysisResult.split("\n");
        String currentSection = "";
        StringBuilder sectionContent = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.contains("任务状态分析:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_status";
                sectionContent = new StringBuilder();
                log.info("\n🎯 任务状态分析:");
                continue;
            } else if (line.contains("执行历史评估:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_history";
                sectionContent = new StringBuilder();
                log.info("\n📈 执行历史评估:");
                continue;
            } else if (line.contains("下一步策略:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_strategy";
                sectionContent = new StringBuilder();
                log.info("\n🚀 下一步策略:");
                continue;
            } else if (line.contains("完成度评估:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_progress";
                sectionContent = new StringBuilder();
                String progress = line.substring(line.indexOf(":") + 1).trim();
                log.info("\n📊 完成度评估: {}", progress);
                sectionContent.append(line).append("\n");
                continue;
            } else if (line.contains("任务状态:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_task_status";
                sectionContent = new StringBuilder();
                String status = line.substring(line.indexOf(":") + 1).trim();
                if (status.equals("COMPLETED")) {
                    log.info("\n✅ 任务状态: 已完成");
                } else {
                    log.info("\n🔄 任务状态: 继续执行");
                }
                sectionContent.append(line).append("\n");
                continue;
            }

            // 收集当前section的内容
            if (!currentSection.isEmpty()) {
                sectionContent.append(line).append("\n");
                switch (currentSection) {
                    case "analysis_status":
                        log.info("   📋 {}", line);
                        break;
                    case "analysis_history":
                        log.info("   📊 {}", line);
                        break;
                    case "analysis_strategy":
                        log.info("   🎯 {}", line);
                        break;
                    default:
                        log.info("   📝 {}", line);
                        break;
                }
            }
        }
        
        // 发送最后一个section的内容
        sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
    }

    /**
     * 发送分析阶段细分结果到流式输出
     */
    private void sendAnalysisSubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                      String subType, String content, String sessionId) {
        if (!subType.isEmpty() && !content.isEmpty()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createAnalysisSubResult(
                    dynamicContext.getStep(), subType, content, sessionId);
            sendSseResult(dynamicContext, result);
        }
    }

}
