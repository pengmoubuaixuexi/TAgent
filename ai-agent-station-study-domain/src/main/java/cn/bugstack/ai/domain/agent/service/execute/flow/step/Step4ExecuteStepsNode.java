package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.IConversationTurnMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第四步：按顺序执行规划步骤节点
 *
 * @author TAgent
 * 2025/8/25 10:30
 */
@Slf4j
@Component
public class Step4ExecuteStepsNode extends AbstractExecuteSupport {

    /** P2.1 Episodic Memory；为 null 时跳过保存 */
    @Autowired(required = false)
    private IEpisodicMemoryService episodicMemoryService;

    /** 2026-05-07：注入真实工具列表给执行 prompt */
    @jakarta.annotation.Resource
    private AgentToolRegistry agentToolRegistry;

    /**
     * 2026-05-07：DAG 并行执行专用 IO 线程池（B 方案，过渡，等 JDK 21 后换虚拟线程）。
     * 注入失败时（@Bean 不存在）降级用 commonPool，不阻塞启动
     */
    @jakarta.annotation.Resource(name = "dagExecutor")
    private java.util.concurrent.Executor dagExecutor;

    @Autowired(required = false)
    private IConversationTurnMemoryService conversationTurnMemoryService;

    /** 2026-05-08：流式适配。advisor.after 在 stream 模式下拿不到 ChatResponse output，节点级直触发 LTM 抽取 */
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService longTermMemoryService;

    /** 摘要触发阈值：10 轮 = 20 条消息 */
    private static final int EPISODIC_SUMMARY_THRESHOLD = 20;
    /** 节流间隔：每新增 4 条消息（= 2 轮）触发一次 */
    private static final int EPISODIC_THROTTLE_INTERVAL = 4;

    /** P2.2 11.2 Plan DAG：启用后独立步骤并行执行 */
    @org.springframework.beans.factory.annotation.Value("${agent.plan-dag.enabled:false}")
    private boolean planDagEnabled;

    @Override
    public String doApply(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        checkCancelled(dynamicContext);
        log.info("开始执行第四步：按顺序执行规划步骤");

        try {
            // 把 sessionId 暂存到 dynamicContext，executeStep / handleStepExecutionError 沿用历史约定取它发 SSE / mirror WM
            dynamicContext.setValue("sessionId", request.getSessionId());

            // 获取配置信息
            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode());
            if (aiAgentClientFlowConfigVO == null) {
                throw new BizException("flow agent missing flow config: " + AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode()
                        + " for agentId=" + request.getAiAgentId());
            }

            // 获取规划客户端
            ChatClient executorChatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());

            // 从动态上下文获取解析的步骤
            Map<String, String> stepsMap = dynamicContext.getValue("stepsMap");

            if (stepsMap == null || stepsMap.isEmpty()) {
                return "步骤映射为空，无法执行";
            }

            // 2026-05-07 DAG UX：执行前先把所有步骤的占位卡片一次性广播给前端，
            // 让用户立即看到完整计划而不是一个个等出现
            broadcastStepPending(stepsMap, dynamicContext, request.getSessionId());

            // 按顺序执行规划步骤
            executeStepsInOrder(executorChatClient, stepsMap, dynamicContext);

            // 发送SSE结果
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createExecutionResult(
                    dynamicContext.getStep(),
                    "已完成所有规划步骤的执行",
                    request.getSessionId()
            );
            sendSseResult(dynamicContext, result);
            
            // 发送总结结果到【最终执行结果】区域
            String finalSummary = sendSummaryResult(dynamicContext, request, executorChatClient);
            if (conversationTurnMemoryService != null) {
                conversationTurnMemoryService.saveFinalTurn(request, finalSummary);
            }

            // 2026-05-08：流式模式下 advisor.after 拿不到完整 assistantText，节点级直触发 LTM 抽取
            triggerLongTermMemoryExtraction(request, finalSummary);

            // P2.1 Episodic Memory：渐进式摘要（与 Auto Step4 逻辑一致）
            saveEpisodicMemory(request, dynamicContext, stepsMap);
            
            // 发送完成标识
            sendCompleteResult(dynamicContext, request.getSessionId());
            
            // 更新步骤
            dynamicContext.setStep(dynamicContext.getStep() + 1);
            dynamicContext.setCompleted(true);
            
            log.info("第四步执行完成：所有规划步骤已执行");

            return "所有规划步骤执行完成";
        } catch (Exception e) {
            log.error("第四步执行失败", e);
            return "执行步骤失败: " + e.getMessage();
        }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        return defaultStrategyHandler;
    }
    
    /**
     * 按顺序执行规划步骤
     */
    private void executeStepsInOrder(ChatClient executorChatClient, Map<String, String> stepsMap, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (stepsMap == null || stepsMap.isEmpty()) {
            log.warn("步骤映射为空，无法执行");
            return;
        }

        // 按步骤编号排序执行
        List<Integer> stepNumbers = new ArrayList<>();
        for (String stepKey : stepsMap.keySet()) {
            try {
                // 从"第1步"、"第2步"等格式中提取数字
                Pattern numberPattern = Pattern.compile("第(\\d+)步");
                Matcher matcher = numberPattern.matcher(stepKey);
                if (matcher.find()) {
                    stepNumbers.add(Integer.parseInt(matcher.group(1)));
                }
            } catch (NumberFormatException e) {
                log.warn("无法解析步骤编号: {}", stepKey);
            }
        }

        // 排序步骤编号
        stepNumbers.sort(Integer::compareTo);

        // 2026-05-07 DAG：planDagEnabled 时按依赖关系拓扑分层执行（同层并行，不同层串行）
        // 没声明依赖的 step 落在第 0 层 → 全并行；有依赖的等依赖层完成后进入下一层
        @SuppressWarnings("unchecked")
        Map<Integer, Set<Integer>> deps = (Map<Integer, Set<Integer>>) dynamicContext.getValue("stepDependencies");
        if (planDagEnabled && stepNumbers.size() > 1) {
            executeStepsAsDag(executorChatClient, stepsMap, stepNumbers,
                    deps != null ? deps : Collections.emptyMap(), dynamicContext);
        } else {
            // 按顺序执行每个步骤
            for (Integer stepNumber : stepNumbers) {
                String stepKey = "第" + stepNumber + "步";
                String stepContent = resolveStepContent(stepsMap, stepKey);
                if (stepContent != null) {
                    executeStep(executorChatClient, stepNumber, stepKey, stepContent, dynamicContext,
                            deps != null ? deps.getOrDefault(stepNumber, Collections.emptySet())
                                         : Collections.emptySet());
                } else {
                    log.warn("未找到步骤内容: {}", stepKey);
                }
            }
        }
    }

    /**
     * 2026-05-07 DAG 拓扑分层执行：
     * <ul>
     *   <li>识别 ready set（所有依赖都已 done 的 step）</li>
     *   <li>当前层并行 join，等齐再进下一层</li>
     *   <li>检测循环依赖 / 不可解析依赖：fallback 串行执行剩余步骤，避免死循环</li>
     * </ul>
     * 每个 step 通过 callStepWithStreaming 携带 dependsOn 字段发 SSE，
     * 前端可标注"依赖步骤 X"标签。
     */
    private void executeStepsAsDag(ChatClient executorChatClient, Map<String, String> stepsMap,
                                    List<Integer> stepNumbers, Map<Integer, Set<Integer>> deps,
                                    DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        Set<Integer> done = new HashSet<>();
        int safety = 0;
        // 上下文接力交给 dagExecutor 内部统一处理（DagExecutorConfig.wrap），这里不再手动捕获

        while (done.size() < stepNumbers.size() && safety++ < 100) {
            // 找出本层 ready：所有依赖都 done
            List<Integer> ready = new ArrayList<>();
            for (Integer n : stepNumbers) {
                if (done.contains(n)) continue;
                Set<Integer> d = deps.getOrDefault(n, Collections.emptySet());
                if (done.containsAll(d)) ready.add(n);
            }
            if (ready.isEmpty()) {
                // 循环依赖或解析错误 → 把剩下的串行跑掉，不卡死
                List<Integer> remaining = new ArrayList<>();
                for (Integer n : stepNumbers) if (!done.contains(n)) remaining.add(n);
                log.warn("[DAG] 检测到无法满足的依赖，剩余步骤 {} 退化为串行执行", remaining);
                for (Integer n : remaining) {
                    String key = "第" + n + "步";
                    String content = resolveStepContent(stepsMap, key);
                    if (content != null) {
                        executeStep(executorChatClient, n, key, content, dynamicContext,
                                deps.getOrDefault(n, Collections.emptySet()));
                    }
                    done.add(n);
                }
                break;
            }
            log.info("[DAG] 第 {} 层并行执行 {} 个步骤: {}", done.size(), ready.size(), ready);

            // 同层并行执行：走专用 dagExecutor，不和其他异步链路争 commonPool
            // dagExecutor 内部已有 ContextSnapshot 接力（参见 DagExecutorConfig.wrap），
            // 这里不再额外手动捕获，避免双层包装
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Integer stepNumber : ready) {
                String stepKey = "第" + stepNumber + "步";
                String stepContent = resolveStepContent(stepsMap, stepKey);
                if (stepContent == null) continue;
                Set<Integer> deplist = deps.getOrDefault(stepNumber, Collections.emptySet());
                futures.add(CompletableFuture.runAsync(() -> {
                    MDC.put("step", "flow_step4_dag_" + stepNumber);
                    try {
                        executeStep(executorChatClient, stepNumber, stepKey, stepContent,
                                dynamicContext, deplist);
                    } finally {
                        MDC.remove("step");
                    }
                }, dagExecutor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            done.addAll(ready);
        }
    }

    // 旧的 executeStepsInParallel 已被 executeStepsAsDag 替代（2026-05-07）

    /**
     * 2026-05-07 DAG UX：执行前批量发 step_pending 事件，让前端立即看到所有占位卡片。
     */
    private void broadcastStepPending(Map<String, String> stepsMap,
                                      DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                      String sessionId) {
        @SuppressWarnings("unchecked")
        Map<Integer, Set<Integer>> deps = (Map<Integer, Set<Integer>>) dynamicContext.getValue("stepDependencies");
        if (deps == null) deps = Collections.emptyMap();

        // 按 step 编号排序广播，前端就按这个顺序排版
        List<Integer> stepNumbers = new ArrayList<>();
        for (String stepKey : stepsMap.keySet()) {
            Matcher m = Pattern.compile("第(\\d+)步").matcher(stepKey);
            if (m.find()) stepNumbers.add(Integer.parseInt(m.group(1)));
        }
        stepNumbers.sort(Integer::compareTo);

        for (Integer n : stepNumbers) {
            Set<Integer> depNums = deps.getOrDefault(n, Collections.emptySet());
            Set<String> depStepIds = depNums.isEmpty() ? Collections.emptySet()
                    : depNums.stream()
                        .map(d -> "flow_step4_execute_step_" + d)
                        .collect(java.util.stream.Collectors.toSet());
            sendStepPending(dynamicContext,
                    "flow_step4_execute_step_" + n,
                    "执行 第" + n + "步",
                    depStepIds,
                    sessionId);
        }
    }

    private String resolveStepContent(Map<String, String> stepsMap, String stepKey) {
        for (Map.Entry<String, String> entry : stepsMap.entrySet()) {
            if (entry.getKey().startsWith(stepKey)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    /**
     * 执行单个步骤（DAG 版：携带 dependsOn 给 SSE 显示依赖关系）
     */
    private void executeStep(ChatClient executorChatClient, Integer stepNumber, String stepKey,
                             String stepContent, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                             Set<Integer> dependsOnNumbers) {
        log.info("\n--- 开始执行 {} {}---", stepKey,
                dependsOnNumbers != null && !dependsOnNumbers.isEmpty() ? "(依赖: " + dependsOnNumbers + ") " : "");
        log.info("步骤内容: {}", stepContent.substring(0, Math.min(200, stepContent.length())) + "...");

        try {
            // 更新执行上下文
            dynamicContext.setValue("currentStep", stepNumber);
            dynamicContext.setValue("currentStepKey", stepKey);
            dynamicContext.setValue("currentStepContent", stepContent);

            // 使用执行器ChatClient来执行具体步骤
            // 2026-05-07：把依赖步骤的产出 + 真实工具列表一并塞进 prompt
            AiAgentClientFlowConfigVO execConfig = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode());
            String executorClientId = execConfig != null ? execConfig.getClientId() : null;
            String stepExecPrompt = buildStepExecutionPrompt(stepContent, dynamicContext, dependsOnNumbers, executorClientId)
                    + githubRepositorySearchGuidance();
            ChatClient.ChatClientRequestSpec spec0 = executorChatClient.prompt().user(stepExecPrompt);
            final ChatClient.ChatClientRequestSpec streamSpec = (step4MaxTokens > 0)
                    ? spec0.options(ChatOptions.builder().maxTokens(step4MaxTokens).build())
                    : spec0;
            // 2026-05-07 流式 UX：每个子 step 独立 step_start/end，前端折叠为"执行 步骤N 已完成"
            // dependsOn 转成 stepId 集合（与 step_start 的 stepId 命名约定保持一致）
            Set<String> dependsOnStepIds = (dependsOnNumbers == null || dependsOnNumbers.isEmpty())
                    ? Collections.emptySet()
                    : dependsOnNumbers.stream()
                        .map(n -> "flow_step4_execute_step_" + n)
                        .collect(java.util.stream.Collectors.toSet());
            String stepSessionId = (String) dynamicContext.getValue("sessionId");
            String executionResult = callStepWithStreaming(
                    streamSpec, dynamicContext,
                    "flow_step4_execute_step_" + stepNumber, "执行 " + stepKey,
                    dependsOnStepIds, stepExecPrompt, stepSessionId);

            if (executionResult == null) throw new BizException("flow step4: executionResult is null", "LLM returned null for Step4ExecuteStepsNode");
            log.info("步骤 {} 执行结果: {}", stepNumber, executionResult.substring(0, Math.min(150, executionResult.length())) + "...");

            // 保存执行结果
            dynamicContext.setValue("step" + stepNumber + "Result", executionResult);
            // P1.2.2：旁路镜像（flow 路径，按子步骤编号区分）
            String sessionId = (String) dynamicContext.getValue("sessionId");
            mirrorToWorkingMemory(sessionId, "flow.step4.step" + stepNumber + "Result", executionResult);
            
            // 发送步骤执行结果的SSE（PII 脱敏：仅在最终输出给用户时脱敏）
            String maskedResult = cn.bugstack.ai.domain.agent.service.security.PiiMasker.mask(executionResult);
            AutoAgentExecuteResultEntity stepResult = AutoAgentExecuteResultEntity.createExecutionResult(
                    stepNumber,
                    stepKey + " 执行完成: " + maskedResult.substring(0, Math.min(500, maskedResult.length())),
                    (String) dynamicContext.getValue("sessionId")
            );
            sendSseResult(dynamicContext, stepResult);

            // 串行场景下避免触发上游限流；plan-dag 并行模式下意义不大但保留以兼容旧行为
            if (!planDagEnabled) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            log.error("执行步骤 {} 时发生错误: {}", stepNumber, e.getMessage());
            dynamicContext.setValue("step" + stepNumber + "Error", e.getMessage());

            // 记录错误但继续执行下一步
            handleStepExecutionError(stepNumber, stepKey, e, dynamicContext);
        }

        log.info("--- 完成执行 {} ---", stepKey);
    }
    
    /**
     * 处理步骤执行错误
     */
    private void handleStepExecutionError(Integer stepNumber, String stepKey, Exception e, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        log.warn("步骤 {} 执行失败，尝试恢复策略", stepNumber);

        // 记录错误统计
        Map<String, Integer> errorStats = dynamicContext.getValue("stepErrorStats");
        if (errorStats == null) {
            errorStats = new HashMap<>();
            dynamicContext.setValue("stepErrorStats", errorStats);
        }
        errorStats.put("step" + stepNumber, errorStats.getOrDefault("step" + stepNumber, 0) + 1);

        // 如果是网络错误，可以尝试重试
        if (e.getMessage() != null && (e.getMessage().contains("timeout") || e.getMessage().contains("connection"))) {
            log.info("检测到网络错误，将在后续重试机制中处理");
        }

        // 标记步骤为部分完成状态
        dynamicContext.setValue("step" + stepNumber + "Status", "FAILED_WITH_ERROR");
        
        // 发送错误结果的SSE
        try {
            AutoAgentExecuteResultEntity errorResult = AutoAgentExecuteResultEntity.createExecutionResult(
                    stepNumber,
                    stepKey + " 执行失败: " + e.getMessage(),
                    dynamicContext.getValue("sessionId")
            );
            sendSseResult(dynamicContext, errorResult);
        } catch (Exception sseException) {
            log.error("发送错误SSE结果失败", sseException);
        }
    }
    
    /**
     * 构建步骤执行提示词。
     * <p>
     * 2026-05-07 关键改造：
     * <ol>
     *   <li>注入前置依赖步骤的真实执行结果（之前缺失，下游只能靠"模拟"）</li>
     *   <li>显式禁止幻觉式工具调用：无 ToolCallback 注册时必须诚实承认无法执行</li>
     * </ol>
     */
    private String buildStepExecutionPrompt(String stepContent,
                                             DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                             Set<Integer> dependsOnNumbers,
                                             String executorClientId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能执行助手，需要执行以下步骤:\n\n");

        // 0. 真实工具列表（让 LLM 知道实际有什么工具可用，避免引用不存在的工具）
        if (executorClientId != null && agentToolRegistry != null) {
            sb.append("**【可用工具清单】**\n").append(agentToolRegistry.describeToolsForPrompt(executorClientId)).append("\n\n");
        }

        // 1. 前置依赖步骤的真实产出（关键！下游 step 必须基于这些数据继续，不要自己编）
        if (dependsOnNumbers != null && !dependsOnNumbers.isEmpty()) {
            sb.append("**前置步骤产出（你必须基于这些真实数据继续，禁止自己编造或想象前置步骤的结果）:**\n");
            for (Integer depNum : dependsOnNumbers) {
                String depResult = (String) dynamicContext.getValue("step" + depNum + "Result");
                String depError = (String) dynamicContext.getValue("step" + depNum + "Error");
                sb.append("\n--- 第").append(depNum).append("步的实际执行结果 ---\n");
                if (depError != null && !depError.isBlank()) {
                    sb.append("（注意：第").append(depNum).append("步执行失败：").append(depError).append("）\n");
                } else if (depResult != null && !depResult.isBlank()) {
                    // 截断超长依赖（避免 token 爆炸），保留前 4000 字符
                    sb.append(depResult.length() > 4000 ? depResult.substring(0, 4000) + "\n...(已截断)" : depResult);
                } else {
                    sb.append("（前置步骤无产出数据可参考，但本步骤仍需基于自身能力执行）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("**步骤内容:**\n").append(stepContent).append("\n\n");
        sb.append("**用户原始请求:**\n").append(dynamicContext.getCurrentTask()).append("\n\n");

        // 2. 执行要求 + 反幻觉约束
        sb.append("**执行要求:**\n");
        sb.append("1. 仔细分析步骤内容，理解需要执行的具体任务\n");
        sb.append("2. **【重要 — 工具调用诚实原则】**：本平台只有当你确实有 MCP 工具回调可调用时才能执行真实操作。\n");
        sb.append("   - 如果步骤计划提到的工具（如 web_search / summarize / run_code 等）你**没有真实可用的工具回调**，\n");
        sb.append("     必须明确说明：\"抱歉，本步骤所需的工具在当前 Agent 配置中不可用，无法执行实际工具调用，\n");
        sb.append("     仅能基于已有知识提供建议\"，然后基于通用知识直接给出答复。\n");
        sb.append("   - **严禁**伪造工具调用过程（不要写\"调用搜索引擎工具中...\"\"模拟工具返回...\"\"工具返回JSON结果\"等）\n");
        sb.append("   - **严禁**虚构工具返回数据（不要编造搜索结果列表、URL、JSON 等假数据）\n");
        sb.append("   - 如果有真工具可用并成功调用，正常使用即可\n");
        sb.append("3. 充分利用上面【前置步骤产出】里的真实数据，不要假装看不见或自己重做一遍\n");
        sb.append("4. 如果遇到问题，说明具体的错误信息\n");
        sb.append("5. **执行完成后，必须在回复末尾明确输出执行结果，格式如下:**\n");
        sb.append("   ```\n");
        sb.append("   === 执行结果 ===\n");
        sb.append("   状态: [成功/失败/无工具可用]\n");
        sb.append("   结果描述: [具体的执行结果描述，或诚实说明因工具不可用提供的替代方案]\n");
        sb.append("   输出数据: [真实的输出数据；若无工具可用则填\"基于通用知识的建议（非工具检索结果）\"]\n");
        sb.append("   ```\n\n");
        sb.append("请开始执行这个步骤，并严格按照要求提供详细的执行报告和结果输出。");
        return sb.toString();
    }
    
    /**
     * 2026-05-08：流式聚合后触发 LTM 事实抽取，绕开 advisor.after 在 stream 模式下拿不到 output 的限制。
     * 仅在 agent.token-streaming.enabled=true 时由节点接管。
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
            log.warn("[FlowLTM] router-small not found, skip extraction: {}", e.getMessage());
            return;
        }
        cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor
                .triggerExtractionAsync(longTermMemoryService, extractionClient,
                        req.getMessage(), assistantText,
                        userId, tenantId, req.getSessionId(), req.getAiAgentId());
    }

    /**
     * 渐进式摘要：首次 ≥20，之后每 4 条节流，覆盖式 upsert。
     */
    private void saveEpisodicMemory(ExecuteCommandEntity req,
                                    DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                    Map<String, String> stepsMap) {
        if (episodicMemoryService == null) return;
        String userId = req.getUserId() != null ? req.getUserId() : MDC.get("userId");
        if (userId == null || userId.isBlank()) return;
        String tenantId = req.getTenantId() != null ? req.getTenantId() : MDC.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        String sessionId = req.getSessionId();

        try {
            String convId = buildConversationId(req);
            // 直接查 repository 拿真实消息总数，绕过 SummarizingChatMemory 的滑动窗口截断
            int msgCount = repository.countChatMemoryByConversationId(convId);
            log.info("[FlowSTM] real msgCount={} (bypassing window)", msgCount);
            if (msgCount < EPISODIC_SUMMARY_THRESHOLD) return;

            int lastSummarized = episodicMemoryService.getLastSummarizedMsgCount(sessionId);
            int delta = msgCount - lastSummarized;
            log.info("[FlowSTM] episodic check: msgCount={} lastSummarized={} delta={} threshold={} interval={}",
                    msgCount, lastSummarized, delta, EPISODIC_SUMMARY_THRESHOLD, EPISODIC_THROTTLE_INTERVAL);
            if (lastSummarized >= 0) {
                if (delta < EPISODIC_THROTTLE_INTERVAL || delta % EPISODIC_THROTTLE_INTERVAL != 0) {
                    log.info("[FlowSTM] episodic throttle: delta={} not a multiple of {}", delta, EPISODIC_THROTTLE_INTERVAL);
                    return;
                }
            }

            // 收集各个 step 结果拼摘要文本
            java.util.List<String> allTexts = repository.findChatMemoryTextsByConversationId(convId);
            if (allTexts == null || allTexts.isEmpty()) return;
            int from = lastSummarized < 0 ? 0 : Math.max(0, allTexts.size() - delta);
            java.util.List<String> sourceTexts = allTexts.subList(from, allTexts.size());
            if (sourceTexts.isEmpty()) return;

            String previousSummary = lastSummarized < 0 ? null : episodicMemoryService.findBySessionId(sessionId);
            String prompt = buildEpisodicSummaryPrompt(previousSummary, String.join("\n", sourceTexts));
            ChatClient summarizer = null;
            String summarizerBeanName = cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.AI_CLIENT.getBeanName("router-small");
            try {
                summarizer = applicationContext.getBean(summarizerBeanName, ChatClient.class);
            } catch (Exception e) {
                log.warn("[FlowSTM] summarizer bean '{}' not found: {}", summarizerBeanName, e.getMessage());
            }
            if (summarizer == null) {
                log.warn("[FlowSTM] summarizer is null, skipping episodic memory update. msgCount={} lastSummarized={}", msgCount, lastSummarized);
                return;
            }
            String episodicSummary = summarizer.prompt().user(prompt).call().content();
            if (episodicSummary == null || episodicSummary.isBlank()) return;
            episodicSummary = episodicSummary.replaceAll("(?s)<think>.*?</think>", "").trim();
            if (episodicSummary.isBlank()) return;

            /*
            StringBuilder stepResults = new StringBuilder();
            java.util.regex.Pattern numPat = java.util.regex.Pattern.compile("第(\\d+)步");
            for (String stepKey : stepsMap.keySet()) {
                java.util.regex.Matcher m = numPat.matcher(stepKey);
                if (m.find()) {
                    String result = dynamicContext.getValue("step" + m.group(1) + "Result");
                    if (result != null && !result.isBlank()) {
                        if (stepResults.length() > 0) stepResults.append("; ");
                        stepResults.append(result.length() > 200 ? result.substring(0, 200) + "..." : result);
                    }
                }
            }
            if (stepResults.length() <= 0) return;

            String episodicSummary;
            if (lastSummarized < 0) {
                // 首次：直接用 step 结果
                episodicSummary = stepResults.toString();
            } else {
                // 后续：已有摘要 + 最近 step 结果 → LLM 重新摘要
                String previousSummary = episodicMemoryService.findBySessionId(sessionId);
                String prompt = buildEpisodicSummaryPrompt(previousSummary, stepResults.toString());
                ChatClient summarizer = null;
                String summarizerBeanName = cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.AI_CLIENT.getBeanName("router-small");
                try {
                    summarizer = applicationContext.getBean(summarizerBeanName, ChatClient.class);
                } catch (Exception e) {
                    log.warn("[FlowSTM] summarizer bean '{}' not found: {}", summarizerBeanName, e.getMessage());
                }
                if (summarizer == null) {
                    log.warn("[FlowSTM] summarizer is null, skipping episodic memory update. msgCount={} lastSummarized={}", msgCount, lastSummarized);
                    return;
                }
                episodicSummary = summarizer.prompt().user(prompt).call().content();
                if (episodicSummary == null || episodicSummary.isBlank()) return;
                episodicSummary = episodicSummary.replaceAll("(?s)<think>.*?</think>", "").trim();
                if (episodicSummary.isBlank()) return;
            }
            */
            if (episodicSummary.length() > 500) episodicSummary = episodicSummary.substring(0, 500);

            String topic = dynamicContext.getCurrentTask() != null
                    ? dynamicContext.getCurrentTask().length() > 64
                        ? dynamicContext.getCurrentTask().substring(0, 64)
                        : dynamicContext.getCurrentTask()
                    : "general";
            episodicMemoryService.upsert(userId, tenantId, sessionId, topic, episodicSummary, msgCount);
            log.info("[FlowSTM] episodic summarized msgCount={} summaryLen={} isFirst={}", msgCount, episodicSummary.length(), lastSummarized < 0);
        } catch (Exception e) {
            log.warn("[FlowSTM] episodic summarize failed: {}", e.getMessage(), e);
        }
    }

    private String buildEpisodicSummaryPrompt(String previousSummary, String newContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话摘要器。将以下内容压缩为1-3句中文摘要，涵盖主题、关键结论、用户意图。\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【此会话之前的摘要】（必须保留其中的关键信息，与新增内容合并）\n").append(previousSummary).append("\n\n");
            sb.append("【本轮新增内容】\n");
        }
        sb.append(newContent);
        sb.append("\n\n要求：输出的摘要必须包含【之前的摘要】中的关键信息和【新增内容】的内容，两者缺一不可。只输出摘要文本，不要前缀，不要解释。");
        return sb.toString();
    }

    /**
     * 发送总结结果到流式输出
     */
    private String sendSummaryResult(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, ExecuteCommandEntity request, ChatClient finalWriterChatClient) {
        String sessionId = request.getSessionId();
        String finalDeliverable = buildFinalDeliverable(dynamicContext, request, finalWriterChatClient);
        if (finalDeliverable != null && !finalDeliverable.isBlank()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(finalDeliverable, sessionId);
            sendSseResult(dynamicContext, result);
            log.info("Flow final deliverable sent, len={}", finalDeliverable.length());
            return finalDeliverable;
        }

        // 构建执行总结内容
        StringBuilder summaryContent = new StringBuilder();
        summaryContent.append("## 执行步骤完成总结\n\n");
        
        // 获取执行历史
        StringBuilder executionHistory = dynamicContext.getExecutionHistory();
        if (executionHistory != null && executionHistory.length() > 0) {
            summaryContent.append("### 已完成的工作\n");
            summaryContent.append(executionHistory.toString());
            summaryContent.append("\n\n");
        }
        
        summaryContent.append("### 执行状态\n");
        summaryContent.append("✅ 所有规划步骤已成功执行完成\n\n");
        
        summaryContent.append("### 执行效果评估\n");
        summaryContent.append("📊 任务执行流程顺利完成，各步骤按计划执行");
        
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(
                summaryContent.toString(), sessionId);
        sendSseResult(dynamicContext, result);
        log.info("📊 已发送总结结果到【最终执行结果】区域");
        return summaryContent.toString();
    }
    
    /**
     * 发送完成标识到流式输出
     */
    private String buildFinalDeliverable(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, ExecuteCommandEntity request, ChatClient finalWriterChatClient) {
        String stepResults = collectStepResults(dynamicContext);
        if (stepResults.isBlank()) {
            return null;
        }

        String prompt = buildFinalSynthesisPrompt(dynamicContext, stepResults);
        // 2026-05-07 流式 UX：最终合成步骤独立 step_start/end，折叠为"最终合成 已完成"
        ChatClient.ChatClientRequestSpec specFinal = finalWriterChatClient.prompt()
                .user(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(request))
                        .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(request, "flow-final-synthesis"))
                        .param("memory_persist_final_turn", true)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50));
        String finalAnswer = callStepWithStreaming(
                specFinal, dynamicContext, "flow_step4_final_synthesis", "最终合成",
                prompt, request.getSessionId());

        if (finalAnswer == null || finalAnswer.isBlank()) {
            return fallbackFinalDeliverable(stepResults);
        }
        return stripExecutionWrapper(finalAnswer);
    }

    private String collectStepResults(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        StringBuilder stepResults = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            String stepResult = dynamicContext.getValue("step" + i + "Result");
            if (stepResult == null || stepResult.isBlank()) {
                continue;
            }
            stepResults.append("\n\n### Step ").append(i).append("\n\n").append(stepResult.trim());
        }
        return stepResults.toString().trim();
    }

    private String buildFinalSynthesisPrompt(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, String stepResults) {
        return """
                你是 Flow Agent 的最终答案整理器。请只基于下面的步骤结果，整理出直接面向用户的最终回答。

                【用户原始问题】
                %s

                【各步骤执行结果】
                %s

                【输出要求】
                1. 直接回答用户原始问题，输出用户真正想要的成品答案。
                2. 如果步骤中包含验证、审核、风险检查，只吸收其中有用改进点，不要把“验证报告/执行状态/工具缺失说明”作为主体。
                3. 不要输出“状态: 成功”“结果描述”“输出数据”“执行过程记录”“Step 1/Step 2”等流程化内容。
                4. 不要再调用任何工具。
                5. 如果步骤里有对用户有价值的图片路径或链接，可以自然保留；否则不要为了展示工具过程而保留。
                6. 用清晰的 Markdown 输出，内容要完整、自然、可直接交付。
                """.formatted(
                dynamicContext.getCurrentTask() == null ? "" : dynamicContext.getCurrentTask(),
                stepResults);
    }

    private String fallbackFinalDeliverable(String stepResults) {
        return "## \u6700\u7ec8\u8f93\u51fa\n\n" + stripExecutionWrapper(stepResults);
    }

    private String stripExecutionWrapper(String content) {
        if (content == null) {
            return "";
        }
        String marker = "=== \u6267\u884c\u7ed3\u679c ===";
        int markerIndex = content.indexOf(marker);
        if (markerIndex >= 0) {
            return content.substring(markerIndex + marker.length()).trim();
        }
        return content.trim();
    }

    private void sendCompleteResult(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
        log.info("✅ 已发送完成标识");
    }
}
