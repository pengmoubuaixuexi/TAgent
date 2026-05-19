package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 质量监督节点
 *
 * @author TAgent
 * 2025/7/27 16:43
 */
@Slf4j
@Service
public class Step3QualitySupervisorNode extends AbstractExecuteSupport {

    /** P1.2 Reflexion 自反思回退总开关 */
    @Value("${agent.reflexion.enabled:false}")
    private boolean reflexionEnabled;

    /** Reflexion 最大重试次数（防死循环） */
    @Value("${agent.reflexion.max-retries:2}")
    private int reflexionMaxRetries;

    /** P2.2 11.3 Actor-Critic：Step3 Critic 用独立模型 tier 避免自评偏见 */
    @Value("${agent.actor-critic.enabled:false}")
    private boolean actorCriticEnabled;

    @Value("${agent.actor-critic.critic-tier:medium}")
    private String criticTier;

    /** dynamicContext key：累计重试次数 */
    private static final String CTX_REFLEXION_RETRIES = "reflexionRetries";
    /** dynamicContext key：上次评审给的 critique（Step2 读取并融入 prompt） */
    public static final String CTX_REFLEXION_CRITIQUE = "reflexionCritique";

    /** 2026-05-07：Step3 critic 注入 LTM 用户事实，避免把基于历史记忆的个性化判定为"编造" */
    @Autowired(required = false)
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        // 第三阶段：质量监督
        log.info("\n🔍 阶段3: 质量监督检查");
        
        // 从动态上下文中获取执行结果
        String executionResult = dynamicContext.getValue("executionResult");
        if (executionResult == null || executionResult.trim().isEmpty()) {
            log.warn("⚠️ 执行结果为空，跳过质量监督");
            return "质量监督跳过";
        }

        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.QUALITY_SUPERVISOR_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new BizException("auto agent missing flow config: " + AiClientTypeEnumVO.QUALITY_SUPERVISOR_CLIENT.getCode()
                    + " for agentId=" + requestParameter.getAiAgentId());
        }

        String supervisionPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(), requestParameter.getMessage(), executionResult);

        // 2026-05-07：注入 LTM 用户事实给 critic，告诉它"这些是已知的真实用户信息，不是 Step2 编的"
        // 修复症状：Step3 看不到历史 → 把 Step2 基于 LTM 的个性化（"张伟/Java后端"）误判为编造
        String userFactsBlock = "";
        if (longTermMemoryService != null) {
            try {
                String ltmUserId = buildConversationId(requestParameter); // 与 LTM advisor 用同样的 userId 维度
                if (requestParameter.getUserId() != null && !requestParameter.getUserId().isBlank()) {
                    ltmUserId = requestParameter.getUserId();
                }
                List<String> facts = longTermMemoryService.retrieveForInjection(
                        ltmUserId, buildLtmRetrievalQuery(requestParameter, "auto-step3-quality-check"), 5, 5);
                if (facts != null && !facts.isEmpty()) {
                    StringBuilder sb = new StringBuilder("\n\n【已知用户事实 — 来自长期记忆，非编造】\n");
                    for (String f : facts) sb.append("- ").append(f).append("\n");
                    sb.append("（评审时如执行结果引用了上述事实，不要判定为编造；只判定**未在已知事实范围内的新信息**才算编造）\n");
                    userFactsBlock = sb.toString();
                    log.info("[Step3-LTM] injected {} user facts into critic prompt", facts.size());
                }
            } catch (Exception e) {
                log.warn("[Step3-LTM] failed to inject user facts: {}", e.getMessage());
            }
        }
        supervisionPrompt = supervisionPrompt + userFactsBlock;

        // 获取对话客户端
        ChatClient chatClient;
        if (actorCriticEnabled && criticTier != null && !criticTier.isBlank()) {
            chatClient = getChatClientByTier(ModelTierEnumVO.fromCode(criticTier), aiAgentClientFlowConfigVO.getClientId());
            log.info("[Actor-Critic] Step3 using critic-tier={} fallback={}", criticTier, aiAgentClientFlowConfigVO.getClientId());
        } else {
            chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());
        }

        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"质量评审 已完成"）
        ChatClient.ChatClientRequestSpec spec3 = chatClient
                .prompt(supervisionPrompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                        .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(requestParameter, "auto-step3-quality-check"))
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024));
        if (step3MaxTokens > 0) spec3 = spec3.options(ChatOptions.builder().maxTokens(step3MaxTokens).build());
        String supervisionResult = callStepWithStreaming(
                spec3, dynamicContext, "step3_quality_supervisor", "质量评审", supervisionPrompt, requestParameter.getSessionId());

        if (supervisionResult == null) throw new BizException("step3: supervisionResult is null", "LLM returned null for Step3QualitySupervisorNode");
        parseSupervisionResult(dynamicContext, supervisionResult, requestParameter.getSessionId());

        // 将监督结果保存到动态上下文中
        dynamicContext.setValue("supervisionResult", supervisionResult);
        // P1.2.2：旁路镜像
        mirrorToWorkingMemory(requestParameter.getSessionId(),
                "step3.supervisionResult." + dynamicContext.getStep(), supervisionResult);
        
        // 根据监督结果决定是否需要重新执行
        boolean failed = supervisionResult.contains("是否通过: FAIL");
        boolean optimize = supervisionResult.contains("是否通过: OPTIMIZE");

        if (failed) {
            log.info("❌ 质量检查未通过，需要重新执行");
            dynamicContext.setCurrentTask("根据质量监督的建议重新执行任务");
        } else if (optimize) {
            log.info("🔧 质量检查建议优化，继续改进");
            dynamicContext.setCurrentTask("根据质量监督的建议优化执行结果");
        } else {
            log.info("✅ 质量检查通过");
            dynamicContext.setCompleted(true);
            // 通过即清空 reflexion 状态，避免影响后续 step
            dynamicContext.setValue(CTX_REFLEXION_CRITIQUE, null);
            dynamicContext.setValue(CTX_REFLEXION_RETRIES, 0);
        }

        // P1.2 Reflexion：FAIL 时把 critique 喂回 Step2，不进 Step1 重头来
        if (reflexionEnabled && failed) {
            Integer retries = dynamicContext.getValue(CTX_REFLEXION_RETRIES);
            int n = retries == null ? 0 : retries;
            if (n < reflexionMaxRetries) {
                dynamicContext.setValue(CTX_REFLEXION_CRITIQUE, supervisionResult);
                dynamicContext.setValue(CTX_REFLEXION_RETRIES, n + 1);
                log.info("🔁 Reflexion 触发：第 {} 次重试，回到 Step2", n + 1);
            } else {
                log.info("🔁 Reflexion 已达最大重试次数 {}，放弃，回到 Step1", reflexionMaxRetries);
                dynamicContext.setValue(CTX_REFLEXION_CRITIQUE, null);
                dynamicContext.setValue(CTX_REFLEXION_RETRIES, 0);
            }
        }
        
        // 更新执行历史
        String stepSummary = String.format("""
                === 第 %d 步完整记录 ===
                【分析阶段】%s
                【执行阶段】%s
                【监督阶段】%s
                """, dynamicContext.getStep(), 
                dynamicContext.getValue("analysisResult"), 
                executionResult, 
                supervisionResult);
        
        dynamicContext.getExecutionHistory().append(stepSummary);
        
        // 增加步骤计数
        dynamicContext.setStep(dynamicContext.getStep() + 1);
        
        // 如果任务已完成或达到最大步数，进入总结阶段
        if (dynamicContext.isCompleted() || dynamicContext.getStep() > dynamicContext.getMaxStep()) {
            return router(requestParameter, dynamicContext);
        }
        
        // 否则继续下一轮执行，返回到Step1AnalyzerNode
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 如果任务已完成或达到最大步数，进入总结阶段
        if (dynamicContext.isCompleted() || dynamicContext.getStep() > dynamicContext.getMaxStep()) {
            return getBean("step4LogExecutionSummaryNode");
        }

        // P1.2 Reflexion：critique 还在说明上一步 doApply 已决定要回 Step2
        Object critique = dynamicContext.getValue(CTX_REFLEXION_CRITIQUE);
        if (reflexionEnabled && critique != null) {
            return getBean("step2PrecisionExecutorNode");
        }

        // 否则返回到 Step1AnalyzerNode 进行下一轮分析
        return getBean("step1AnalyzerNode");
    }
    
    /**
     * 解析监督结果
     */
    private void parseSupervisionResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String supervisionResult, String sessionId) {
        int step = dynamicContext.getStep();
        log.info("\n🔍 === 第 {} 步监督结果 ===", step);
        
        String[] lines = supervisionResult.split("\n");
        String currentSection = "";
        StringBuilder sectionContent = new StringBuilder();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            if (line.contains("质量评估:")) {
                // 发送前一个部分的内容
                sendSupervisionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "assessment";
                sectionContent.setLength(0);
                log.info("\n📊 质量评估:");
                continue;
            } else if (line.contains("问题识别:")) {
                // 发送前一个部分的内容
                sendSupervisionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "issues";
                sectionContent.setLength(0);
                log.info("\n⚠️ 问题识别:");
                continue;
            } else if (line.contains("改进建议:")) {
                // 发送前一个部分的内容
                sendSupervisionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "suggestions";
                sectionContent.setLength(0);
                log.info("\n💡 改进建议:");
                continue;
            } else if (line.contains("质量评分:")) {
                // 发送前一个部分的内容
                sendSupervisionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "score";
                sectionContent.setLength(0);
                String score = line.substring(line.indexOf(":") + 1).trim();
                log.info("\n📊 质量评分: {}", score);
                sectionContent.append(score);
                continue;
            } else if (line.contains("是否通过:")) {
                // 发送前一个部分的内容
                sendSupervisionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "pass";
                sectionContent.setLength(0);
                String status = line.substring(line.indexOf(":") + 1).trim();
                if (status.equals("PASS")) {
                    log.info("\n✅ 检查结果: 通过");
                } else if (status.equals("FAIL")) {
                    log.info("\n❌ 检查结果: 未通过");
                } else {
                    log.info("\n🔧 检查结果: 需要优化");
                }
                sectionContent.append(status);
                continue;
            }
            
            // 收集当前部分的内容
            if (!currentSection.isEmpty()) {
                if (!sectionContent.isEmpty()) {
                    sectionContent.append("\n");
                }
                sectionContent.append(line);
            }
            
            switch (currentSection) {
                case "assessment":
                    log.info("   📋 {}", line);
                    break;
                case "issues":
                    log.info("   ⚠️ {}", line);
                    break;
                case "suggestions":
                    log.info("   💡 {}", line);
                    break;
                default:
                    log.info("   📝 {}", line);
                    break;
            }
        }
        
        // 发送最后一个部分的内容
        sendSupervisionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
        
        // 发送完整的监督结果
        sendSupervisionResult(dynamicContext, supervisionResult, sessionId);
    }
    
    /**
     * 发送监督结果到流式输出
     */
    private void sendSupervisionResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                     String supervisionResult, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSupervisionResult(
                dynamicContext.getStep(), supervisionResult, sessionId);
        sendSseResult(dynamicContext, result);
    }
    
    /**
     * 发送监督子结果到流式输出（细粒度标识）
     */
    private void sendSupervisionSubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                        String section, String content, String sessionId) {
        // 抽取的通用判断逻辑
        if (!content.isEmpty() && !section.isEmpty()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSupervisionSubResult(
                    dynamicContext.getStep(), section, content, sessionId);
            sendSseResult(dynamicContext, result);
        }
    }

}
