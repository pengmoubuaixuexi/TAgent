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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务分析节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:36
 */
@Slf4j
@Service
public class Step1AnalyzerNode extends AbstractExecuteSupport {

    /** P0-B2a：完成判定 shadow 指标采集；test seam 直接 new 节点时字段自然为 null。 */
    @org.springframework.beans.factory.annotation.Autowired
    private cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics autoAgentMetrics;

    /**
     * P0-B2a shadow 契约尾注（代码注入，不写 DB）。要求模型在全文最末输出旧 parser 看不见的 HTML 注释机器字段，
     * 供 {@link cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector} 旁路解析。
     * 旧 {@code contains("任务状态: COMPLETED"/"完成度评估: 100%")} 看不见这些 key → 真 shadow，旧控制流不变。
     */
    private static final String SHADOW_COMPLETION_TRAILER_HINT =
            "\n\n【机器可读标记 - 必填】在全文最末另起两行，各输出且仅输出一行如下 HTML 注释（系统采集用，不要做展示性描述）：\n" +
            "<!-- AUTO_COMPLETION_PROGRESS: N% -->\n" +
            "<!-- AUTO_COMPLETION_STATUS: STATUS -->\n" +
            "必须保留 <!-- 和 --> 注释定界符；只把 N 替换为 0 到 100 的整数，把 STATUS 替换为 CONTINUE 或 COMPLETED（只能选一个、大写）。不要原样输出 N/STATUS，不要添加竖线、代码围栏或其他机器字段。";

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        // 立即回答：跳过分析，直接汇总
        String __finalize = checkFinalizeRoute(requestParameter, dynamicContext);
        if (__finalize != null) return __finalize;
        log.info("\n🎯 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 获取配置信息
        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new BizException("auto agent missing flow config: " + AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode()
                    + " for agentId=" + requestParameter.getAiAgentId());
        }

        // 第一阶段：任务分析
        log.info("\n📊 阶段1: 任务状态分析");
        // 引导感知：本步 prompt 包成 Supplier，每轮按最新 currentTask(%5) 重建（引导后 currentTask 已折入引导）
        final AiAgentClientFlowConfigVO step1Config = aiAgentClientFlowConfigVO;
        java.util.function.Supplier<String> analysisPromptSupplier = () -> appendCurrentTimeContext(String.format(step1Config.getStepPrompt(),
                effectiveUserQuestionForStep(requestParameter, dynamicContext, 1),
                dynamicContext.getStep(),
                dynamicContext.getMaxStep(),
                !dynamicContext.getExecutionHistory().isEmpty() ? dynamicContext.getExecutionHistory().toString() : "[首次执行]",
                dynamicContext.getCurrentTask()
        ) + metaToolPromptHint(cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.AUTO_STEP1_ANALYSIS,
                requestParameter.getSessionId()))
                // P0-B2a：shadow 契约尾注置于全文最末（晚于时间块），旧 parser 看不见
                + SHADOW_COMPLETION_TRAILER_HINT;

        ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());
        List<ToolCallback> dynamicToolCallbacks = resolveAgentDynamicToolCallbacks(requestParameter, aiAgentClientFlowConfigVO.getClientId());

        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"需求分析 已完成"）
        org.springframework.ai.openai.OpenAiChatOptions.Builder step1OptionsBuilder =
                org.springframework.ai.openai.OpenAiChatOptions.builder();
        if (step1MaxTokens > 0) {
            step1OptionsBuilder.maxTokens(step1MaxTokens);
        }
        if (!dynamicToolCallbacks.isEmpty()) {
            step1OptionsBuilder.toolCallbacks(toRequestToolCallbacks(aiAgentClientFlowConfigVO.getClientId(), dynamicToolCallbacks));
        }
        final ChatClient step1Client = chatClient;
        final org.springframework.ai.openai.OpenAiChatOptions step1Opts = step1OptionsBuilder.build();
        // 引导回复：被打断则折入新想法重做本步（思考不关、工具不变）；不触发时等价于单发流式
        String analysisResult = callStepWithSteer(
                p -> step1Client.prompt(p)
                        .advisors(a -> a
                                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                                .param(LTM_RETRIEVAL_QUERY_KEY, steerAwareRetrievalQuery(dynamicContext, requestParameter))
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024))
                        .options(step1Opts),
                dynamicContext, "step1_analyzer", "需求分析",
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.AUTO_STEP1_ANALYSIS,
                analysisPromptSupplier, requestParameter.getSessionId());

        if (analysisResult == null) throw new BizException("step1: analysisResult is null", "LLM returned null for Step1AnalyzerNode");
        // P0-B2a：剥离 shadow 尾注得到业务文本；rawResult 仅供 shadow 解析，businessResult 驱动全部旧逻辑（不改控制流）
        final String rawResult = analysisResult;
        final String businessResult = cn.bugstack.ai.domain.agent.service.execute.common.ShadowContractTrailer.strip(rawResult);
        // P2.7 16.2：发送 thinking 事件展示中间推理
        sendThinkingEvent(dynamicContext, "任务分析", businessResult, requestParameter.getSessionId());
        parseAnalysisResult(dynamicContext, businessResult, requestParameter.getSessionId());

        // 将分析结果保存到动态上下文中，供下一步使用
        dynamicContext.setValue("analysisResult", businessResult);
        // P1.2.2：旁路镜像到 Working Memory，按 step 序号区分历史轮（Reflexion 重做时不互覆盖）
        mirrorToWorkingMemory(requestParameter.getSessionId(),
                "step1.analysisResult." + dynamicContext.getStep(), businessResult);

        // 检查是否已完成（旧控制流必须读取 rawResult，保证即使畸形 AUTO_* value 内含旧 marker 也与改造前完全等价）
        boolean legacyCompleted = rawResult.contains("任务状态: COMPLETED") ||
                rawResult.contains("完成度评估: 100%");
        // P0-B2b-O1 shadow：inspect() 给出 field/prose/candidate-source/unknown_reason；legacy 标签取真实旧分支布尔（§43 约束 3）。只打点、不改路由。
        if (autoAgentMetrics != null) {
            cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.Inspection insp =
                    cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.inspect(rawResult);
            autoAgentMetrics.recordAnalysisCompletion(insp.resolvedSignal().name());
            autoAgentMetrics.recordContractShadow("step1",
                    legacyCompleted ? "completed" : "continue", insp.resolvedSignal().name());
            autoAgentMetrics.recordAnalysisCandidateSource(
                    cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.candidateSource(insp));
            autoAgentMetrics.recordFieldVsProse("step1",
                    cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.fieldVsProse(insp));
            if (insp.resolvedSignal() == cn.bugstack.ai.domain.agent.service.execute.common.AnalysisCompletionDetector.Signal.UNKNOWN) {
                autoAgentMetrics.recordUnknownReason("step1", insp.unknownReason());
            }
        }

        // 检查是否已完成
        if (legacyCompleted) {
            dynamicContext.setCompleted(true);
            log.info("✅ 任务分析显示已完成！");
            recordTransition("step1_analyzer", dynamicContext);
            return router(requestParameter, dynamicContext);
        }

        recordTransition("step1_analyzer", dynamicContext);
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
