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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 执行总结节点
 *
 * @author TAgent
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

    /** 摘要触发阈值：10 轮 = 20 条消息 */
    private static final int EPISODIC_SUMMARY_THRESHOLD = 20;
    /** 节流间隔：每新增 4 条消息（= 2 轮）触发一次 */
    private static final int EPISODIC_THROTTLE_INTERVAL = 4;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n📊 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 第四阶段：执行总结
        log.info("\n📊 阶段4: 执行总结分析");
        
        // 记录执行总结
        logExecutionSummary(dynamicContext.getMaxStep(), dynamicContext.getExecutionHistory(), dynamicContext.isCompleted());
        
        // 生成最终总结报告（无论任务是否完成都需要生成）
        generateFinalReport(requestParameter, dynamicContext);
        
        log.info("\n🏁 === 动态多轮执行结束 ====");
        
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
    private void generateFinalReport(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            boolean isCompleted = dynamicContext.isCompleted();
            log.info("\n--- 生成{}任务的最终答案 ---", isCompleted ? "已完成" : "未完成");

            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());
            if (aiAgentClientFlowConfigVO == null) {
                throw new BizException("auto agent missing flow config: " + AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode()
                        + " for agentId=" + requestParameter.getAiAgentId());
            }

            String summaryPrompt = getSummaryPrompt(aiAgentClientFlowConfigVO, requestParameter, dynamicContext, isCompleted);

            // 获取对话客户端 - 使用任务分析客户端进行总结
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());
            
            ChatClient.ChatClientRequestSpec spec0 = chatClient
                    .prompt(summaryPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(requestParameter))
                            .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(requestParameter, "auto-step4-final-summary"))
                            .param("memory_persist_final_turn", true)
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50));
            final ChatClient.ChatClientRequestSpec streamSpec = (step4MaxTokens > 0)
                    ? spec0.options(ChatOptions.builder().maxTokens(step4MaxTokens).build())
                    : spec0;
            // 2026-05-07 流式 UX：step_start → 流式 token → step_end（折叠为"最终总结 已完成"）
            String summaryResult = callStepWithStreaming(
                    streamSpec, dynamicContext, "step4_summary", "最终总结",
                    summaryPrompt, requestParameter.getSessionId());

            if (summaryResult == null) throw new BizException("step4: summaryResult is null", "LLM returned null for Step4LogExecutionSummaryNode");
            // P2.5 14.2 PII 脱敏：仅在最终输出给用户时脱敏
            summaryResult = OutputFilter.cleanForUser(cn.bugstack.ai.domain.agent.service.security.PiiMasker.mask(summaryResult));
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

    private static String getSummaryPrompt(AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO, ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, boolean isCompleted) {
        String summaryPrompt;
        if (isCompleted) {
            summaryPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                    requestParameter.getMessage(),
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
                    requestParameter.getMessage(),
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
