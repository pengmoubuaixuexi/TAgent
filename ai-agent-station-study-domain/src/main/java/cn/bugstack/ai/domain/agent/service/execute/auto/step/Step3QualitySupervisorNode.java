package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.CritiqueParser;
import cn.bugstack.ai.domain.agent.service.execute.common.CritiqueRecord;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * 质量监督节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
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

    /** dynamicContext key：累计重试次数 */
    private static final String CTX_REFLEXION_RETRIES = "reflexionRetries";
    /** dynamicContext key：上次评审给的 critique（Step2 读取并融入 prompt） */
    public static final String CTX_REFLEXION_CRITIQUE = "reflexionCritique";
    /**
     * T11 B：上次评审给的结构化 critique（{@link CritiqueRecord}）。
     * <p>
     * 与 {@link #CTX_REFLEXION_CRITIQUE} 并存——前者是原始字符串（Step2 当前路径），后者是
     * {@link CritiqueParser#parse(String)} 结果。Step2（C 阶段）可优先消费结构化版本，
     * 解析失败/缺失时回退到字符串路径。**这条数据只增强不打断**：解析失败 record.type=OTHER + rawText=原文。
     */
    public static final String CTX_REFLEXION_CRITIQUE_RECORD = "reflexionCritiqueRecord";

    /**
     * T11 B：追加给 Step3 模型的"结构化 critique 输出"指令。
     * <p>
     * 不动 DB 中的 stepPrompt 配置，仅在代码层追加。原有五段中文（质量评估/问题识别/改进建议/质量评分/是否通过）
     * 完全保留，Step2 字符串路径不受影响；末尾追加 JSON 给 {@link CritiqueParser} 解析。
     * <p>
     * 仅在 {@code reflexionEnabled=true} 且模型可能进入 FAIL 分支时才需要——但为简化逻辑，
     * 只要 reflexion 开关开就追加，PASS 路径多输出几十字节不影响成本。
     */
    private static final String CRITIQUE_JSON_HINT =
            "\n\n【额外输出要求 - Reflexion 结构化】\n" +
            "如果判定为 FAIL，请在上述五段评审文字末尾追加一行 markdown JSON 代码块，格式：\n" +
            "```json\n" +
            "{\"type\":\"<MISSING_TOOL_CALL|WRONG_PARAM|LOGIC_INCONSISTENT|HALLUCINATION|OTHER>\"," +
            "\"evidence\":\"<引用执行结果中的具体片段说明为什么这么判>\"," +
            "\"suggestion\":\"<给下一轮 Step2 的可执行修复指示，不要重复原错误>\"}\n" +
            "```\n" +
            "PASS / OPTIMIZE 时无需输出 JSON。";

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        checkCancelled(dynamicContext);
        // 立即回答：跳过质量监督，直接汇总
        String __finalize = checkFinalizeRoute(requestParameter, dynamicContext);
        if (__finalize != null) return __finalize;
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

        // 质检步元工具豁免（条件生效）。同规划步：若该步 DB 系统提示也有"禁止调用任何工具"，需同样改为
        // "禁止调用业务/执行类工具"才能让 request_tool 真触发，否则模型会"说而不做"。
        String supervisionPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(), effectiveUserQuestion(requestParameter, dynamicContext), executionResult) + metaToolPromptHint(requestParameter.getSessionId());

        // T11 B：reflexion 开关开时追加结构化 critique JSON 输出要求（不动 DB stepPrompt）
        if (reflexionEnabled) {
            supervisionPrompt = supervisionPrompt + CRITIQUE_JSON_HINT;
        }
        supervisionPrompt = appendCurrentTimeContext(supervisionPrompt);

        // 获取对话客户端：直接用 QUALITY_SUPERVISOR_CLIENT 的 DB 配置。
        // 2026-05-29：移除运行期 tier 覆盖（getChatClientByTier）。Actor-Critic 的模型分家改由 DB 控制——
        // 给 QUALITY_SUPERVISOR_CLIENT 配一个与 step2 actor 不同的 model 即可。
        ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());
        List<ToolCallback> dynamicToolCallbacks = resolveAgentDynamicToolCallbacks(requestParameter, aiAgentClientFlowConfigVO.getClientId());

        // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"质量评审 已完成"）
        ChatClient.ChatClientRequestSpec spec3 = chatClient
                .prompt(supervisionPrompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                        .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(requestParameter, "auto-step3-quality-check"))
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024));
        org.springframework.ai.openai.OpenAiChatOptions.Builder step3OptionsBuilder =
                org.springframework.ai.openai.OpenAiChatOptions.builder();
        if (step3MaxTokens > 0) {
            step3OptionsBuilder.maxTokens(step3MaxTokens);
        }
        if (!dynamicToolCallbacks.isEmpty()) {
            step3OptionsBuilder.toolCallbacks(toRequestToolCallbacks(aiAgentClientFlowConfigVO.getClientId(), dynamicToolCallbacks));
        }
        spec3 = spec3.options(step3OptionsBuilder.build());
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
        }

        // T11 B（修正 Codex 第 35 轮指出的 OPTIMIZE 状态污染）：
        // 唯一写入 reflexion 状态的路径：FAIL 且未超 max-retries。其余所有路径（PASS / OPTIMIZE /
        // 达上限 / reflexion 关闭）统一 clear，避免 C 阶段 Step2 读到上一轮残留的 record。
        boolean reflexionTriggered = false;
        if (reflexionEnabled && failed) {
            Integer retries = dynamicContext.getValue(CTX_REFLEXION_RETRIES);
            int n = retries == null ? 0 : retries;
            if (n < reflexionMaxRetries) {
                // 双写：原字符串 critique 给 Step2 现有路径；结构化 record 给 C 阶段消费
                dynamicContext.setValue(CTX_REFLEXION_CRITIQUE, supervisionResult);
                CritiqueRecord record = CritiqueParser.parse(supervisionResult);
                dynamicContext.setValue(CTX_REFLEXION_CRITIQUE_RECORD, record);
                dynamicContext.setValue(CTX_REFLEXION_RETRIES, n + 1);
                log.info("🔁 Reflexion 触发：第 {} 次重试，回到 Step2 [critique.type={}, hasRawText={}]",
                        n + 1, record.getType(), record.getRawText() != null);
                reflexionTriggered = true;
            } else {
                log.info("🔁 Reflexion 已达最大重试次数 {}，放弃，回到 Step1", reflexionMaxRetries);
            }
        }
        if (!reflexionTriggered) {
            // 集中清理：所有"不写入新 reflexion 状态"的路径走这里，保证 ctx 不携带旧 record/critique
            dynamicContext.setValue(CTX_REFLEXION_CRITIQUE, null);
            dynamicContext.setValue(CTX_REFLEXION_CRITIQUE_RECORD, null);
            dynamicContext.setValue(CTX_REFLEXION_RETRIES, 0);
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
            recordTransition("step3_quality_supervisor", dynamicContext);
            return router(requestParameter, dynamicContext);
        }

        // 否则继续下一轮执行，返回到Step1AnalyzerNode
        recordTransition("step3_quality_supervisor", dynamicContext);
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
