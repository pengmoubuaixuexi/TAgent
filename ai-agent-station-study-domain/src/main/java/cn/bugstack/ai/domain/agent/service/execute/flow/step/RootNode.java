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

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
            String redoToolNeed = inferRedoToolNeed(redoStepContent);
            dynamicContext.setStep(4);
            dynamicContext.setValue("stepsMap", buildRedoStepsMap(requestParameter, targetFlowStepNo, redoStepContent));
            dynamicContext.setValue("stepDependencies", buildRedoStepDependencies(requestParameter, targetFlowStepNo));
            dynamicContext.setValue("mcpToolsAnalysis", "历史运行步骤级重做：沿用目标步骤之前的快照上下文，直接从目标步骤重新执行。");
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
        int stepNo = targetFlowStepNo != null && targetFlowStepNo > 0 ? targetFlowStepNo : 1;
        String displayName = redoStepDisplayName(requestParameter, stepNo);
        StringBuilder step = new StringBuilder();
        if (resolvedStepContent != null && !resolvedStepContent.isBlank()) {
            step.append(resolvedStepContent.trim()).append("\n\n");
        } else {
            step.append("第").append(stepNo).append("步：").append(displayName).append("\n");
            step.append("- **优先级**: HIGH\n");
            step.append("- **目标能力**: 基于历史运行快照和用户本次修订指令，重新生成被指定步骤及其后续整合所需的新结果\n");
            step.append("- **依赖步骤**: `DEPENDS_ON: ").append(dependsOnText(buildRedoStepDependencies(requestParameter, stepNo).get(stepNo))).append("`\n");
            step.append("- **执行方法**: 不重新做前置工具分析和规划；沿用历史快照中目标步骤之前的内容，只修正用户指定的问题\n\n");
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
        stepsMap.put("第" + stepNo + "步：" + displayName, step.toString());
        return stepsMap;
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
        if (snapshot == null || snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
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
        if (tools.isEmpty()) {
            return stepContent.length() > 500 ? stepContent.substring(0, 500) : stepContent;
        }
        return "Flow redo 需要恢复原步骤可用工具能力，优先匹配这些工具或等价能力：" + String.join(", ", tools)
                + "\n目标步骤内容：" + (stepContent.length() > 800 ? stepContent.substring(0, 800) : stepContent);
    }

    private void seedRedoInheritedStepResults(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                             ExecuteCommandEntity requestParameter,
                                             Integer targetFlowStepNo) {
        if (dynamicContext == null || requestParameter == null || targetFlowStepNo == null || targetFlowStepNo <= 1
                || runSnapshotService == null || requestParameter.getSourceRunId() == null || requestParameter.getSourceRunId().isBlank()) {
            return;
        }
        RunSnapshot snapshot = runSnapshotService.find(requestParameter.getSourceRunId()).orElse(null);
        if (snapshot == null || snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
            return;
        }
        for (RunStepSnapshot step : snapshot.getSteps()) {
            if (step == null || step.getStepNo() == null || step.getStepNo() >= targetFlowStepNo) {
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
