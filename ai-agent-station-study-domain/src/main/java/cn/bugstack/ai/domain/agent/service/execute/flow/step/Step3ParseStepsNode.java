package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.FlowPlanReviewService;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 步骤3：规划步骤解析节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/25 11:00
 */
@Slf4j
@Service
public class Step3ParseStepsNode extends AbstractExecuteSupport {

    @Resource
    private Step4ExecuteStepsNode step4ExecuteStepsNode;

    @Autowired(required = false)
    private FlowPlanReviewService flowPlanReviewService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n--- 步骤3: 规划步骤解析 ---");
        
        String planningResult = dynamicContext.getValue("planningResult");
        
        if (planningResult == null || planningResult.trim().isEmpty()) {
            log.warn("规划结果为空，无法解析步骤");
            throw new RuntimeException("规划结果为空，无法解析步骤");
        }
        
        Map<String, String> stepsMap = parseExecutionSteps(planningResult);

        // 2026-07-02 兜底：规划未产出可解析的"第N步"步骤时（如建议/知识类题被规划步写成散文，
        // 尤其早期 8013 通用 prompt 缺规划师约束），不要吐"成功解析 0 个执行步骤"的空 artifact，
        // 而是把整段规划内容当作单个步骤交给 Step4 合成，保证最终仍有答案。
        // 根因层已在规划 system prompt(8013_p2 → 专职规划师)修复，此处为防任何 agent 复发的结构兜底。
        if (stepsMap.isEmpty()) {
            log.warn("[flow-step3] 规划未解析出任何步骤(0步)，兜底为单步交 Step4 合成，避免空答案。planningLen={}",
                    planningResult.length());
            stepsMap.put("第1步", "第1步：根据下述规划内容直接完成用户任务并给出最终答案\n" + planningResult);
        }

        // 2026-05-07 DAG 依赖解析：从每个 step 内容里抽出"依赖：第X步" / "需要先完成第X步"
        // 类型 Map<stepNumber, Set<依赖的stepNumber>>，无依赖的 step 是空 set（可立即执行）
        Map<Integer, Set<Integer>> stepDependencies = parseStepDependencies(stepsMap);
        log.info("成功解析 {} 个执行步骤；依赖关系：{}", stepsMap.size(), stepDependencies);

        // 保存解析结果到上下文
        dynamicContext.setValue("stepsMap", stepsMap);
        dynamicContext.setValue("stepDependencies", stepDependencies);
        // P1.2.2：旁路镜像（flow 路径）；存解析后的步骤 map
        mirrorToWorkingMemory(requestParameter.getSessionId(), "flow.step3.stepsMap", stepsMap);
        mirrorToWorkingMemory(requestParameter.getSessionId(), "flow.step3.stepDependencies", stepDependencies);
        
        // 构建解析结果摘要
        StringBuilder parseResult = new StringBuilder();
        parseResult.append("## 步骤解析结果\n\n");
        parseResult.append(String.format("成功解析 %d 个执行步骤：\n\n", stepsMap.size()));
        
        for (Map.Entry<String, String> entry : stepsMap.entrySet()) {
            parseResult.append(String.format("- **%s**: %s\n", 
                entry.getKey(), 
                entry.getValue().split("\n")[0])); // 只显示标题部分
        }
        
        // 发送SSE结果
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createAnalysisSubResult(
                dynamicContext.getStep(), 
                "analysis_progress", 
                parseResult.toString(), 
                requestParameter.getSessionId());
        sendSseResult(dynamicContext, result);
        
        // 更新步骤
        dynamicContext.setStep(dynamicContext.getStep() + 1);

        recordTransition("flow_step3_parse_steps", dynamicContext);
        if (flowPlanReviewService != null
                && flowPlanReviewService.tryPauseForReview(requestParameter, dynamicContext, stepsMap, stepDependencies)) {
            return "flow plan review required";
        }
        return router(requestParameter, dynamicContext);
    }

    /**
     * 解析执行步骤。
     * <p>2026-05-07：扩展支持 3 种格式（按特异性 → 通用性逐次尝试）：
     * <ol>
     *   <li>"### 第N步：xxx\n详情..." — 带 markdown header 的详情块</li>
     *   <li>"^第N步：xxx\n优先级: ...\n依赖步骤: ..." — 纯文本详情块（行首锚定，避开 [ ] 概览行）</li>
     *   <li>"[ ] 第N步：xxx" — 仅有 checkbox 概览，没有详情时的最后兜底</li>
     * </ol>
     * <p>关键：前两种能拿到 body（含"依赖步骤"行），DAG 解析才有数据。
     */
    private Map<String, String> parseExecutionSteps(String planningResult) {
        Map<String, String> stepsMap = new LinkedHashMap<>();
        if (planningResult == null || planningResult.trim().isEmpty()) return stepsMap;

        try {
            // 策略 1：### 前缀的详情块
            Pattern p1 = Pattern.compile(
                    "###\\s+(第(\\d+)步[：:][^\\n]+)\\n?([\\s\\S]*?)(?=###\\s+第\\d+步[：:]|$)");
            Matcher m = p1.matcher(planningResult);
            while (m.find()) {
                String number = m.group(2);
                String title = m.group(1).trim();
                String body = m.group(3).trim();
                stepsMap.put("第" + number + "步", title + "\n" + body);
                log.debug("[parse-strategy1 ###] step {} body length={}", number, body.length());
            }
            if (!stepsMap.isEmpty()) {
                log.info("成功解析 {} 个执行步骤（### 详情格式）", stepsMap.size());
                return stepsMap;
            }

            // 策略 2：plain "^第N步：" 详情块（行首锚定，不会误吃 "[ ] 第N步" 概览行）
            // 详情段以"第N步："开头，后跟若干行属性（优先级/依赖步骤/...），直到下一个"第N+1步："或文末
            Pattern p2 = Pattern.compile(
                    "(?m)^(第(\\d+)步[：:][^\\n]+)\\n([\\s\\S]*?)(?=(?:^第\\d+步[：:])|\\Z)");
            m = p2.matcher(planningResult);
            while (m.find()) {
                String number = m.group(2);
                String title = m.group(1).trim();
                String body = m.group(3).trim();
                stepsMap.put("第" + number + "步", title + "\n" + body);
                log.debug("[parse-strategy2 plain] step {} body length={}", number, body.length());
            }
            if (!stepsMap.isEmpty()) {
                log.info("成功解析 {} 个执行步骤（plain 详情格式）", stepsMap.size());
                return stepsMap;
            }

            // 策略 3 兜底：仅概览（[ ] 第N步：xxx），无 body → 没有依赖信息可用
            Pattern p3 = Pattern.compile("\\[\\s*\\]\\s*(第(\\d+)步[：:][^\\n]+)");
            m = p3.matcher(planningResult);
            while (m.find()) {
                String number = m.group(2);
                String title = m.group(1).trim();
                stepsMap.put("第" + number + "步", title);
                log.debug("[parse-strategy3 overview-only] step {} (no body)", number);
            }
            if (!stepsMap.isEmpty()) {
                log.warn("仅匹配到 {} 个步骤概览（无详情），DAG 依赖解析将退化为全并行", stepsMap.size());
            }
        } catch (Exception e) {
            log.error("解析规划结果时发生错误", e);
        }

        return stepsMap;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return step4ExecuteStepsNode;
    }

    /**
     * 2026-05-07 DAG 依赖解析。
     * <p>主格式（Step2 prompt 强制约束）：
     * <ul>
     *   <li>{@code DEPENDS_ON: NONE} — 无依赖</li>
     *   <li>{@code DEPENDS_ON: 1} — 依赖第1步</li>
     *   <li>{@code DEPENDS_ON: 1,2,3} — 依赖多个步骤</li>
     * </ul>
     * <p>容错兼容（LLM 偶尔不听话时）：
     * <ul>
     *   <li>"依赖步骤[：:]\\s*第?N步?[、,，]?..." 中文老格式</li>
     *   <li>"无" / "none" / "N/A" → 空依赖</li>
     * </ul>
     * 装饰可忽略：markdown bold (**...**) / 列表 bullet (- ) / 空白
     */
    private Map<Integer, Set<Integer>> parseStepDependencies(Map<String, String> stepsMap) {
        Map<Integer, Set<Integer>> deps = new LinkedHashMap<>();
        if (stepsMap == null || stepsMap.isEmpty()) return deps;

        // 主格式：DEPENDS_ON: ... （大小写不敏感，容忍空格）
        Pattern dependsOnLine = Pattern.compile(
                "(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?\\s*DEPENDS_ON\\s*[：:]\\s*([^\\n]+?)\\s*(?:\\*+)?\\s*$");
        // 容错老格式：依赖步骤: ... 或 **依赖步骤**: ...（中文）
        Pattern legacyLine = Pattern.compile(
                "(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?\\s*依赖步骤\\s*(?:\\*+)?\\s*[：:]\\s*([^\\n]+?)\\s*$");
        Pattern numberOnly = Pattern.compile("(\\d+)");

        for (Map.Entry<String, String> entry : stepsMap.entrySet()) {
            Matcher numM = Pattern.compile("第(\\d+)步").matcher(entry.getKey());
            if (!numM.find()) continue;
            int currentStep = Integer.parseInt(numM.group(1));

            Set<Integer> currentDeps = new TreeSet<>();
            String content = entry.getValue();

            // 优先匹配主格式
            Matcher mainM = dependsOnLine.matcher(content);
            String depPayload = null;
            String matchedSource = null;
            if (mainM.find()) {
                depPayload = mainM.group(1).trim();
                matchedSource = "DEPENDS_ON";
            } else {
                Matcher legM = legacyLine.matcher(content);
                if (legM.find()) {
                    depPayload = legM.group(1).trim();
                    matchedSource = "依赖步骤";
                }
            }

            if (depPayload != null) {
                // 移除装饰 / 检查"无"
                String cleaned = depPayload.replaceAll("\\*+", "").trim();
                if (cleaned.equalsIgnoreCase("NONE")
                        || cleaned.equalsIgnoreCase("无")
                        || cleaned.equalsIgnoreCase("N/A")
                        || cleaned.isEmpty()) {
                    log.debug("[DAG-Parse] step {} via {}: NONE", currentStep, matchedSource);
                } else {
                    Matcher nm = numberOnly.matcher(cleaned);
                    while (nm.find()) {
                        int n = Integer.parseInt(nm.group(1));
                        if (n != currentStep) currentDeps.add(n);
                    }
                    log.info("[DAG-Parse] step {} via {} payload='{}' → deps {}",
                            currentStep, matchedSource, cleaned, currentDeps);
                }
            } else {
                log.warn("[DAG-Parse] step {} 未识别到依赖行（既无 DEPENDS_ON 也无 依赖步骤），默认无依赖", currentStep);
            }
            deps.put(currentStep, currentDeps);
        }
        return deps;
    }

}
