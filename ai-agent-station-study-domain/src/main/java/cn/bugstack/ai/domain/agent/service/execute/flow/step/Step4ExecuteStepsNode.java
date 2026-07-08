package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.IConversationTurnMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.types.exception.BizException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第四步：按顺序执行规划步骤节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
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

    @jakarta.annotation.Resource
    private McpToolCatalogService mcpToolCatalogService;

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

    /** 立即回答可观测：打断时写一条 ai_event_log 标记行。 */
    @Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.execute.IEventLogService eventLogService;

    /** 摘要触发阈值：10 轮 = 20 条消息 */
    private static final int EPISODIC_SUMMARY_THRESHOLD = 20;
    /** 节流间隔：每新增 4 条消息（= 2 轮）触发一次 */
    private static final int EPISODIC_THROTTLE_INTERVAL = 4;

    /** 2026-05-28：注入给下游步骤的依赖产出最大字符数，防 token 爆炸（现在注入的是结构化「输出数据」段，比整段散文密） */
    private static final int DEP_INJECT_MAX_CHARS = 6000;

    /**
     * step4 子步执行的系统角色：用请求级 .system() 覆盖 EXECUTOR_CLIENT 的领域人设
     * （如 8010_p3 "旅行规划攻略编写者→输出完整逐日行程/预算/物品清单"），把每个子步钉成
     * "只做本步、只产本步数据"，禁止提前生成完整最终交付物。
     * 这些子步是 step2 现场生成、step3 解析出的运行时产物，DB 里无对应 prompt 行 → 只能代码兜底；
     * 最终整合 buildFinalDeliverable 不走这里，仍用 EXECUTOR_CLIENT 的 DB 系统提示(8010_p3)产出完整交付物。
     * Spring AI 1.0.0 请求级 .system(text) 替换 defaultSystem（非追加），故能干净盖掉领域人设。
     */
    private static final String SUB_STEP_EXECUTOR_SYSTEM =
            "你是多步骤任务中的【单步执行器】。本次只负责完成下面“步骤内容”里描述的【这一个步骤】，只产出该步骤自身的结果数据。\n" +
            "严禁提前生成完整的最终交付物（如完整行程、完整攻略、最终报告、整体汇总答案）——那是最后“整合步骤”的职责，不属于你。\n" +
            "有可调用的工具就调用拿真实数据；没有真实工具就基于前置步骤产出/已有知识完成本步。产出聚焦本步，不要扩展到其它步骤的范围。";

    /** P2.2 11.2 Plan DAG：启用后独立步骤并行执行 */
    @org.springframework.beans.factory.annotation.Value("${agent.plan-dag.enabled:false}")
    private boolean planDagEnabled;

    /** 立即回答：finalize 是否允许调工具（默认关）。 */
    @org.springframework.beans.factory.annotation.Value("${agent.answer-now.finalize-tools:false}")
    private boolean answerNowFinalizeTools;

    /** 立即回答：关思考指令（单行，代码侧补换行）。 */
    @org.springframework.beans.factory.annotation.Value("${agent.answer-now.no-think-directive:[立即作答模式] 用户已要求立即回答，请直接基于以上已有信息给出最终答案，不要再展开额外的思考或分析过程，简明扼要。}")
    private String answerNowNoThinkDirective;

    /** 立即回答 finalize 关思考：OpenAI reasoning_effort（minimal/low/medium/high）；空=不设。设到 OpenAiChatOptions，由 Spring AI 确定性序列化进 body。 */
    @org.springframework.beans.factory.annotation.Value("${agent.no-think.reasoning-effort:}")
    private String answerNowReasoningEffort;

    @Override
    public String doApply(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        checkCancelled(dynamicContext);
        // 立即回答（在 step4 执行前点的）：跳过 DAG 执行，直接基于"计划 + 已有产出 + 半截思考"整合作答
        if (dynamicContext.isFinalizeRequested()) {
            dynamicContext.setValue("sessionId", request.getSessionId());
            dynamicContext.setValue("dynamicMissingToolDesc", mcpToolCatalogService.needsFor(request.getSessionId()));
            dynamicContext.setValue("dynamicToolQuery", request.getMessage());
            return finalizeNow(request, dynamicContext);
        }
        // 进入执行阶段：禁止引导（flow step4 不消费 steerIdea；steerExecute 据此忽略，避免无意义断流）
        dynamicContext.setValue("flowInExecution", Boolean.TRUE);
        log.info("开始执行第四步：按顺序执行规划步骤");

        try {
            // 把 sessionId 暂存到 dynamicContext，executeStep / handleStepExecutionError 沿用历史约定取它发 SSE / mirror WM
            dynamicContext.setValue("sessionId", request.getSessionId());
            dynamicContext.setValue("dynamicMissingToolDesc", mcpToolCatalogService.needsFor(request.getSessionId()));
            dynamicContext.setValue("dynamicToolQuery", request.getMessage());

            // 获取配置信息
            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode());
            if (aiAgentClientFlowConfigVO == null) {
                throw new BizException("flow agent missing flow config: " + AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode()
                        + " for agentId=" + request.getAiAgentId());
            }

            // 获取规划客户端
            ChatClient executorChatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());
            List<ToolCallback> step4DynamicToolCallbacks = resolveAgentDynamicToolCallbacks(request, aiAgentClientFlowConfigVO.getClientId());
            cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog v3Catalog =
                    storeExecutorToolCatalogSnapshot(dynamicContext, FLOW_TOOL_CATALOG_V3_KEY,
                            aiAgentClientFlowConfigVO.getClientId(), step4DynamicToolCallbacks, 3);
            List<String> v2ToV3Drift = compareExecutorToolCatalogLineage(dynamicContext, FLOW_TOOL_CATALOG_V2_KEY, v3Catalog);
            if (!v2ToV3Drift.isEmpty()) {
                log.warn("[ExecutorToolCatalog] V2->V3 lineage drift: {}", v2ToV3Drift);
            }

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

            // 立即回答（在 DAG 执行途中点的）：已停止调度未启动子步，这里基于已完成产出直接整合作答
            if (dynamicContext.isFinalizeRequested()) {
                return finalizeNow(request, dynamicContext);
            }

            // 发送SSE结果
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createExecutionResult(
                    dynamicContext.getStep(),
                    "已完成所有规划步骤的执行",
                    request.getSessionId()
            );
            sendSseResult(dynamicContext, result);
            
            // 发送总结结果到【最终执行结果】区域
            String finalSummary = sendSummaryResult(dynamicContext, request, executorChatClient, aiAgentClientFlowConfigVO.getClientId());
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

            recordTransition("flow_step4_execute_steps", dynamicContext);
            return "所有规划步骤执行完成";
        } catch (CancellationException e) {
            log.info("[Cancel] flow step4 execution cancelled: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("execution cancelled during flow step4");
            }
            log.error("第四步执行失败", e);
            recordTransition("flow_step4_execute_steps_failed", dynamicContext);
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
                if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("execution cancelled before step " + stepNumber);
                }
                // 立即回答：停止调度剩余子步，已完成的产出交给 finalizeNow 整合
                if (dynamicContext.isFinalizeRequested()) {
                    log.info("[AnswerNow][flow] 串行执行中收到立即回答，停止调度第{}步及之后", stepNumber);
                    break;
                }
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
        // 2026-05-29 依赖驱动调度（取代旧的"整层屏障"）：每个步骤"自己的依赖一完成就启动"，
        // 不再等同层最慢的步骤。例：1、3 同为起始层，1 先完成 → 依赖 1 的 2 立刻开跑，不必等 3。
        // 用 Kahn 拓扑序保证构建 future 时其依赖的 future 已存在；有环的节点排不进拓扑序 → 末尾串行兜底，绝不卡死。
        // 上下文接力由 dagExecutor 内部统一处理（DagExecutorConfig.wrap）。
        java.util.Map<Integer, CompletableFuture<Void>> futures = new java.util.HashMap<>();

        // 1. Kahn 拓扑排序（仅统计落在 stepNumbers 内的依赖）
        java.util.Map<Integer, Integer> indegree = new java.util.HashMap<>();
        for (Integer n : stepNumbers) {
            int cnt = 0;
            for (Integer d : deps.getOrDefault(n, Collections.emptySet())) {
                if (stepNumbers.contains(d)) cnt++;
            }
            indegree.put(n, cnt);
        }
        java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
        for (Integer n : stepNumbers) if (indegree.get(n) == 0) queue.add(n);
        List<Integer> topoOrder = new ArrayList<>();
        while (!queue.isEmpty()) {
            Integer n = queue.poll();
            topoOrder.add(n);
            for (Integer m : stepNumbers) {
                if (deps.getOrDefault(m, Collections.emptySet()).contains(n)) {
                    int left = indegree.get(m) - 1;
                    indegree.put(m, left);
                    if (left == 0) queue.add(m);
                }
            }
        }

        // 2. 按拓扑序为每步建 future：allOf(依赖的 future).thenRunAsync(执行该步)。
        //    依赖一旦全部完成就立即在 dagExecutor 上启动，无整层屏障。
        for (Integer n : topoOrder) {
            final Integer stepNumber = n;
            final Set<Integer> d = deps.getOrDefault(n, Collections.emptySet());
            CompletableFuture<?>[] depFutures = d.stream()
                    .map(futures::get).filter(java.util.Objects::nonNull)
                    .toArray(CompletableFuture[]::new);
            final String stepKey = "第" + n + "步";
            final String stepContent = resolveStepContent(stepsMap, stepKey);
            CompletableFuture<Void> f = CompletableFuture.allOf(depFutures).thenRunAsync(() -> {
                MDC.put("step", "flow_step4_dag_" + stepNumber);
                try {
                    if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("execution cancelled before step " + stepNumber);
                    }
                    // 立即回答：已触发则未启动的子步直接跳过，让 future 快速完成，控制权尽快回到 finalizeNow
                    if (stepContent != null && !dynamicContext.isFinalizeRequested()) {
                        executeStep(executorChatClient, stepNumber, stepKey, stepContent, dynamicContext, d);
                    }
                } catch (CancellationException e) {
                    log.info("[Cancel] DAG step {} skipped/cancelled: {}", stepNumber, e.getMessage());
                    throw e;
                } catch (Exception e) {
                    // 单步失败不阻断依赖它的后续步骤（与旧行为一致：失败也视为"完成"）
                    log.error("[DAG] 第{}步 执行异常: {}", stepNumber, e.toString());
                } finally {
                    MDC.remove("step");
                }
            }, dagExecutor);
            futures.put(n, f);
            log.info("[DAG] 调度 第{}步 依赖={}（依赖完成即启动，无整层屏障）", n, d.isEmpty() ? "无" : d);
        }

        // 3. 环/无法拓扑排序的节点 → 等已调度的跑完后串行兜底，不卡死
        List<Integer> unscheduled = new ArrayList<>();
        for (Integer n : stepNumbers) if (!futures.containsKey(n)) unscheduled.add(n);
        if (!unscheduled.isEmpty()) {
            log.warn("[DAG] 检测到环/无法满足的依赖，剩余步骤 {} 退化为串行执行", unscheduled);
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
            for (Integer n : unscheduled) {
                if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("execution cancelled before fallback step " + n);
                }
                String stepKey = "第" + n + "步";
                String stepContent = resolveStepContent(stepsMap, stepKey);
                if (stepContent != null) {
                    executeStep(executorChatClient, n, stepKey, stepContent, dynamicContext,
                            deps.getOrDefault(n, Collections.emptySet()));
                }
            }
            return;
        }

        // 4. 等全部步骤完成（final synthesis 在 doApply 中 DAG 之后才执行）
        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
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
            String runId = dynamicContext.getValue("runId");
            String leaseSessionId = dynamicContext.getValue("sessionId");
            String latestSessionNeeds = mcpToolCatalogService != null ? mcpToolCatalogService.needsFor(leaseSessionId) : null;
            String dynamicMissingToolDesc = latestSessionNeeds != null && !latestSessionNeeds.isBlank()
                    ? latestSessionNeeds
                    : dynamicContext.getValue("dynamicMissingToolDesc");
            String dynamicToolQuery = dynamicContext.getValue("dynamicToolQuery");
            if (dynamicMissingToolDesc != null && !dynamicMissingToolDesc.isBlank()) {
                dynamicContext.setValue("dynamicMissingToolDesc", dynamicMissingToolDesc);
            }
            List<ToolCallback> dynamicToolCallbacks = mcpToolCatalogService != null
                    ? mcpToolCatalogService.resolveDynamicToolCallbacks(runId, leaseSessionId, executorClientId, dynamicMissingToolDesc, dynamicToolQuery,
                            agentToolRegistry != null ? agentToolRegistry.getTools(executorClientId) : List.of())
                    : List.of();
            storeExecutorToolCatalogSnapshot(dynamicContext, FLOW_TOOL_CATALOG_V3_KEY,
                    executorClientId, dynamicToolCallbacks, 3);
            String stepExecPrompt = buildStepExecutionPrompt(stepContent, dynamicContext, dependsOnNumbers, executorClientId, dynamicToolCallbacks);
            stepExecPrompt = appendCurrentTimeContext(stepExecPrompt);
            // 子步用窄角色 system 覆盖 EXECUTOR_CLIENT 的领域人设，防每个子步都照"攻略编写者"吐整份完整攻略。
            // 整合步(buildFinalDeliverable)不走这里，仍用 DB 的 8010_p3 产出完整交付物。
            ChatClient.ChatClientRequestSpec spec0 = executorChatClient.prompt()
                    // P2-B-1：A2 请求级 .system() 替换 defaultSystem，故公共信任边界须在此另行 prepend（否则 DAG 子步丢失）。
                    .system(cn.bugstack.ai.domain.agent.service.prompt.SystemPolicyComposer.prepend(SUB_STEP_EXECUTOR_SYSTEM))
                    .user(stepExecPrompt);
            // 必须 OpenAiChatOptions：DefaultChatClientUtils 需 instanceof ToolCallingChatOptions 才注入 toolContext，
            // 且 OpenAiChatModel.buildRequestPrompt 的 toolContext merge 把 runtime 强转 OpenAiChatOptions，
            // 非 OpenAiChatOptions 会丢掉 toolContext(sessionId/stepLabel) → 工具线程拿不到 → 审批门失效（Step4 是 flow 工具执行步骤）。
            org.springframework.ai.openai.OpenAiChatOptions.Builder step4OptionsBuilder =
                    org.springframework.ai.openai.OpenAiChatOptions.builder();
            if (step4MaxTokens > 0) {
                step4OptionsBuilder.maxTokens(step4MaxTokens);
            }
            if (!dynamicToolCallbacks.isEmpty()) {
                step4OptionsBuilder.toolCallbacks(toRequestToolCallbacks(executorClientId, dynamicToolCallbacks));
            }
            ChatClient.ChatClientRequestSpec streamSpec = spec0.options(step4OptionsBuilder.build());
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
                    dependsOnStepIds,
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_DAG_EXECUTION,
                    stepExecPrompt, stepSessionId);

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

        } catch (CancellationException e) {
            log.info("[Cancel] step {} execution cancelled: {}", stepNumber, e.getMessage());
            throw e;
        } catch (Exception e) {
            if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("execution cancelled during step " + stepNumber);
            }
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
                                             String executorClientId,
                                             List<ToolCallback> dynamicToolCallbacks) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能执行助手，需要执行以下步骤:\n\n");

        // 0. 真实工具列表（让 LLM 知道实际有什么工具可用，避免引用不存在的工具）
        if (executorClientId != null && agentToolRegistry != null) {
            cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog catalog =
                    snapshotExecutorToolCatalog(executorClientId, dynamicToolCallbacks, 3);
            sb.append("**【可用工具清单】**\n").append(renderToolRuntimeForPrompt(catalog, executorClientId)).append("\n\n");
            sb.append("**工具选择说明（重要）:**\n");
            sb.append("- 【可用工具清单】是真实允许使用的工具范围；只要在清单里，就可以按本步骤目标调用。\n");
            sb.append("- 【步骤内容】里的“使用工具/首选工具”是规划阶段给出的首选建议，不是唯一允许工具。\n");
            sb.append("- 如果首选工具返回字段不足、失败、空结果，或无法完成本步骤目标，可以改用【可用工具清单】中的其他相关工具补足，这不算偏离步骤。\n");
            sb.append("- 不要陷入反复思考；工具不足时请明确说明缺失信息，并基于已取得的数据输出本步骤的最佳结果。\n\n");
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
                    // 2026-05-28：优先注入前置步骤「输出数据」结构化段（下游真正需要的明细），
                    // 抠不出（LLM 没按格式输出）时回退整段原文。两者都做截断防 token 爆炸。
                    String structured = extractStepOutputData(depResult);
                    String inject = (structured != null && !structured.isBlank()) ? structured : depResult;
                    sb.append(inject.length() > DEP_INJECT_MAX_CHARS
                            ? inject.substring(0, DEP_INJECT_MAX_CHARS) + "\n...(已截断)" : inject);
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
        sb.append("   - 如果步骤计划提到的首选工具可用但结果不完整，可以换用其它真实可用工具补足；不要把首选工具误认为唯一工具。\n");
        sb.append("   - **严禁**伪造工具调用过程（不要写\"调用搜索引擎工具中...\"\"模拟工具返回...\"\"工具返回JSON结果\"等）\n");
        sb.append("   - **严禁**虚构工具返回数据（不要编造搜索结果列表、URL、JSON 等假数据）\n");
        sb.append("   - 如果有真工具可用并成功调用，正常使用即可\n");
        sb.append("3. 充分利用上面【前置步骤产出】里的真实数据，不要假装看不见或自己重做一遍\n");
        sb.append("4. 如果遇到问题，说明具体的错误信息\n");
        sb.append("5. **先输出给用户看的完整回答**：用清晰的 Markdown（表格/列表/说明）把本步骤成果完整、好读地呈现出来——这部分会实时展示给用户，务必保持丰富、易读，不要省略。\n");
        sb.append("6. **再在回复末尾附结构化执行结果块**（供后续步骤机器读取）。注意：第 5 点给用户看的回答和这里的“输出数据”【两者都必须有】，不要因为下游只读“输出数据”就把上面的回答省成一坨 JSON。格式如下：\n");
        sb.append("   ```\n");
        sb.append("   === 执行结果 ===\n");
        sb.append("   状态: [成功/失败/无工具可用]\n");
        sb.append("   结果描述: [一句话概述本步骤做了什么、关键结论]\n");
        sb.append("   输出数据: [本步骤产出的【完整明细数据】，结构化、可被下游步骤直接使用。\n");
        sb.append("            必须逐条/逐项列全（如逐日数据、逐个条目、每个 URL/数值），\n");
        sb.append("            **严禁只写\"范围/概况/趋势\"这类概括**——后续步骤只读这一段，拿不到明细就无法继续。\n");
        sb.append("            可用 JSON 或清晰的列表/表格。若无工具可用则填\"基于通用知识的建议（非工具检索结果）\"。]\n");
        sb.append("   ```\n\n");
        sb.append("请开始执行这个步骤，并严格按照要求提供详细的执行报告和结果输出。");
        return sb.toString();
    }

    /**
     * 2026-05-28：从一步的完整回复里抽出 "=== 执行结果 ===" 块中的「输出数据」段，
     * 供下游步骤精确引用（而非盲截整段散文，避免把推理/展示文本也塞给下游、并防止盲截砍掉关键明细）。
     * <p>用 lastIndexOf 定位 marker（块约定在末尾，避免命中正文里偶然出现的字样）；
     * 找不到块或抠不出「输出数据」→ 返回 null，调用方回退用整段原文。
     */
    private String extractStepOutputData(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) return null;
        int marker = rawResult.lastIndexOf("=== 执行结果 ===");
        if (marker < 0) return null;
        String block = rawResult.substring(marker);
        // 输出数据是块内最后一个字段，匹配 "输出数据:" 后取到结尾（兼容全角/半角冒号）
        Matcher m = Pattern.compile("输出数据\\s*[:：]\\s*").matcher(block);
        if (!m.find()) return null;
        String data = block.substring(m.end()).trim();
        // 去掉可能的结尾代码围栏 ```
        if (data.endsWith("```")) {
            data = data.substring(0, data.length() - 3).trim();
        }
        return data.isBlank() ? null : data;
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
    private String sendSummaryResult(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, ExecuteCommandEntity request, ChatClient finalWriterChatClient, String finalWriterClientId) {
        String sessionId = request.getSessionId();
        String finalDeliverable = buildFinalDeliverable(dynamicContext, request, finalWriterChatClient, finalWriterClientId);
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
    /**
     * 立即回答（flow 专用整合）：跳过/中止 DAG 执行，基于"用户问题 + 计划 + 已完成子步产出 + 半截思考"直接整合作答。
     * 走 EXECUTOR_CLIENT 的完整整合路径（非单步执行器窄角色）；finalize-tools 开时挂常驻+补充工具并注入清单、追加关思考指令。
     */
    private String finalizeNow(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            AiAgentClientFlowConfigVO execConfig = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode());
            if (execConfig == null) {
                throw new BizException("flow agent missing flow config: " + AiClientTypeEnumVO.EXECUTOR_CLIENT.getCode()
                        + " for agentId=" + request.getAiAgentId());
            }
            ChatClient client = getChatClientByClientId(execConfig.getClientId());
            String clientId = execConfig.getClientId();
            List<ToolCallback> dynamicToolCallbacks = resolveAgentDynamicToolCallbacks(request, clientId);
            boolean attachTools = !dynamicToolCallbacks.isEmpty() && answerNowFinalizeTools;
            String prompt = buildFlowAnswerNowPrompt(dynamicContext, request) + "\n\n" + answerNowNoThinkDirective;
            // 立即回答且挂工具：把常驻+补充工具清单注入 prompt（与正常执行步一致，帮模型选对工具、防幻觉）
            if (attachTools && dynamicAgentToolRegistry != null) {
                cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog catalog =
                        snapshotExecutorToolCatalog(clientId, dynamicToolCallbacks, 3);
                prompt += "\n\n**【可用工具清单】**\n"
                        + renderToolRuntimeForPrompt(catalog, clientId)
                        + "\n如需补足信息可调用上述工具后再作答；不需要则直接作答。";
            }
            prompt = appendCurrentTimeContext(prompt);

            // 立即回答可观测：LLM 调用前写标记行（中断点 + 改写后的完整 finalize 输入），LLM 失败也留痕。
            if (eventLogService != null) {
                try {
                    String partialReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter.getLatestReasoning(request.getSessionId());
                    int doneSteps = 0;
                    for (int i = 1; i <= 50; i++) { Object r = dynamicContext.getValue("step" + i + "Result"); if (r != null) doneSteps++; }
                    String header = "【立即回答触发·flow】中断于 step=" + dynamicContext.getStep()
                            + " | 已完成子步数=" + doneSteps
                            + " | partialReasoningChars=" + (partialReasoning != null ? partialReasoning.length() : 0)
                            + " | 打断前累计token(prompt+completion)=" + dynamicContext.cumulativePromptTokens()
                                + "+" + dynamicContext.cumulativeCompletionTokens()
                            + " | finalizeTools=" + answerNowFinalizeTools
                            + "\n----- 改写后的 finalize 输入 -----\n";
                    eventLogService.log(cn.bugstack.ai.domain.agent.service.execute.EventLogEntry.builder()
                            .sessionId(request.getSessionId())
                            .userId(request.getUserId())
                            .tenantId(request.getTenantId())
                            .agentId(request.getAiAgentId())
                            .billingScope(cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE)
                            .stepName("answer_now_triggered")
                            .stepIndex(dynamicContext.getStep())
                            .inputPrompt(header + prompt)
                            .model("intervention")
                            .latencyMs(0L)
                            .build());
                    log.info("[AnswerNow][flow] event_log marker written: interruptedStep={} doneSteps={}", dynamicContext.getStep(), doneSteps);
                } catch (Exception ex) {
                    log.debug("[AnswerNow][flow] marker log failed: {}", ex.getMessage());
                }
            }

            ChatClient.ChatClientRequestSpec spec = client.prompt()
                    .user(prompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(request))
                            .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(request, "flow-answer-now"))
                            .param("memory_persist_final_turn", true)
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50));
            // 立即回答关思考(reasoning_effort 设到 options) + 工具回调；reasoning_effort 由 Spring AI 序列化进 body（确定性，绕开 filter 跨线程）。
            boolean hasEffort = answerNowReasoningEffort != null && !answerNowReasoningEffort.isBlank();
            if (attachTools || hasEffort) {
                org.springframework.ai.openai.OpenAiChatOptions.Builder ob = org.springframework.ai.openai.OpenAiChatOptions.builder();
                if (attachTools) ob.toolCallbacks(toRequestToolCallbacks(clientId, dynamicToolCallbacks));
                if (hasEffort) ob.reasoningEffort(answerNowReasoningEffort);
                spec = spec.options(ob.build());
            }
            String answer = callStepWithStreaming(spec, dynamicContext, "flow_step4_answer_now", "立即回答",
                    cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.answerNow(attachTools),
                    prompt, request.getSessionId());
            if (answer == null || answer.isBlank()) answer = "（当前可用信息不足，无法立即作答。）";
            answer = stripExecutionWrapper(answer);

            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(answer, request.getSessionId());
            sendSseResult(dynamicContext, result);
            if (conversationTurnMemoryService != null) {
                conversationTurnMemoryService.saveFinalTurn(request, answer);
            }
            triggerLongTermMemoryExtraction(request, answer);
            // episodic / summary：与正常完成路径一致（之前 finalizeNow 漏了这步）
            @SuppressWarnings("unchecked")
            Map<String, String> __steps = (Map<String, String>) dynamicContext.getValue("stepsMap");
            saveEpisodicMemory(request, dynamicContext, __steps != null ? __steps : Collections.emptyMap());
            sendCompleteResult(dynamicContext, request.getSessionId());

            dynamicContext.setStep(dynamicContext.getStep() + 1);
            dynamicContext.setCompleted(true);
            recordTransition("flow_step4_answer_now", dynamicContext);
            log.info("[AnswerNow][flow] 立即回答整合完成 len={}", answer.length());
            return "立即回答完成";
        } catch (Exception e) {
            log.error("[AnswerNow][flow] 立即回答失败", e);
            return "立即回答失败: " + e.getMessage();
        }
    }

    /** 立即回答（flow）：收集用户问题 + 计划(stepsMap) + 已完成子步产出 + 半截思考。 */
    private String buildFlowAnswerNowPrompt(DefaultFlowAgentExecuteStrategyFactory.DynamicContext ctx, ExecuteCommandEntity request) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户在执行过程中点击了【立即回答】，要求基于目前已有的（可能不完整的）信息立刻作答。\n\n");
        sb.append("**用户原始问题:**\n").append(ctx.getCurrentTask() != null ? ctx.getCurrentTask() : request.getMessage()).append("\n\n");

        @SuppressWarnings("unchecked")
        Map<String, String> stepsMap = (Map<String, String>) ctx.getValue("stepsMap");
        if (stepsMap != null && !stepsMap.isEmpty()) {
            sb.append("**已制定的执行计划（部分步骤可能尚未执行）:**\n");
            for (Map.Entry<String, String> e : stepsMap.entrySet()) {
                String v = e.getValue();
                sb.append("- ").append(e.getKey()).append(": ")
                  .append(v != null && v.length() > 200 ? v.substring(0, 200) + "..." : v).append("\n");
            }
            sb.append("\n");
        }

        String stepResults = collectStepResults(ctx);
        if (stepResults != null && !stepResults.isBlank()) {
            sb.append("**已完成步骤的真实产出:**\n").append(stepResults).append("\n\n");
        }

        String partialReasoning = cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter
                .getLatestReasoning(request.getSessionId());
        if (partialReasoning != null && !partialReasoning.isBlank()) {
            String pr = partialReasoning.length() > 4000 ? partialReasoning.substring(0, 4000) + "...(截断)" : partialReasoning;
            sb.append("**你刚才正在进行的思考（被用户中断，可能为半截）:**\n").append(pr).append("\n\n");
        }

        sb.append("**要求:**\n");
        sb.append("1. 立即基于以上信息直接回答用户原始问题，给出尽可能完整、有用的答案。\n");
        sb.append("2. 未执行/信息不足的部分简要说明，不要编造工具结果。\n");
        sb.append("3. 不要输出\"状态/输出数据/Step N\"等流程化内容，直接给成品答案，用清晰 Markdown。\n");
        return sb.toString();
    }

    private String buildFinalDeliverable(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext, ExecuteCommandEntity request, ChatClient finalWriterChatClient, String finalWriterClientId) {
        String stepResults = collectStepResults(dynamicContext);
        if (stepResults.isBlank()) {
            return null;
        }

        String prompt = appendCurrentTimeContext(buildFinalSynthesisPrompt(dynamicContext, stepResults));
        List<ToolCallback> dynamicToolCallbacks = resolveAgentDynamicToolCallbacks(request, finalWriterClientId);
        // 2026-05-07 流式 UX：最终合成步骤独立 step_start/end，折叠为"最终合成 已完成"
        ChatClient.ChatClientRequestSpec specFinal = finalWriterChatClient.prompt()
                .user(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, buildConversationId(request))
                        .param(LTM_RETRIEVAL_QUERY_KEY, buildLtmRetrievalQuery(request, "flow-final-synthesis"))
                        .param("memory_persist_final_turn", true)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50));
        if (!dynamicToolCallbacks.isEmpty()) {
            specFinal = specFinal.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .toolCallbacks(toRequestToolCallbacks(finalWriterClientId, dynamicToolCallbacks))
                    .build());
        }
        String finalAnswer = callStepWithStreaming(
                specFinal, dynamicContext, "flow_step4_final_synthesis", "最终合成",
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies.FLOW_FINAL_SYNTHESIS,
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
