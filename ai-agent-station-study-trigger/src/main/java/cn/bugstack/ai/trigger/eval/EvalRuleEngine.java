package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EvalRuleEngine {

    public static final String VERSION = "agent-rules-v2";
    private static final Set<String> META_TOOL_NAMES = Set.of("request_tool", "ask_user");

    public RuleResult evaluate(Observation observation) {
        EvalCaseConfig spec = observation.getConfig() == null
                ? new EvalCaseConfig().normalized() : observation.getConfig().normalized();
        Signals signals = collectSignals(observation.getEvents());
        RuleResult result = new RuleResult();
        result.setSignals(signals);
        result.setRouteScore(scoreRoute(spec, observation.getAgentId()));
        result.setAnswerScore(scoreAnswer(spec, observation.getAnswer()));
        result.setStepScore(scoreSteps(spec, signals.getStepStartCount(), signals.getEarlyStepToolCalls()));
        result.setToolScore(scoreTools(spec, signals.getToolCallCount(), signals.getToolErrorCount(), signals.getDuplicateToolCalls()));
        result.setGroundingScore(scoreGrounding(spec, signals.getRagEvidenceCount(), signals.getToolCallCount()));
        signals.setGroundingBlockedByRoute(spec.isExpectRag() && signals.getRagEvidenceCount() == 0
                && result.getRouteScore() == 0.0);
        result.setMemoryScore(scoreMemory(spec, observation.getAnswer(), signals.getMemoryEvidenceCount()));
        result.setStabilityScore(scoreStability(observation.getStatus(), signals.getToolCallCount(), signals.getToolErrorCount()));
        result.setEfficiencyScore(scoreEfficiency(spec, observation.getLatencyMs()));
        result.setSafetyScore(scoreSafety(spec, observation.getAnswer()));
        collectWarnings(result, spec, observation);
        double groundingForOverall = signals.isGroundingBlockedByRoute() ? 1.0 : result.getGroundingScore();
        result.setOverallScore(round4(
                0.16 * result.getRouteScore()
                        + 0.22 * result.getAnswerScore()
                        + 0.12 * result.getStepScore()
                        + 0.14 * result.getToolScore()
                        + 0.12 * groundingForOverall
                        + 0.10 * result.getMemoryScore()
                        + 0.08 * result.getStabilityScore()
                        + 0.04 * result.getEfficiencyScore()
                        + 0.02 * result.getSafetyScore()));
        result.setGrade(grade(result.getOverallScore()));
        return result;
    }

    Signals collectSignals(List<RunEventRecord> events) {
        Signals signals = new Signals();
        if (events == null) return signals;
        Map<String, Integer> toolInputs = new LinkedHashMap<>();
        for (RunEventRecord event : events) {
            if (event == null) continue;
            String type = event.getEventType();
            JSONObject payload = payload(event.getPayloadJson());
            if ("step_start".equals(type)) signals.stepStartCount++;
            if ("tool_call_start".equals(type)) {
                String toolName = safe(payload.getString("toolName"));
                if (isMetaToolCall(payload, toolName)) {
                    signals.metaToolCallCount++;
                    continue;
                }
                signals.toolCallCount++;
                String input = safe(payload.getString("inputPreview"));
                toolInputs.merge(toolName + "|" + input, 1, Integer::sum);
                String step = (safe(payload.getString("step")) + " " + safe(payload.getString("displayName"))).toLowerCase();
                if (!step.isBlank() && !isExecutorStep(step)) signals.earlyStepToolCalls++;
            }
            if ("tool_call_error".equals(type)
                    && !isMetaToolCall(payload, safe(payload.getString("toolName")))) signals.toolErrorCount++;
            if ("rag_evidence".equals(type)) signals.ragEvidenceCount += itemCount(payload);
            if ("memory_evidence".equals(type)) {
                signals.memoryEvidenceEventCount++;
                signals.memoryEvidenceCount += itemCount(payload);
            }
        }
        for (Integer count : toolInputs.values()) {
            if (count != null && count > 1) signals.duplicateToolCalls += count - 1;
        }
        return signals;
    }

    private static JSONObject payload(String raw) {
        if (raw == null || raw.isBlank()) return new JSONObject();
        try {
            Object parsed = JSON.parse(raw);
            if (parsed instanceof JSONObject object) return object;
            if (parsed instanceof String text) {
                JSONObject nested = JSON.parseObject(text);
                return nested == null ? new JSONObject() : nested;
            }
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    private static int itemCount(JSONObject payload) {
        JSONArray items = payload.getJSONArray("items");
        return items == null ? 0 : items.size();
    }

    private static boolean isMetaToolCall(JSONObject payload, String toolName) {
        return payload.getBooleanValue("meta")
                || META_TOOL_NAMES.contains(safe(toolName).toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isExecutorStep(String step) {
        return step.contains("执行") || step.contains("回答") || step.contains("最终合成")
                || step.contains("precision_executor") || step.contains("execute_step")
                || step.contains("execute_steps") || step.contains("final_synthesis") || step.contains("fixed");
    }

    private static double scoreRoute(EvalCaseConfig spec, String agentId) {
        Map<String, Double> fit = capabilityFit(agentId);
        double best = 0.0;
        for (String capability : spec.getExpectedCapabilities()) {
            best = Math.max(best, fit.getOrDefault(capability, 0.0));
        }
        if (best > 0) return best;
        if (spec.getExpectedCapabilities().contains("general")) return 0.6;
        if (fit.containsKey("general") && spec.isAllowGeneralFallback()) return 0.6;
        return 0.0;
    }

    private static double scoreAnswer(EvalCaseConfig spec, String answer) {
        String value = safe(answer);
        if (invalidAnswer(value)) return 0.0;
        double score = 1.0;
        if (spec.getMinAnswerLength() > 0 && answerLength(value) < spec.getMinAnswerLength()) score = 0.65;
        for (String keyword : spec.getMustMention()) if (!matchesConstraint(value, keyword)) score -= 0.18;
        for (String keyword : spec.getMustNotMention()) if (matchesConstraint(value, keyword)) score -= 0.25;
        return clamp(score);
    }

    private static double scoreSteps(EvalCaseConfig spec, int steps, int earlyTools) {
        double score = 1.0;
        if (earlyTools > 0) score -= Math.min(0.5, 0.2 * earlyTools);
        if (spec.isSimpleTask() && steps > spec.getMaxSteps()) score -= 0.35;
        else if (steps > spec.getMaxSteps() + 2) score -= 0.2;
        return clamp(score);
    }

    private static double scoreTools(EvalCaseConfig spec, int tools, int errors, int duplicates) {
        double score;
        if (spec.isExpectTools()) score = tools > 0 ? 1.0 : 0.2;
        else if (spec.isAllowTools()) score = tools <= 8 ? 1.0 : 0.8;
        else score = tools == 0 ? 1.0 : (tools <= 2 ? 0.75 : 0.45);
        if (duplicates > 0) score -= Math.min(0.3, duplicates * 0.08);
        if (errors > 0) score -= Math.min(0.2, errors * 0.05);
        return clamp(score);
    }

    private static double scoreGrounding(EvalCaseConfig spec, int ragEvidence, int tools) {
        if (spec.isExpectRag() && ragEvidence == 0) return 0.45;
        if (spec.isExpectTools() && tools == 0) return 0.35;
        if (!spec.isExpectRag() && ragEvidence > 8) return 0.75;
        return 1.0;
    }

    private static double scoreMemory(EvalCaseConfig spec, String answer, int memoryEvidence) {
        if (!spec.isExpectMemory()) return 1.0;
        String value = safe(answer);
        boolean keywordHit = spec.getMustMention().stream().anyMatch(value::contains);
        return memoryEvidence > 0 || keywordHit ? 1.0 : 0.35;
    }

    private static double scoreStability(String status, int tools, int toolErrors) {
        if (status == null) return 0.0;
        if ("PASS".equals(status)) return toolErrors > 0 && tools > 0 ? 0.85 : 1.0;
        if ("RETRY_PASS".equals(status)) return 0.85;
        return 0.0;
    }

    private static double scoreEfficiency(EvalCaseConfig spec, long latencyMs) {
        if (latencyMs <= spec.getMaxLatencyMs()) return 1.0;
        if (latencyMs <= spec.getMaxLatencyMs() * 2) return 0.65;
        return 0.35;
    }

    private static double scoreSafety(EvalCaseConfig spec, String answer) {
        if (!spec.isFinancialSafety()) return 1.0;
        String value = safe(answer);
        return value.contains("不构成投资建议") || value.contains("投资有风险") || value.contains("仅供参考") ? 1.0 : 0.55;
    }

    private static void collectWarnings(RuleResult result, EvalCaseConfig spec, Observation observation) {
        Signals signals = result.getSignals();
        if (signals.isGroundingBlockedByRoute()) {
            result.warnings.add("路由能力不匹配 → RAG 未产生证据；grounding 标记为上游阻断且不重复计入总分");
        } else {
            if (result.getRouteScore() < 1.0) result.warnings.add("路由能力与期望不完全匹配");
            if (spec.isExpectRag() && signals.getRagEvidenceCount() == 0) result.warnings.add("预期 RAG 但未捕获证据");
        }
        String answer = safe(observation.getAnswer());
        if (invalidAnswer(answer)) {
            result.warnings.add("最终答案未捕获、为空或执行失败");
        } else {
            if (spec.getMinAnswerLength() > 0 && answerLength(answer) < spec.getMinAnswerLength()) {
                result.warnings.add("答案长度 " + answerLength(answer) + "，低于本题要求 " + spec.getMinAnswerLength());
            }
            List<String> missing = new ArrayList<>();
            for (String keyword : spec.getMustMention()) if (!matchesConstraint(answer, keyword)) missing.add(keyword);
            if (!missing.isEmpty()) result.warnings.add("缺少必须内容：" + String.join("、", missing));
            List<String> forbidden = new ArrayList<>();
            for (String keyword : spec.getMustNotMention()) if (matchesConstraint(answer, keyword)) forbidden.add(keyword);
            if (!forbidden.isEmpty()) result.warnings.add("出现禁止内容：" + String.join("、", forbidden));
        }
        if (signals.getEarlyStepToolCalls() > 0) result.warnings.add("非执行步骤调用工具 " + signals.getEarlyStepToolCalls() + " 次");
        if (spec.isSimpleTask() && signals.getStepStartCount() > spec.getMaxSteps()) result.warnings.add("简单任务步骤数超限");
        if (spec.isExpectTools() && signals.getToolCallCount() == 0) result.warnings.add("预期调用工具但未捕获工具调用");
        if (signals.getDuplicateToolCalls() > 0) result.warnings.add("重复工具调用 " + signals.getDuplicateToolCalls() + " 次");
        if (observation.getLatencyMs() > spec.getMaxLatencyMs()) result.warnings.add("耗时超过预算");
        if (spec.isFinancialSafety() && result.getSafetyScore() < 1.0) result.warnings.add("理财回答缺少风险声明");
    }

    private static boolean invalidAnswer(String value) {
        return value.isBlank() || value.contains("(无响应)") || value.contains("执行异常") || value.contains("超时");
    }

    private static int answerLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean matchesConstraint(String answer, String constraint) {
        String normalizedAnswer = normalizeText(answer);
        for (String alternative : safe(constraint).split("\\|")) {
            String normalizedAlternative = normalizeText(alternative);
            if (!normalizedAlternative.isBlank() && normalizedAnswer.contains(normalizedAlternative)) return true;
        }
        return false;
    }

    private static String normalizeText(String value) {
        return Normalizer.normalize(safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    static Map<String, Double> capabilityFit(String agentId) {
        if (agentId == null) return Map.of();
        return switch (agentId) {
            case "8001" -> Map.of("time_management", 0.8, "personal_growth", 0.8, "travel", 0.6);
            case "8002" -> Map.of("cooking", 1.0, "fitness", 0.7);
            case "8003" -> Map.of("reading", 1.0, "learning_path", 0.75);
            case "8004" -> Map.of("fitness", 1.0);
            case "8005" -> Map.of("translation", 1.0, "writing", 0.75);
            case "8006" -> Map.of("time_management", 1.0);
            case "8007" -> Map.of("finance", 1.0);
            case "8008" -> Map.of("learning_path", 1.0, "code", 0.8, "reading", 0.7);
            case "8009" -> Map.of("writing", 1.0, "personal_growth", 0.7);
            case "8010" -> Map.of("travel", 1.0, "time_management", 0.6);
            case "8011" -> Map.of("general", 1.0);
            case "8012" -> Map.of("general", 1.0, "personal_growth", 0.8, "learning_path", 0.8, "science", 0.8);
            case "8013" -> Map.of("general", 1.0, "code", 0.8, "tech_blog", 0.8);
            case "8014" -> Map.of("tech_blog", 1.0, "code", 0.8);
            case "8015", "8016" -> Map.of("code", 0.7);
            default -> Map.of();
        };
    }

    private static String grade(double score) {
        if (score >= 0.85) return "A";
        if (score >= 0.70) return "B";
        if (score >= 0.55) return "C";
        return "D";
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
    private static double round4(double value) { return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue(); }
    private static String safe(String value) { return value == null ? "" : value; }

    @Data
    @Builder
    public static class Observation {
        private EvalCaseConfig config;
        private String agentId;
        private String strategy;
        private String status;
        private String answer;
        private long latencyMs;
        private List<RunEventRecord> events;
    }

    @Data
    public static class Signals {
        private int stepStartCount;
        private int toolCallCount;
        private int toolErrorCount;
        private int duplicateToolCalls;
        private int ragEvidenceCount;
        private int memoryEvidenceEventCount;
        private int memoryEvidenceCount;
        private int earlyStepToolCalls;
        private int metaToolCallCount;
        /** RAG 未执行由明确的路由能力不匹配阻断，grounding 原始分保留但不重复影响总分。 */
        private boolean groundingBlockedByRoute;
    }

    @Data
    public static class RuleResult {
        private double routeScore;
        private double answerScore;
        private double stepScore;
        private double toolScore;
        private double groundingScore;
        private double memoryScore;
        private double stabilityScore;
        private double efficiencyScore;
        private double safetyScore;
        private double overallScore;
        private String grade;
        private Signals signals;
        private List<String> warnings = new ArrayList<>();
    }
}
