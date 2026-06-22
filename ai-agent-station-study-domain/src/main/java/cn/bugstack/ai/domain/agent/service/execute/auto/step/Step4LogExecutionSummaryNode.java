package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.IConversationTurnMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 执行总结节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:45
 */
@Slf4j
@Service
public class Step4LogExecutionSummaryNode extends AbstractExecuteSupport {

    /** P2.1 Episodic Memory；为 null 时跳过保存 */
    @Autowired(required = false)
    private IEpisodicMemoryService episodicMemoryService;

    @Autowired(required = false)
    private IConversationTurnMemoryService conversationTurnMemoryService;

    /** 2026-05-08：流式适配。advisor.after 在 stream 模式下拿不到 ChatResponse output，节点级直触发 LTM 抽取 */
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService longTermMemoryService;

    /** 立即回答可观测：打断时写一条 ai_event_log 标记行（即使 finalize LLM 失败也留痕）。 */
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.IEventLogService eventLogService;

    /** 摘要触发阈值：10 轮 = 20 条消息 */
    private static final int EPISODIC_SUMMARY_THRESHOLD = 20;
    /** 节流间隔：每新增 4 条消息（= 2 轮）触发一次 */
    private static final int EPISODIC_THROTTLE_INTERVAL = 4;

    /** 立即回答：finalize 是否允许调工具（默认关，求"立即"）。 */
    @org.springframework.beans.factory.annotation.Value("${agent.answer-now.finalize-tools:false}")
    private boolean answerNowFinalizeTools;

    /** 立即回答：关思考指令（model-agnostic 提示词；可填 /no_think 等 provider token）。单行，代码侧补换行。 */
    @org.springframework.beans.factory.annotation.Value("${agent.answer-now.no-think-directive:[立即作答模式] 用户已要求立即回答，请直接基于以上已有信息给出最终答案，不要再展开额外的思考或分析过程，简明扼要。}")
    private String answerNowNoThinkDirective;

    /** 立即回答 finalize 关思考：OpenAI reasoning_effort（minimal/low/medium/high）；空=不设。设到 OpenAiChatOptions，由 Spring AI 确定性序列化进 body。 */
    @org.springframework.beans.factory.annotation.Value("${agent.no-think.reasoning-effort:}")
    private String answerNowReasoningEffort;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n📊 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 第四阶段：执行总结
        log.info("\n📊 阶段4: 执行总结分析");
        
        // 记录执行总结
        logExecutionSummary(dynamicContext.getMaxStep(), dynamicContext.getExecutionHistory(), dynamicContext.isCompleted());
        
        // 生成最终总结报告（无论任务是否完成都需要生成）
        generateFinalReport(requestParameter, dynamicContext);

        // P0-B2b-Step3：质量交付终态每请求仅在 Step4 记一次（含 not_assessed）
        if (autoAgentMetrics != null) {
            cn.bugstack.ai.domain.agent.model.valobj.enums.QualityVerificationStatus __qvs = dynamicContext.getQualityVerificationStatus();
            autoAgentMetrics.recordQualityTerminal(__qvs == null ? "not_assessed" : __qvs.name());
        }
        
        log.info("\n🏁 === 动态多轮执行结束 ====");

        recordTransition("step4_log_execution_summary", dynamicContext);
        return "ai agent execution summary completed!";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 总结节点是最后一个节点，返回null表示执行结束
        return defaultStrategyHandler;
    }
    
    /**
     * 记录执行总结
     */
    private void logExecutionSummary(int maxSteps, StringBuilder executionHistory, boolean isCompleted) {
        log.info("\n📊 === 动态多轮执行总结 ====");
        
        int actualSteps = Math.min(maxSteps, executionHistory.toString().split("=== 第").length - 1);
        log.info("📈 总执行步数: {} 步", actualSteps);
        
        if (isCompleted) {
            log.info("✅ 任务完成状态: 已完成");
        } else {
            log.info("⏸️ 任务完成状态: 未完成（达到最大步数限制）");
        }
        
        // 计算执行效率
        double efficiency = isCompleted ? 100.0 : (double) actualSteps / maxSteps * 100;
        log.info("📊 执行效率: {}%", efficiency);
    }
    
    /**
     * 生成最终总结报告
     */
    protected void generateFinalReport(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            boolean isCompleted = dynamicContext.isCompleted();
            boolean answerNow = dynamicContext.isFinalizeRequested();
            log.info("\n--- 生成{}任务的最终答案{} ---", isCompleted ? "已完成" : "未完成", answerNow ? "（立即回答）" : "");

            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());
            if (aiAgentClientFlowConfigVO == null) {
                throw new BizException("auto agent missing flow config: " + AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode()
                        + " for agentId=" + requestParameter.getAiAgentId());
            }

            // 工具：常驻 + 路由补充（既用于挂回调，也用于注入 prompt 工具清单）
            String clientId = aiAgentClientFlowConfigVO.getClientId();
            List<ToolCallback> dynamicToolCallbacks = resolveAgentDynamicToolCallbacks(requestParameter, clientId);
            boolean attachTools = !dynamicToolCallbacks.isEmpty() && (!answerNow || answerNowFinalizeTools);

            // 立即回答：用半成品上下文（含半截思考）拼专用 prompt + 关思考指令；否则走原 completed/incomplete 提示词。
            String summaryPrompt = answerNow
                    ? buildAnswerNowPrompt(requestParameter, dynamicContext) + "\n\n" + answerNowNoThinkDirective
                    : getSummaryPrompt(aiAgentClientFlowConfigVO, requestParameter, dynamicContext, isCompleted);
            // 立即回答且挂工具：把常驻+补充工具清单注入 prompt（与正常执行步一致，帮模型选对工具、防幻觉）
            if (answerNow && attachTools && dynamicAgentToolRegistry != null) {
                summaryPrompt += "\n\n**【可用工具清单】**\n"
                        + dynamicAgentToolRegistry.describeToolsForPrompt(clientId, dynamicToolCallbacks)
                        + "\n如需补足信息可调用上述工具后再作答；不需要则直接作答。";
            }
            summaryPrompt = appendCurrentTimeContext(summaryPrompt);
            // P0-B2b-Step3：质量未通过/未验证时，要求最终交付向用户明示局限，且不泄露内部流程
            summaryPrompt += qualityDeliveryDirective(dynamicContext.getQualityVerificationStatus());

            // 立即回答可观测：在 LLM 调用前写一条标记行，记录"中断点 + 改写后的完整 finalize 输入"，LLM 失败也留痕。
            if (answerNow) {
                logAnswerNowMarker(requestParameter, dynamicContext, summaryPrompt);
            }

            // 获取对话客户端 - 使用任务分析客户端进行总结
            ChatClient chatClient = getChatClientByClientId(clientId);

            ChatClient.ChatClientRequestSpec spec0 = chatClient
                    .prompt(summaryPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                            .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(requestParameter, "auto-step4-final-summary"))
                            .param("memory_persist_final_turn", true)
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50));
            org.springframework.ai.openai.OpenAiChatOptions.Builder step4OptionsBuilder =
                    org.springframework.ai.openai.OpenAiChatOptions.builder();
            if (step4MaxTokens > 0) {
                step4OptionsBuilder.maxTokens(step4MaxTokens);
            }
            // 挂工具回调（常驻+补充）：finalize-tools 开 + 该 agent 原有工具时
            if (attachTools) {
                step4OptionsBuilder.toolCallbacks(toRequestToolCallbacks(clientId, dynamicToolCallbacks));
            }
            // 立即回答关思考：reasoning_effort 设到 options，由 Spring AI 序列化进 body（确定性，绕开 filter 跨线程）。
            if (answerNow && answerNowReasoningEffort != null && !answerNowReasoningEffort.isBlank()) {
                step4OptionsBuilder.reasoningEffort(answerNowReasoningEffort);
            }
            ChatClient.ChatClientRequestSpec streamSpec = spec0.options(step4OptionsBuilder.build());
            // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"最终总结 已完成"）
            String summaryResult = callStepWithStreaming(
                    streamSpec, dynamicContext, answerNow ? "step4_answer_now" : "step4_summary", "最终总结",
                    answerNow
                            ? cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.answerNow(attachTools)
                            : cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.AUTO_STEP4_SUMMARY,
                    summaryPrompt, requestParameter.getSessionId());

            if (summaryResult == null) throw new BizException("step4: summaryResult is null", "LLM returned null for Step4LogExecutionSummaryNode");
            // P2.5 14.2 PII 脱敏：仅在最终输出给用户时脱敏
            summaryResult = OutputFilter.cleanForUser(cn.bugstack.ai.domain.agent.service.security.PiiMasker.mask(summaryResult));
            // P0-B2b-Step3：结构门控兜底。模型即使忽略软 directive，异常质量终态也必须确定性告知用户。
            // 固定文案不含内部枚举/prompt/error；方法幂等，且只在 Step4 最终出口执行一次。
            summaryResult = applyQualityDeliveryNotice(dynamicContext.getQualityVerificationStatus(), summaryResult);
            logFinalReport(dynamicContext, summaryResult, requestParameter.getSessionId());

            // 将总结结果保存到动态上下文中
            dynamicContext.setValue("finalSummary", summaryResult);
            if (conversationTurnMemoryService != null) {
                conversationTurnMemoryService.saveFinalTurn(requestParameter, summaryResult);
            }
            // P1.2.2：把最终答案也镜像一份；TTL 内可读，便于客户端 SSE 断线重连快速回放完成态
            mirrorToWorkingMemory(requestParameter.getSessionId(), "step4.finalSummary", summaryResult);
            mirrorToWorkingMemory(requestParameter.getSessionId(), "step4.completed", Boolean.TRUE);

            // 2026-05-08：流式模式下 advisor.after 拿不到完整 assistantText，节点级直触发 LTM 抽取
            triggerLongTermMemoryExtraction(requestParameter, summaryResult);

            // P2.1 Episodic Memory：渐进式摘要（与 Fixed 逻辑一致：首次 ≥20，之后每 4 条节流）
            saveEpisodicMemory(requestParameter, summaryResult, dynamicContext);

        } catch (Exception e) {
            log.error("生成最终总结报告时出现异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 2026-05-08：流式聚合后触发 LTM 事实抽取，绕开 advisor.after 在 stream 模式下拿不到 output 的限制。
     * 仅在 agent.token-streaming.enabled=true 时由节点接管；非流式下让 advisor.after 自己处理避免重复。
     */
    private void triggerLongTermMemoryExtraction(ExecuteCommandEntity req, String assistantText) {
        if (longTermMemoryService == null) return;
        if (!tokenStreamingEnabled) return;
        if (assistantText == null || assistantText.isBlank()) return;
        String userId = req.getUserId() != null ? req.getUserId() : MDC.get("userId");
        if (userId == null || userId.isBlank()) return;
        String tenantId = req.getTenantId() != null ? req.getTenantId() : MDC.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";

        org.springframework.ai.chat.client.ChatClient extractionClient;
        try {
            extractionClient = applicationContext.getBean(
                    cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.AI_CLIENT.getBeanName("router-small"),
                    org.springframework.ai.chat.client.ChatClient.class);
        } catch (Exception e) {
            log.warn("[AutoLTM] router-small not found, skip extraction: {}", e.getMessage());
            return;
        }
        cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor
                .triggerExtractionAsync(longTermMemoryService, extractionClient,
                        req.getMessage(), assistantText,
                        userId, tenantId, req.getSessionId(), req.getAiAgentId());
    }

    /**
     * 立即回答可观测：写一条 ai_event_log 标记行（stepName=answer_now_triggered），记录中断点 + 改写后的完整 finalize 输入。
     * 独立于 finalize LLM 调用：即使后续 LLM 失败也能在 event_log 看到"打断发生了 + 输入改成了啥"。
     */
    private void logAnswerNowMarker(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx, String finalizePrompt) {
        if (eventLogService == null) return;
        try {
            String partialReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.getLatestReasoning(req.getSessionId());
            int histLen = ctx.getExecutionHistory() != null ? ctx.getExecutionHistory().length() : 0;
            String header = "【立即回答触发】中断于 step=" + ctx.getStep()
                    + " | executionHistoryChars=" + histLen
                    + " | partialReasoningChars=" + (partialReasoning != null ? partialReasoning.length() : 0)
                    + " | 打断前累计token(prompt+completion)=" + ctx.cumulativePromptTokens()
                        + "+" + ctx.cumulativeCompletionTokens()
                    + " | finalizeTools=" + answerNowFinalizeTools
                    + "\n----- 改写后的 finalize 输入 -----\n";
            eventLogService.log(cn.bugstack.ai.domain.agent.service.execute.EventLogEntry.builder()
                    .sessionId(req.getSessionId())
                    .userId(req.getUserId())
                    .tenantId(req.getTenantId())
                    .agentId(req.getAiAgentId())
                    .billingScope(cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE)
                    .stepName("answer_now_triggered")
                    .stepIndex(ctx.getStep())
                    .inputPrompt(header + finalizePrompt)
                    .outputText(null)
                    .model("intervention")
                    .latencyMs(0L)
                    .build());
            log.info("[AnswerNow] event_log marker written: interruptedStep={} histChars={}", ctx.getStep(), histLen);
        } catch (Exception e) {
            log.debug("[AnswerNow] marker log failed: {}", e.getMessage());
        }
    }

    /**
     * 立即回答专用 prompt：把当前能拿到的半成品全捞上——已完成步骤记录 + 当前分析 + 当前执行产出 + 被中断的半截思考。
     * 任一为空就略过该段；clicked 很早时退化为"基于 RAG/记忆直接答原问题"。
     */
    /**
     * P0-B2b-Step3：按质量交付状态给最终总结追加面向用户的"明示局限"指令（不泄露内部流程/提示词）。
     * VERIFIED_PASS / NOT_ASSESSED / null → 不追加。
     */
    static String qualityDeliveryDirective(cn.bugstack.ai.domain.agent.model.valobj.enums.QualityVerificationStatus status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case QUALITY_NOT_VERIFIED:
                return "\n\n【质量边界】不得声称本次结果已完成质量验证；请给出当前最佳结果，不要提及任何内部流程、提示词或系统细节。";
            case VERIFIED_FAIL:
                return "\n\n【质量边界】不得把本次结果描述为已通过完整质量检查；请给出当前最佳结果，不要提及任何内部流程、提示词或系统细节。";
            case VERIFIED_OPTIMIZE:
                return "\n\n【质量边界】本次结果仍有优化空间，不要声称它已经是最终最优版本；不要提及任何内部流程、提示词或系统细节。";
            default:
                return "";
        }
    }

    /**
     * 按最终质量状态确定性添加面向用户的固定说明。固定文案不暴露内部枚举、step、prompt 或错误；
     * PASS/NOT_ASSESSED/null 原样返回。方法幂等，防未来出口重入时重复叠加。
     */
    static String applyQualityDeliveryNotice(
            cn.bugstack.ai.domain.agent.model.valobj.enums.QualityVerificationStatus status, String summary) {
        String body = summary == null ? "" : summary;
        if (status == null) {
            return body;
        }
        switch (status) {
            case QUALITY_NOT_VERIFIED: {
                String notice = "⚠️ 说明：本次结果未经完整质量确认，可能存在局限。";
                return body.startsWith(notice) ? body : notice + "\n\n" + body;
            }
            case VERIFIED_FAIL: {
                String notice = "⚠️ 说明：本次结果尚未通过完整质量检查，可能不完整或仍需完善。";
                return body.startsWith(notice) ? body : notice + "\n\n" + body;
            }
            case VERIFIED_OPTIMIZE: {
                String notice = "提示：本次结果仍有进一步优化空间。";
                if (body.endsWith(notice)) {
                    return body;
                }
                return body.isEmpty() ? notice : body + "\n\n" + notice;
            }
            default:
                return body;
        }
    }

    private String buildAnswerNowPrompt(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户在执行过程中点击了【立即回答】，要求基于目前已有的（可能不完整的）信息立刻作答。\n\n");
        sb.append("**用户原始问题:**\n").append(effectiveUserQuestion(req, ctx)).append("\n\n");

        String history = ctx.getExecutionHistory() != null ? ctx.getExecutionHistory().toString() : "";
        if (history != null && !history.isBlank()) {
            sb.append("**已完成步骤记录:**\n").append(history).append("\n\n");
        }
        String analysis = ctx.getValue("analysisResult");
        if (analysis != null && !analysis.isBlank()) {
            sb.append("**当前分析（可能为半截）:**\n").append(analysis).append("\n\n");
        }
        String execResult = ctx.getValue("executionResult");
        if (execResult != null && !execResult.isBlank()) {
            sb.append("**当前执行产出（可能为半截）:**\n").append(execResult).append("\n\n");
        }
        String partialReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter
                .getLatestReasoning(req.getSessionId());
        if (partialReasoning != null && !partialReasoning.isBlank()) {
            String pr = partialReasoning.length() > 4000 ? partialReasoning.substring(0, 4000) + "...(截断)" : partialReasoning;
            sb.append("**你刚才正在进行的思考（被用户中断，可能为半截）:**\n").append(pr).append("\n\n");
        }

        sb.append("**要求:**\n");
        sb.append("1. 立即基于以上信息直接回答用户原始问题，给出尽可能完整、有用的答案。\n");
        sb.append("2. 信息不足的部分简要说明，不要编造。\n");
        sb.append("3. 用清晰的 Markdown 输出。\n");
        return sb.toString();
    }

    private static String getSummaryPrompt(AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO, ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, boolean isCompleted) {
        String summaryPrompt;
        if (isCompleted) {
            summaryPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                    effectiveUserQuestion(requestParameter, dynamicContext),
                    dynamicContext.getExecutionHistory().toString());
        } else {
            summaryPrompt = String.format("""
                    虽然任务未完全执行完成，但请基于已有的执行过程，尽力回答用户的原始问题：
                    
                    **用户原始问题:** %s
                    
                    **已执行的过程和获得的信息:**
                    %s
                    
                    **要求:**
                    1. 基于已有信息，尽力回答用户的原始问题
                    2. 如果信息不足，说明哪些部分无法完成并给出原因
                    3. 提供已能确定的部分答案
                    4. 给出完成剩余部分的具体建议
                    5. 以MD语法的表格形式，优化展示结果数据
                    
                    请基于现有信息给出用户问题的答案：
                    """,
                    effectiveUserQuestion(requestParameter, dynamicContext),
                    dynamicContext.getExecutionHistory().toString());
        }
        return summaryPrompt;
    }

    /**
     * 输出最终总结报告
     */
    private void logFinalReport(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String summaryResult, String sessionId) {
        boolean isCompleted = dynamicContext.isCompleted();
        log.info("\n📋 === {}任务最终总结报告 ===", isCompleted ? "已完成" : "未完成");

        String[] lines = summaryResult.split("\n");
        String currentSection = "summary_overview";
        StringBuilder sectionContent = new StringBuilder();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // 检测是否开始新的总结部分
            String newSection = detectSummarySection(line);
            if (newSection != null && !newSection.equals(currentSection)) {
                // 发送前一个部分的内容
                if (!sectionContent.isEmpty()) {
                    sendSummarySubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                }
                currentSection = newSection;
                sectionContent.setLength(0);
            }
            
            // 收集当前部分的内容
            if (!sectionContent.isEmpty()) {
                sectionContent.append("\n");
            }
            sectionContent.append(line);
            
            // 根据内容类型添加不同图标
            if (line.contains("已完成") || line.contains("完成的工作")) {
                log.info("✅ {}", line);
            } else if (line.contains("未完成") || line.contains("原因")) {
                log.info("❌ {}", line);
            } else if (line.contains("建议") || line.contains("推荐")) {
                log.info("💡 {}", line);
            } else if (line.contains("评估") || line.contains("效果")) {
                log.info("📊 {}", line);
            } else {
                log.info("📝 {}", line);
            }
        }
        
        // 发送最后一个部分的内容
        if (!sectionContent.isEmpty()) {
            sendSummarySubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
        }
        
        // 发送完整的总结结果
        sendSummaryResult(dynamicContext, summaryResult, sessionId);
        
        // 发送完成标识
        sendCompleteResult(dynamicContext, sessionId);
    }
    
    /**
     * 发送总结结果到流式输出
     */
    private void sendSummaryResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                 String summaryResult, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(
                 summaryResult, sessionId);
        sendSseResult(dynamicContext, result);
    }
    
    /**
     * 发送总结阶段细分结果到流式输出
     */
    private void sendSummarySubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                     String subType, String content, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummarySubResult(
                subType, content, sessionId);
        sendSseResult(dynamicContext, result);
    }
    
    /**
     * 发送完成标识到流式输出
     */
    private void sendCompleteResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
        log.info("✅ 已发送完成标识");
    }
    
    /**
     * 渐进式摘要：与 Fixed 逻辑一致（首次 ≥20，之后每 4 条节流，覆盖式 upsert）。
     * 从 ChatMemory 取消息计数，判断是否触发摘要。
     */
    private void saveEpisodicMemory(ExecuteCommandEntity req, String summaryResult,
                                    DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (episodicMemoryService == null) return;
        if (summaryResult == null || summaryResult.isBlank()) return;

        String userId = req.getUserId() != null ? req.getUserId() : MDC.get("userId");
        if (userId == null || userId.isBlank()) return;
        String tenantId = req.getTenantId() != null ? req.getTenantId() : MDC.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        String sessionId = req.getSessionId();

        try {
            String convId = buildConversationId(req);
            // 直接查 repository 拿真实消息总数，绕过 SummarizingChatMemory 的滑动窗口截断
            int msgCount = repository.countChatMemoryByConversationId(convId);
            log.info("[AutoSTM] real msgCount={} (bypassing window)", msgCount);
            if (msgCount < EPISODIC_SUMMARY_THRESHOLD) return;

            // 节流：已有摘要时，delta 必须是 4 的倍数才触发
            int lastSummarized = episodicMemoryService.getLastSummarizedMsgCount(sessionId);
            int delta = msgCount - lastSummarized;
            log.info("[AutoSTM] episodic check: msgCount={} lastSummarized={} delta={} threshold={} interval={}",
                    msgCount, lastSummarized, delta, EPISODIC_SUMMARY_THRESHOLD, EPISODIC_THROTTLE_INTERVAL);
            if (lastSummarized >= 0) {
                if (delta < EPISODIC_THROTTLE_INTERVAL || delta % EPISODIC_THROTTLE_INTERVAL != 0) {
                    log.info("[AutoSTM] episodic throttle: delta={} not a multiple of {}", delta, EPISODIC_THROTTLE_INTERVAL);
                    return;
                }
            }

            // 用 router-small 做摘要
            ChatClient summarizer = null;
            String summarizerBeanName = cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.AI_CLIENT.getBeanName("router-small");
            try {
                summarizer = applicationContext.getBean(summarizerBeanName, ChatClient.class);
            } catch (Exception e) {
                log.warn("[AutoSTM] summarizer bean '{}' not found: {}", summarizerBeanName, e.getMessage());
            }
            if (summarizer == null) {
                log.warn("[AutoSTM] summarizer is null, skipping episodic memory update. msgCount={} lastSummarized={}", msgCount, lastSummarized);
                return;
            }

            java.util.List<String> allTexts = repository.findChatMemoryTextsByConversationId(convId);
            if (allTexts == null || allTexts.isEmpty()) return;
            int from = lastSummarized < 0 ? 0 : Math.max(0, allTexts.size() - delta);
            java.util.List<String> sourceTexts = allTexts.subList(from, allTexts.size());
            if (sourceTexts.isEmpty()) return;

            String previousSummary = lastSummarized < 0 ? null : episodicMemoryService.findBySessionId(sessionId);
            String prompt = buildEpisodicSummaryPromptFromTexts(previousSummary, sourceTexts, sourceTexts.size());

            String episodicSummary = summarizer.prompt().user(prompt).call().content();
            if (episodicSummary == null || episodicSummary.isBlank()) return;
            episodicSummary = episodicSummary.replaceAll("(?s)<think>.*?</think>", "").trim();
            if (episodicSummary.isBlank()) return;
            /*
            if (lastSummarized < 0) {
                // 首次：用 Step4 的 summaryResult 作为摘要（已经是一次 LLM 总结了）
                prompt = null; // 不需要再做 LLM 摘要，直接用 summaryResult
            } else {
                // 后续：已有摘要 + 最近 delta 条消息 → 重新摘要
                String previousSummary = episodicMemoryService.findBySessionId(sessionId);
                java.util.List<String> allTexts = repository.findChatMemoryTextsByConversationId(convId);
                int from = Math.max(0, allTexts.size() - delta);
                java.util.List<String> recentTexts = allTexts.subList(from, allTexts.size());
                prompt = buildEpisodicSummaryPromptFromTexts(previousSummary, recentTexts, recentTexts.size());
            }
            */

            /*
            String ignoredEpisodicSummary;
            if (prompt == null) {
                ignoredEpisodicSummary = summaryResult;
            } else {
                ignoredEpisodicSummary = summarizer.prompt().user(prompt).call().content();
                if (ignoredEpisodicSummary == null || ignoredEpisodicSummary.isBlank()) return;
                ignoredEpisodicSummary = ignoredEpisodicSummary.replaceAll("(?s)<think>.*?</think>", "").trim();
                if (ignoredEpisodicSummary.isBlank()) return;
            }
            */
            if (episodicSummary.length() > 500) episodicSummary = episodicSummary.substring(0, 500);

            String topic = dynamicContext.getCurrentTask() != null
                    ? dynamicContext.getCurrentTask().length() > 64
                        ? dynamicContext.getCurrentTask().substring(0, 64)
                        : dynamicContext.getCurrentTask()
                    : "general";
            episodicMemoryService.upsert(userId, tenantId, sessionId, topic, episodicSummary, msgCount);
            log.info("[AutoSTM] episodic summarized msgCount={} summaryLen={} lastSummarized={} isFirst={}",
                    msgCount, episodicSummary.length(), lastSummarized, lastSummarized < 0);
        } catch (Exception e) {
            log.warn("[AutoSTM] episodic summarize failed: {}", e.getMessage(), e);
        }
    }

    private String buildEpisodicSummaryPrompt(String previousSummary, java.util.List<org.springframework.ai.chat.messages.Message> msgs, int msgCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话摘要器。将以下对话压缩为1-3句中文摘要，涵盖主题、关键结论、用户意图。\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【此会话之前的摘要】\n").append(previousSummary).append("\n\n");
            sb.append("【本轮新增对话】\n");
        }
        sb.append("（共").append(msgCount).append("条消息）\n");
        for (var m : msgs) {
            String text = m.getText();
            if (text == null || text.isBlank()) continue;
            String role = m.getMessageType() != null ? m.getMessageType().name() : "?";
            String shortened = text.length() > 300 ? text.substring(0, 300) + "..." : text;
            sb.append("[").append(role).append("] ").append(shortened).append("\n");
        }
        sb.append("\n只输出摘要文本，不要前缀，不要解释。");
        return sb.toString();
    }

    private String buildEpisodicSummaryPromptFromTexts(String previousSummary, java.util.List<String> texts, int msgCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话摘要器。将以下内容压缩为1-3句中文摘要，涵盖主题、关键结论、用户意图。\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【此会话之前的摘要】（必须保留其中的关键信息，与新增对话合并）\n").append(previousSummary).append("\n\n");
            sb.append("【本轮新增对话】\n");
            sb.append("（共").append(msgCount).append("条新消息）\n");
        } else {
            sb.append("（共").append(msgCount).append("条消息）\n");
        }
        for (String line : texts) {
            if (line == null || line.isBlank()) continue;
            String shortened = line.length() > 350 ? line.substring(0, 350) + "..." : line;
            sb.append(shortened).append("\n");
        }
        sb.append("\n要求：输出的摘要必须包含【之前的摘要】中的关键信息和【新增对话】的内容，两者缺一不可。只输出摘要文本，不要前缀，不要解释。");
        return sb.toString();
    }

    /**
     * 检测总结部分标识
     */
    private String detectSummarySection(String content) {
        if (content.contains("已完成的工作") || content.contains("完成的工作") || content.contains("工作内容和成果")) {
            return "completed_work";
        } else if (content.contains("未完成的原因") || content.contains("未完成原因")) {
            return "incomplete_reasons";
        } else if (content.contains("关键因素") || content.contains("完成的关键因素")) {
            return "key_factors";
        } else if (content.contains("执行效率") || content.contains("执行效率和质量")) {
            return "efficiency_quality";
        } else if (content.contains("完成剩余任务的建议") || content.contains("建议") || content.contains("优化建议") || content.contains("经验总结")) {
            return "suggestions";
        } else if (content.contains("整体执行效果") || content.contains("评估")) {
            return "evaluation";
        }
        return null;
    }

}
