package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunStepSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程执行根节点
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/24 14:35
 */
@Slf4j
@Service("flowRootNode")
public class RootNode extends AbstractExecuteSupport {

    @Resource
    private Step1McpToolsAnalysisNode step1McpToolsAnalysisNode;

    @Resource
    private Step2PlanningNode step2PlanningNode;

    @Resource
    private Step4ExecuteStepsNode step4ExecuteStepsNode;

    @Autowired(required = false)
    private McpToolCatalogService mcpToolCatalogService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 流程执行开始 ====");
        log.info("用户输入: {}", requestParameter.getMessage());
        log.info("会话ID: {}", requestParameter.getSessionId());

        Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap = repository.queryAiAgentClientFlowConfig(requestParameter.getAiAgentId());

        // 客户端对话组
        dynamicContext.setAiAgentClientFlowConfigVOMap(aiAgentClientFlowConfigVOMap);
        // 上下文信息
        dynamicContext.setExecutionHistory(new StringBuilder());
        // 当前任务信息
        dynamicContext.setCurrentTask(effectiveInitialTask(requestParameter));

        if (isRedoRequest(requestParameter)) {
            FlowRedoTarget redoTarget = resolveRedoTarget(requestParameter);
            if (redoTarget == FlowRedoTarget.UNSUPPORTED) {
                throw new cn.bugstack.ai.types.exception.BizException(
                        "Flow Step3 仅负责解析计划，不支持步骤级重做，请选择 Step1、Step2 或具体执行步骤。");
            }
            Integer targetFlowStepNo = redoFlowStepNo(requestParameter);
            String redoUserRequest = buildRedoUserRequestForPrompt(requestParameter);

            dynamicContext.setCurrentTask(redoUserRequest);
            dynamicContext.setValue("flowRedoUserRequestPrompt", redoUserRequest);
            if (redoTarget == FlowRedoTarget.STEP1) {
                dynamicContext.setStep(1);
                return step1McpToolsAnalysisNode.apply(requestParameter, dynamicContext);
            }

            if (redoTarget == FlowRedoTarget.STEP2) {
                String inheritedMcpAnalysis = sourceStepContent(requestParameter, "thinking:工具分析");
                if (inheritedMcpAnalysis == null || inheritedMcpAnalysis.isBlank()) {
                    throw new cn.bugstack.ai.types.exception.BizException(
                            "历史运行缺少 Flow Step1 工具分析快照，无法从 Step2 重做，请从 Step1 重新开始。");
                }
                dynamicContext.setStep(2);
                dynamicContext.setValue("mcpToolsAnalysis", inheritedMcpAnalysis);
                return step2PlanningNode.apply(requestParameter, dynamicContext);
            }

            String redoStepContent = resolveRedoStepContent(requestParameter, targetFlowStepNo);
            Map<String, String> redoStepsMap = buildRedoStepsMap(requestParameter, targetFlowStepNo, redoStepContent);
            Map<Integer, Set<Integer>> fullDependencies = buildRedoStepDependencies(requestParameter, targetFlowStepNo);
            // 恢复旧 run 已申请过的能力，同时继续识别本次修订新增的能力；两者必须并集，不能让旧 need 压掉新要求。
            String snapshotToolNeeds = snapshotExtraToolNeed(requestParameter);
            String inferredToolNeeds = inferRedoToolNeed(
                    redoToolNeedSource(requestParameter, targetFlowStepNo, redoStepContent, redoStepsMap));
            String redoToolNeed = mergeToolNeeds(snapshotToolNeeds, inferredToolNeeds);
            dynamicContext.setStep(4);
            dynamicContext.setValue("stepsMap", redoStepsMap);
            dynamicContext.setValue("stepDependencies", fullDependencies);
            // 当前执行只跑 redo 子图，但新 run 的计划快照必须保留完整图，确保它以后还能再次按步骤重做。
            dynamicContext.setValue("flowPlanSnapshotStepsMap",
                    buildRedoFullPlanSnapshot(requestParameter, redoStepsMap));
            dynamicContext.setValue("flowPlanSnapshotDependencies", fullDependencies);
            dynamicContext.setValue("mcpToolsAnalysis", "历史运行步骤级重做：沿用 redo 子图之外的快照结果，重新执行目标步骤及其依赖后代。");
            dynamicContext.setValue("planningResult", "历史运行步骤级重做：无需重新规划，直接执行用户指定的重做步骤。");
            seedRedoInheritedStepResults(dynamicContext, requestParameter, targetFlowStepNo);
            if (redoToolNeed != null && !redoToolNeed.isBlank()) {
                dynamicContext.setValue("dynamicMissingToolDesc", redoToolNeed);
                dynamicContext.setValue("dynamicToolQuery", redoUserRequest + "\n\n" + redoStepContent);
                if (mcpToolCatalogService != null) {
                    mcpToolCatalogService.setNeeds(requestParameter.getSessionId(), redoToolNeed);
                }
            }
            return step4ExecuteStepsNode.apply(requestParameter, dynamicContext);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return step1McpToolsAnalysisNode;
    }

    private boolean isRedoRequest(ExecuteCommandEntity requestParameter) {
        return requestParameter != null
                && requestParameter.getSourceRunId() != null
                && !requestParameter.getSourceRunId().isBlank()
                && requestParameter.getRedoFromStep() != null;
    }

    private FlowRedoTarget resolveRedoTarget(ExecuteCommandEntity requestParameter) {
        RunStepSnapshot target = sourceRedoStep(requestParameter);
        if (target == null) {
            return FlowRedoTarget.UNSUPPORTED;
        }
        String stepId = target.getStepId() == null ? "" : target.getStepId();
        String title = target.getTitle() == null ? "" : target.getTitle();
        if ("thinking:工具分析".equals(stepId) || title.contains("工具分析")) {
            return FlowRedoTarget.STEP1;
        }
        if ("thinking:步骤规划".equals(stepId) || title.contains("步骤规划")) {
            return FlowRedoTarget.STEP2;
        }
        if ("thinking:计划解析".equals(stepId) || title.contains("计划解析")) {
            return FlowRedoTarget.UNSUPPORTED;
        }
        return stepId.startsWith("flow_step4_execute_step_")
                ? FlowRedoTarget.STEP4_EXECUTION
                : FlowRedoTarget.UNSUPPORTED;
    }

    private RunStepSnapshot sourceRedoStep(ExecuteCommandEntity requestParameter) {
        if (runSnapshotService == null || requestParameter == null
                || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()
                || requestParameter.getRedoFromStep() == null) {
            return null;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null || snapshot.getSteps() == null) {
            return null;
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && requestParameter.getRedoFromStep().equals(step.getOrdinal()))
                .findFirst()
                .orElse(null);
    }

    private String sourceStepContent(ExecuteCommandEntity requestParameter, String stepId) {
        if (runSnapshotService == null || requestParameter == null
                || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()) {
            return null;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null || snapshot.getSteps() == null) {
            return null;
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && stepId.equals(step.getStepId()))
                .map(RunStepSnapshot::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> buildRedoStepsMap(ExecuteCommandEntity requestParameter,
                                                  Integer targetFlowStepNo,
                                                  String resolvedStepContent) {
        Map<String, String> stepsMap = new LinkedHashMap<>();
        int target = targetFlowStepNo != null && targetFlowStepNo > 0 ? targetFlowStepNo : 1;

        // 2026-07-21 修复：redo 第 N 步 = 重跑「目标步骤 + 其所有后代」，而不是只重建目标单步。
        // 后代步骤的输入 = 目标步骤产出，目标一变就必须重算；否则它们会被丢掉，Flow 直接走最终整合。
        Map<Integer, String> originalByNo = originalPlanStepContents(requestParameter);
        Map<Integer, Set<Integer>> depsByNo = originalPlanDependencies(requestParameter);
        Set<Integer> redoSet = depsByNo.isEmpty()
                ? Collections.singleton(target)
                : collectRedoTargets(target, depsByNo);

        List<Integer> ordered = new ArrayList<>(redoSet);
        Collections.sort(ordered);
        for (Integer no : ordered) {
            String displayName = redoStepDisplayName(requestParameter, no);
            String content;
            if (no.intValue() == target) {
                // 目标步骤：注入用户修订指令（原逻辑）
                content = buildTargetStepRedoContent(requestParameter, no, resolvedStepContent, displayName, depsByNo);
            } else {
                // 后代步骤：沿用原始 stepContent 被动重算，不注入用户修订指令
                String orig = originalByNo.get(no);
                content = (orig != null && !orig.isBlank())
                        ? orig
                        : fallbackDescendantContent(no, displayName, depsByNo);
            }
            stepsMap.put("第" + no + "步：" + displayName, content);
        }
        return stepsMap;
    }

    /**
     * 为新 redo run 构造完整计划快照：未重跑旁支沿用源计划，redo 子图替换为本轮实际执行内容。
     * 这样 redo 产生的 run 以后再次 redo 时，仍然拥有完整 DAG，而不是只有本次执行的子图。
     */
    private Map<String, String> buildRedoFullPlanSnapshot(ExecuteCommandEntity requestParameter,
                                                          Map<String, String> redoStepsMap) {
        Map<Integer, String> mergedByNo = new LinkedHashMap<>(originalPlanStepContents(requestParameter));
        if (redoStepsMap != null) {
            for (Map.Entry<String, String> entry : redoStepsMap.entrySet()) {
                Integer stepNo = extractStepNo(entry.getKey());
                if (stepNo != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                    mergedByNo.put(stepNo, entry.getValue());
                }
            }
        }
        Map<String, String> full = new LinkedHashMap<>();
        List<Integer> ordered = new ArrayList<>(mergedByNo.keySet());
        Collections.sort(ordered);
        for (Integer stepNo : ordered) {
            full.put("第" + stepNo + "步：" + redoStepDisplayName(requestParameter, stepNo), mergedByNo.get(stepNo));
        }
        return full.isEmpty() && redoStepsMap != null ? new LinkedHashMap<>(redoStepsMap) : full;
    }

    private Integer extractStepNo(String stepKey) {
        if (stepKey == null || stepKey.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("第(\\d+)步").matcher(stepKey);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 目标重做步骤的内容：沿用旧快照/计划内容为骨架，追加旧输出参考 + 用户本次修订指令。 */
    private String buildTargetStepRedoContent(ExecuteCommandEntity requestParameter, int stepNo,
                                              String resolvedStepContent, String displayName,
                                              Map<Integer, Set<Integer>> depsByNo) {
        StringBuilder step = new StringBuilder();
        if (resolvedStepContent != null && !resolvedStepContent.isBlank()) {
            step.append(resolvedStepContent.trim()).append("\n\n");
        } else {
            Set<Integer> selfDeps = depsByNo != null
                    ? depsByNo.getOrDefault(stepNo, Collections.emptySet())
                    : buildRedoStepDependencies(requestParameter, stepNo).getOrDefault(stepNo, Collections.emptySet());
            step.append("第").append(stepNo).append("步：").append(displayName).append("\n");
            step.append("- **优先级**: HIGH\n");
            step.append("- **目标能力**: 基于历史运行快照和用户本次修订指令，重新生成被指定步骤及其依赖后代所需的新结果\n");
            step.append("- **依赖步骤**: `DEPENDS_ON: ").append(dependsOnText(selfDeps)).append("`\n");
            step.append("- **执行方法**: 不重新做前置工具分析和规划；沿用 redo 子图之外的历史结果，只修正用户指定的问题\n\n");
        }
        if (requestParameter.getRedoTargetStepContextPrompt() != null
                && !requestParameter.getRedoTargetStepContextPrompt().isBlank()) {
            step.append("【被重做步骤的旧输出参考】\n");
            step.append(requestParameter.getRedoTargetStepContextPrompt().trim()).append("\n\n");
        }
        step.append("【用户本次修订指令】\n")
                .append(requestParameter.getMessage() == null ? "" : requestParameter.getMessage().trim())
                .append("\n\n")
                .append("请输出修订后的该步骤结果。若本次修订影响最终答案，请在结果中明确体现，供最终整合步骤使用。");
        return step.toString();
    }

    /** 后代步骤在快照缺原始内容时的兜底骨架（保留依赖行，让 DAG 与前端展示一致）。 */
    private String fallbackDescendantContent(int stepNo, String displayName, Map<Integer, Set<Integer>> depsByNo) {
        Set<Integer> selfDeps = depsByNo != null
                ? depsByNo.getOrDefault(stepNo, Collections.emptySet())
                : Collections.emptySet();
        StringBuilder step = new StringBuilder();
        step.append("第").append(stepNo).append("步：").append(displayName).append("\n");
        step.append("- **优先级**: HIGH\n");
        step.append("- **目标能力**: 前置步骤已被重做，本步骤需基于其新产出重新执行\n");
        step.append("- **依赖步骤**: `DEPENDS_ON: ").append(dependsOnText(selfDeps)).append("`\n");
        step.append("- **执行方法**: 基于依赖步骤重做后的最新产出，重新完成本步骤\n");
        return step.toString();
    }

    /**
     * 从源 run 快照重建原始执行计划的「步骤号 → 原始 stepContent」。
     * 快照里每个 flow_step4_execute_step_N 步都存了 stepContent（含 DEPENDS_ON 行）。
     */
    private Map<Integer, String> originalPlanStepContents(ExecuteCommandEntity requestParameter) {
        Map<Integer, String> byNo = new LinkedHashMap<>();
        if (runSnapshotService == null || requestParameter == null
                || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()) {
            return byNo;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null) {
            return byNo;
        }
        if (snapshot.getFlowPlanSteps() != null && !snapshot.getFlowPlanSteps().isEmpty()) {
            for (Map.Entry<String, String> entry : snapshot.getFlowPlanSteps().entrySet()) {
                Integer stepNo = extractStepNo(entry.getKey());
                if (stepNo != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                    byNo.put(stepNo, entry.getValue());
                }
            }
            if (!byNo.isEmpty()) {
                return byNo;
            }
        }
        if (snapshot.getSteps() == null) {
            return byNo;
        }
        // 兼容 2026-07-21 之前的老快照：只能从已启动步骤的 stepContent 尽力恢复，可能不完整。
        Pattern stepIdPattern = Pattern.compile("flow_step4_execute_step_(\\d+)");
        for (RunStepSnapshot step : snapshot.getSteps()) {
            if (step == null || step.getStepId() == null) {
                continue;
            }
            Matcher m = stepIdPattern.matcher(step.getStepId());
            if (!m.matches()) {
                continue;
            }
            int no = Integer.parseInt(m.group(1));
            String content = step.getStepContent();
            if (content != null && !content.isBlank()) {
                byNo.put(no, content);
            }
        }
        return byNo;
    }

    /** 从源 run 快照重建完整依赖图（步骤号 → 依赖集合），供 redo 卡片展示与 DAG 拓扑使用。 */
    private Map<Integer, Set<Integer>> originalPlanDependencies(ExecuteCommandEntity requestParameter) {
        Map<Integer, Set<Integer>> deps = new LinkedHashMap<>();
        if (runSnapshotService != null && requestParameter != null
                && requestParameter.getSourceRunId() != null && !requestParameter.getSourceRunId().isBlank()) {
            RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
            if (snapshot != null && snapshot.getFlowPlanDependencies() != null
                    && !snapshot.getFlowPlanDependencies().isEmpty()) {
                snapshot.getFlowPlanDependencies().forEach((stepNo, values) -> {
                    if (stepNo != null) {
                        deps.put(stepNo, values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values));
                    }
                });
                return deps;
            }
        }
        for (Map.Entry<Integer, String> e : originalPlanStepContents(requestParameter).entrySet()) {
            deps.put(e.getKey(), parseDependsOnFromContent(e.getValue(), e.getKey()));
        }
        return deps;
    }

    /** 从单个步骤原始内容解析 DEPENDS_ON（与 Step3ParseStepsNode.parseStepDependencies 同一套 regex）。 */
    private Set<Integer> parseDependsOnFromContent(String content, int currentStep) {
        Set<Integer> deps = new TreeSet<>();
        if (content == null || content.isBlank()) {
            return deps;
        }
        String payload = null;
        Matcher mainM = Pattern.compile(
                "(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?\\s*DEPENDS_ON\\s*[：:]\\s*([^\\n]+?)\\s*(?:\\*+)?\\s*$").matcher(content);
        if (mainM.find()) {
            payload = mainM.group(1).trim();
        } else {
            Matcher legM = Pattern.compile(
                    "(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?\\s*依赖步骤\\s*(?:\\*+)?\\s*[：:]\\s*([^\\n]+?)\\s*$").matcher(content);
            if (legM.find()) {
                payload = legM.group(1).trim();
            }
        }
        if (payload == null) {
            return deps;
        }
        String cleaned = payload.replaceAll("\\*+", "").trim();
        if (cleaned.equalsIgnoreCase("NONE") || cleaned.equalsIgnoreCase("无")
                || cleaned.equalsIgnoreCase("N/A") || cleaned.isEmpty()) {
            return deps;
        }
        Matcher nm = Pattern.compile("(\\d+)").matcher(cleaned);
        while (nm.find()) {
            int n = Integer.parseInt(nm.group(1));
            if (n != currentStep) {
                deps.add(n);
            }
        }
        return deps;
    }

    /** 收集「目标步骤 + 所有（传递）依赖它的后代」。fixpoint 迭代，避免依赖图无拓扑序时的问题。 */
    private Set<Integer> collectRedoTargets(int target, Map<Integer, Set<Integer>> deps) {
        Set<Integer> result = new LinkedHashSet<>();
        result.add(target);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Integer, Set<Integer>> e : deps.entrySet()) {
                Integer stepNo = e.getKey();
                if (stepNo == null || result.contains(stepNo)) {
                    continue;
                }
                for (Integer d : e.getValue()) {
                    if (result.contains(d)) {
                        result.add(stepNo);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    private Integer redoFlowStepNo(ExecuteCommandEntity requestParameter) {
        RunStepSnapshot target = sourceRedoStep(requestParameter);
        if (target != null && target.getStepId() != null) {
            Matcher stepIdMatcher = Pattern.compile("flow_step4_execute_step_(\\d+)").matcher(target.getStepId());
            if (stepIdMatcher.matches()) {
                return Integer.parseInt(stepIdMatcher.group(1));
            }
        }
        if (requestParameter == null || requestParameter.getRedoTargetStepContextPrompt() == null) {
            return 1;
        }
        Matcher matcher = Pattern.compile("Step4_execute(\\d+)").matcher(requestParameter.getRedoTargetStepContextPrompt());
        if (!matcher.find()) {
            return 1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private Map<Integer, String> buildRedoStepDisplayNames(ExecuteCommandEntity requestParameter, Integer targetFlowStepNo) {
        if (targetFlowStepNo == null || targetFlowStepNo <= 0) {
            return Collections.emptyMap();
        }
        Map<Integer, String> names = new HashMap<>();
        names.put(targetFlowStepNo, redoStepDisplayName(requestParameter, targetFlowStepNo));
        return names;
    }

    private String redoStepDisplayName(ExecuteCommandEntity requestParameter, int stepNo) {
        String fallback = "Step4_execute" + stepNo + " · 第" + stepNo + "步";
        String planTitle = extractPlanStepTitle(requestParameter == null ? null : requestParameter.getRedoContextPrompt(), stepNo);
        if (planTitle != null && !planTitle.isBlank()) {
            return planTitle;
        }
        String target = requestParameter == null ? null : requestParameter.getRedoTargetStepContextPrompt();
        if (target == null || target.isBlank()) {
            return fallback;
        }
        Matcher line = Pattern.compile("(?m)^Step4_execute" + stepNo + "\\s+-\\s+(.+):\\s*$").matcher(target);
        if (line.find()) {
            String title = line.group(1).trim();
            if (!title.isBlank()) {
                return title;
            }
        }
        return fallback;
    }

    private Map<Integer, Set<Integer>> buildRedoStepDependencies(ExecuteCommandEntity requestParameter, Integer targetFlowStepNo) {
        // 2026-07-21 修复：优先返回从快照重建的完整依赖图。卡片展示（FlowPlanReviewService.toReviewSteps /
        // Step4.broadcastStepPending）与 DAG 拓扑都需要完整图，只给目标步骤一条会让前置步骤卡片显示"无依赖"。
        Map<Integer, Set<Integer>> full = originalPlanDependencies(requestParameter);
        if (!full.isEmpty()) {
            return full;
        }
        // 兜底（快照无法重建）：退回仅目标步骤的旧逻辑
        if (targetFlowStepNo == null || targetFlowStepNo <= 0) {
            return Collections.emptyMap();
        }
        Set<Integer> deps = parseDependsOn(requestParameter == null ? null : requestParameter.getRedoContextPrompt(), targetFlowStepNo);
        if (deps.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, Set<Integer>> result = new HashMap<>();
        result.put(targetFlowStepNo, deps);
        return result;
    }

    private Set<Integer> parseDependsOn(String planningContext, int stepNo) {
        if (planningContext == null || planningContext.isBlank()) {
            return Collections.emptySet();
        }
        Matcher sectionMatcher = Pattern.compile("(?s)###\\s*第\\s*" + stepNo + "\\s*步.*?(?=\\n###\\s*第\\s*\\d+\\s*步|\\nStep3\\s*-|\\z)")
                .matcher(planningContext);
        if (!sectionMatcher.find()) {
            return Collections.emptySet();
        }
        String section = sectionMatcher.group();
        Matcher depMatcher = Pattern.compile("DEPENDS_ON\\s*[:：]\\s*`?\\s*([^`\\r\\n]+)").matcher(section);
        if (!depMatcher.find()) {
            return Collections.emptySet();
        }
        String raw = depMatcher.group(1).trim();
        if (raw.equalsIgnoreCase("NONE") || raw.equalsIgnoreCase("NULL") || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<Integer> deps = new HashSet<>();
        Matcher num = Pattern.compile("\\d+").matcher(raw);
        while (num.find()) {
            try {
                deps.add(Integer.parseInt(num.group()));
            } catch (Exception ignored) {
            }
        }
        return deps;
    }

    private String dependsOnText(Set<Integer> deps) {
        if (deps == null || deps.isEmpty()) {
            return "NONE";
        }
        return deps.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("NONE");
    }

    private String resolveRedoStepContent(ExecuteCommandEntity requestParameter, Integer targetFlowStepNo) {
        int stepNo = targetFlowStepNo != null && targetFlowStepNo > 0 ? targetFlowStepNo : 1;
        String fromSnapshot = originalStepContentFromRedisSnapshot(requestParameter, stepNo);
        if (fromSnapshot != null && !fromSnapshot.isBlank()) {
            return fromSnapshot;
        }
        return extractPlanStepSection(requestParameter == null ? null : requestParameter.getRedoContextPrompt(), stepNo);
    }

    private String originalStepContentFromRedisSnapshot(ExecuteCommandEntity requestParameter, int stepNo) {
        if (runSnapshotService == null || requestParameter == null
                || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()) {
            return null;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.getFlowPlanSteps() != null && !snapshot.getFlowPlanSteps().isEmpty()) {
            for (Map.Entry<String, String> entry : snapshot.getFlowPlanSteps().entrySet()) {
                Integer planStepNo = extractStepNo(entry.getKey());
                if (planStepNo != null && planStepNo == stepNo
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        }
        if (snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
            return null;
        }
        String stepId = "flow_step4_execute_step_" + stepNo;
        for (RunStepSnapshot step : snapshot.getSteps()) {
            if (step == null || !stepId.equals(step.getStepId())) {
                continue;
            }
            String value = step.getStepContent();
            return value == null || value.isBlank() ? null : value;
        }
        return null;
    }

    private String extractPlanStepSection(String planningContext, int stepNo) {
        if (planningContext == null || planningContext.isBlank()) {
            return null;
        }
        Matcher sectionMatcher = Pattern.compile("(?s)###\\s*第\\s*" + stepNo + "\\s*步[^\\r\\n]*(?:\\r?\\n).*?(?=\\r?\\n###\\s*第\\s*\\d+\\s*步|\\r?\\nStep3\\s*-|\\z)")
                .matcher(planningContext);
        if (!sectionMatcher.find()) {
            return null;
        }
        String section = sectionMatcher.group().trim();
        return section.isBlank() ? null : section;
    }

    private String extractPlanStepTitle(String planningContext, int stepNo) {
        String section = extractPlanStepSection(planningContext, stepNo);
        if (section == null || section.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("^###\\s*(第\\s*" + stepNo + "\\s*步[^\\r\\n]*)").matcher(section);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replaceAll("\\s+", "").trim();
    }

    private String buildRedoUserRequestForPrompt(ExecuteCommandEntity requestParameter) {
        String original = extractOriginalUserRequest(requestParameter == null ? null : requestParameter.getRedoContextPrompt());
        if (original == null || original.isBlank()) {
            original = requestParameter == null || requestParameter.getMessage() == null ? "" : requestParameter.getMessage().trim();
        }
        String revision = requestParameter == null || requestParameter.getMessage() == null ? "" : requestParameter.getMessage().trim();
        if (revision.isBlank()) {
            return original;
        }
        return original + "\n\n【用户本次修订指令】\n" + revision;
    }

    private String extractOriginalUserRequest(String redoContextPrompt) {
        if (redoContextPrompt == null || redoContextPrompt.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?m)^原始用户请求:\\s*(.+)\\s*$").matcher(redoContextPrompt);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        return value.isBlank() ? null : value;
    }

    /**
     * 从源 run 读回曾经 request_tool 的 capability need，供 redo 重新申请旧能力。
     * 它不是精确 tool identity 快照；常驻工具仍由 ensureArmed 装配。
     */
    private String snapshotExtraToolNeed(ExecuteCommandEntity requestParameter) {
        if (runSnapshotService == null || requestParameter == null
                || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()) {
            return null;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null || snapshot.getExtraToolNeeds() == null || snapshot.getExtraToolNeeds().isEmpty()) {
            return null;
        }
        return String.join("\n", snapshot.getExtraToolNeeds());
    }

    /**
     * 2026-07-21：redo 工具能力推断源 = 待执行集（目标 + 所有后代）的全部步骤内容拼接。
     * 目标步骤用（可能带修订骨架的）resolvedStepContent，后代用快照里的原始 stepContent。
     * 修「redo 纯分析步 → 重跑的后代执行步（需 write_file 等）拿不到工具」。
     */
    private String redoToolNeedSource(ExecuteCommandEntity requestParameter,
                                      Integer targetFlowStepNo,
                                      String targetStepContent,
                                      Map<String, String> redoStepsMap) {
        StringBuilder sb = new StringBuilder();
        if (requestParameter != null && requestParameter.getMessage() != null
                && !requestParameter.getMessage().isBlank()) {
            sb.append(requestParameter.getMessage().trim()).append("\n\n");
        }
        if (redoStepsMap != null && !redoStepsMap.isEmpty()) {
            for (String content : redoStepsMap.values()) {
                if (content != null && !content.isBlank()) {
                    sb.append(content.trim()).append("\n\n");
                }
            }
        } else if (targetStepContent != null && !targetStepContent.isBlank()) {
            sb.append(targetStepContent.trim());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String inferRedoToolNeed(String stepContent) {
        if (stepContent == null || stepContent.isBlank()) {
            return null;
        }
        Set<String> tools = new LinkedHashSet<>();
        Matcher backtick = Pattern.compile("`([A-Za-z0-9_\\-]+)`").matcher(stepContent);
        while (backtick.find()) {
            tools.add(backtick.group(1));
        }
        Matcher plain = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_\\-]{2,})\\b").matcher(stepContent);
        while (plain.find()) {
            String token = plain.group(1);
            if (token.contains("_") || token.contains("-")) {
                tools.add(token);
            }
        }
        String compactContent = stepContent.replaceAll("\\s+", " ").trim();
        if (tools.isEmpty()) {
            return compactContent.length() > 500 ? compactContent.substring(0, 500) : compactContent;
        }
        String excerpt = compactContent.length() > 800 ? compactContent.substring(0, 800) : compactContent;
        return "Flow redo 需要这些工具或等价能力：" + String.join(", ", tools)
                + "；本次修订与待重跑步骤：" + excerpt;
    }

    /** Merge old capability needs with needs inferred from this revision, preserving order and removing duplicates. */
    private String mergeToolNeeds(String... needGroups) {
        Set<String> merged = new LinkedHashSet<>();
        if (needGroups != null) {
            for (String group : needGroups) {
                if (group == null || group.isBlank()) {
                    continue;
                }
                for (String need : group.split("\\R+")) {
                    if (need != null && !need.isBlank()) {
                        merged.add(need.trim());
                    }
                }
            }
        }
        return merged.isEmpty() ? null : String.join("\n", merged);
    }

    private void seedRedoInheritedStepResults(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                             ExecuteCommandEntity requestParameter,
                                             Integer targetFlowStepNo) {
        if (dynamicContext == null || requestParameter == null || targetFlowStepNo == null || targetFlowStepNo <= 0
                || runSnapshotService == null || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()) {
            return;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null || snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
            return;
        }
        // 2026-07-21 修复：待执行集（目标 + 后代）里的步骤会被重做，不能当继承产出；
        // 其余步骤（前置及非后代旁支）的旧产出才种进上下文，供被重做步骤引用。
        Map<Integer, Set<Integer>> depsByNo = originalPlanDependencies(requestParameter);
        Set<Integer> redoSet = depsByNo.isEmpty()
                ? Collections.singleton(targetFlowStepNo)
                : collectRedoTargets(targetFlowStepNo, depsByNo);
        for (RunStepSnapshot step : snapshot.getSteps()) {
            if (step == null || step.getStepNo() == null || redoSet.contains(step.getStepNo())) {
                continue;
            }
            String stepId = step.getStepId();
            if (stepId == null || !stepId.startsWith("flow_step4_execute_step_")) {
                continue;
            }
            String content = step.getContent();
            if (content != null && !content.isBlank()) {
                dynamicContext.setValue("step" + step.getStepNo() + "Result", content);
            }
        }
    }

    private enum FlowRedoTarget {
        STEP1,
        STEP2,
        STEP4_EXECUTION,
        UNSUPPORTED
    }

}
