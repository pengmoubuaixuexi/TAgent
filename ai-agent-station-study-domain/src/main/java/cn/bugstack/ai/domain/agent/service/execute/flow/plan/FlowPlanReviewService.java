package cn.bugstack.ai.domain.agent.service.execute.flow.plan;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class FlowPlanReviewService {

    private static final Pattern STEP_KEY_PATTERN = Pattern.compile("\\u7b2c\\s*(\\d+)\\s*\\u6b65");
    private static final Pattern DEPENDS_LINE_PATTERN = Pattern.compile("(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?\\s*(DEPENDS_ON|\\u4f9d\\u8d56\\u6b65\\u9aa4)\\s*(?:\\*+)?\\s*[:\\uff1a].*$");
    private static final Pattern PLAN_HEADER_PATTERN = Pattern.compile("(?is)^\\s*#{0,6}\\s*\\u7b2c\\s*\\d+\\s*\\u6b65\\s*[:\\uff1a].*(?:\\r?\\n|$)");

    @Autowired(required = false)
    private FlowPlanReviewStateStore stateStore;

    @Autowired(required = false)
    private McpToolCatalogService mcpToolCatalogService;

    @Value("${agent.flow.plan-review.enabled:false}")
    private boolean enabled;

    @Value("${agent.flow.plan-review.ttl-seconds:7200}")
    private long ttlSeconds;

    @Value("${agent.flow.plan-review.max-steps:50}")
    private int maxSteps;

    public boolean tryPauseForReview(ExecuteCommandEntity request,
                                     DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                     Map<String, String> stepsMap,
                                     Map<Integer, Set<Integer>> stepDependencies) {
        if (!isReviewEnabled(request) || stateStore == null || request == null || dynamicContext == null || stepsMap == null || stepsMap.isEmpty()) {
            return false;
        }
        ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
        if (emitter == null) {
            return false;
        }

        String runId = firstNonBlank(request.getRunId(), dynamicContext.getValue("runId"));
        if (runId == null || runId.isBlank()) {
            runId = request.getSessionId() + "-run-" + java.util.UUID.randomUUID();
            request.setRunId(runId);
            dynamicContext.setValue("runId", runId);
        }

        long now = System.currentTimeMillis();
        long effectiveTtlSeconds = Math.max(60, ttlSeconds);
        List<FlowPlanReviewStep> reviewSteps = toReviewSteps(stepsMap, stepDependencies);
        FlowPlanReviewValidationResult validation = validateSteps(reviewSteps);
        FlowPlanReviewState state = FlowPlanReviewState.builder()
                .runId(runId)
                .sessionId(request.getSessionId())
                .agentId(request.getAiAgentId())
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .originalMessage(request.getMessage())
                .planningResult(dynamicContext.getValue("planningResult"))
                .mcpToolsAnalysis(dynamicContext.getValue("mcpToolsAnalysis"))
                .mcpNeeds(mcpToolCatalogService != null ? mcpToolCatalogService.needsFor(request.getSessionId()) : null)
                .steps(reviewSteps)
                .createdAt(now)
                .expiresAt(now + effectiveTtlSeconds * 1000)
                .build();

        try {
            stateStore.save(state, Duration.ofSeconds(effectiveTtlSeconds));
        } catch (Exception e) {
            log.warn("[FlowPlanReview] Redis save failed, continue without review runId={} err={}", runId, e.getMessage());
            return false;
        }
        dynamicContext.setValue("planReviewPaused", Boolean.TRUE);
        sendPlanReviewRequired(emitter, state, validation, effectiveTtlSeconds);
        log.info("[FlowPlanReview] paused runId={} sessionId={} steps={} ttl={}s",
                runId, request.getSessionId(), state.getSteps().size(), effectiveTtlSeconds);
        return true;
    }

    public FlowPlanReviewPreparedPlan prepareApprovedPlan(String runId, String sessionId, List<FlowPlanReviewStep> submittedSteps) {
        if (runId == null || runId.isBlank()) {
            return FlowPlanReviewPreparedPlan.failed("missing_run_id", List.of("runId is required"));
        }
        if (stateStore == null) {
            return FlowPlanReviewPreparedPlan.failed("plan_review_disabled", List.of("Plan review is not enabled"));
        }
        Optional<FlowPlanReviewState> optionalState = stateStore.find(runId);
        if (optionalState.isEmpty()) {
            return FlowPlanReviewPreparedPlan.failed("plan_expired", List.of("The pending plan has expired or was already consumed"));
        }
        FlowPlanReviewState state = optionalState.get();
        long now = System.currentTimeMillis();
        if (state.getExpiresAt() != null && state.getExpiresAt() < now) {
            stateStore.delete(runId);
            return FlowPlanReviewPreparedPlan.failed("plan_expired", List.of("The pending plan has expired; please generate a fresh plan"));
        }
        if (sessionId != null && !sessionId.isBlank()
                && state.getSessionId() != null && !state.getSessionId().equals(sessionId)) {
            return FlowPlanReviewPreparedPlan.failed("session_mismatch", List.of("runId does not belong to the current session"));
        }

        FlowPlanReviewValidationResult validation = validateSteps(normalizeSubmittedSteps(submittedSteps));
        if (!validation.isValid()) {
            return FlowPlanReviewPreparedPlan.builder()
                    .ready(false)
                    .errorCode("invalid_plan")
                    .errors(validation.getErrors())
                    .warnings(validation.getWarnings())
                    .state(state)
                    .validation(validation)
                    .build();
        }
        return FlowPlanReviewPreparedPlan.builder()
                .ready(true)
                .errors(List.of())
                .warnings(validation.getWarnings())
                .state(state)
                .validation(validation)
                .build();
    }

    public void deletePendingPlan(String runId) {
        stateStore.delete(runId);
    }

    public FlowPlanReviewValidationResult validateSteps(List<FlowPlanReviewStep> rawSteps) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> stepsMap = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> depsMap = new LinkedHashMap<>();

        if (rawSteps == null || rawSteps.isEmpty()) {
            errors.add("Plan must contain at least one step");
            return validation(errors, warnings, stepsMap, depsMap);
        }
        if (rawSteps.size() > Math.max(1, maxSteps)) {
            errors.add("Plan has too many steps: " + rawSteps.size() + ", max " + maxSteps);
        }

        List<FlowPlanReviewStep> steps = normalizeSubmittedSteps(rawSteps);
        Set<Integer> seen = new HashSet<>();
        Set<Integer> existing = new HashSet<>();
        for (FlowPlanReviewStep step : steps) {
            Integer stepNo = step.getStepNo();
            if (stepNo == null || stepNo <= 0) {
                errors.add("Each step must have a positive stepNo");
                continue;
            }
            if (!seen.add(stepNo)) {
                errors.add("Duplicate stepNo: " + stepNo);
            }
            existing.add(stepNo);
        }
        for (int i = 0; i < steps.size(); i++) {
            int expected = i + 1;
            Integer actual = steps.get(i).getStepNo();
            if (actual == null || actual != expected) {
                errors.add("Step numbers must be continuous from 1 to " + steps.size());
                break;
            }
        }

        for (FlowPlanReviewStep step : steps) {
            int stepNo = step.getStepNo() == null ? -1 : step.getStepNo();
            String title = cleanOneLine(step.getTitle());
            String content = step.getContent() == null ? "" : step.getContent().trim();
            if (title.isBlank()) {
                title = titleFromContent(content, stepNo);
            }
            if (title.isBlank()) {
                errors.add("Step " + stepNo + " title is required");
            }
            if (content.isBlank()) {
                errors.add("Step " + stepNo + " content is required");
            }

            Set<Integer> deps = new TreeSet<>();
            if (step.getDependsOn() != null) {
                for (Integer dep : step.getDependsOn()) {
                    if (dep == null) {
                        continue;
                    }
                    if (dep == stepNo) {
                        errors.add("Step " + stepNo + " cannot depend on itself");
                    } else if (!existing.contains(dep)) {
                        errors.add("Step " + stepNo + " depends on missing step " + dep);
                    } else {
                        deps.add(dep);
                    }
                }
            }
            depsMap.put(stepNo, deps);
            stepsMap.put(stepKey(stepNo), buildStepContent(stepNo, title, content, deps));
        }

        if (!hasCycle(steps, depsMap)) {
            return validation(errors, warnings, stepsMap, depsMap);
        }
        errors.add("Plan dependencies contain a cycle");
        return validation(errors, warnings, stepsMap, depsMap);
    }

    private void sendPlanReviewRequired(ResponseBodyEmitter emitter,
                                        FlowPlanReviewState state,
                                        FlowPlanReviewValidationResult validation,
                                        long effectiveTtlSeconds) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", state.getRunId());
            payload.put("sessionId", state.getSessionId());
            payload.put("agentId", state.getAgentId());
            payload.put("steps", state.getSteps());
            payload.put("createdAt", state.getCreatedAt());
            payload.put("expiresAt", state.getExpiresAt());
            payload.put("expiresInSeconds", effectiveTtlSeconds);
            payload.put("valid", validation != null && validation.isValid());
            payload.put("validationErrors", validation != null ? validation.getErrors() : List.of());
            payload.put("validationWarnings", validation != null ? validation.getWarnings() : List.of());
            synchronized (emitter) {
                emitter.send("event: plan_review_required\ndata: " + JSON.toJSONString(payload) + "\n\n");
            }
        } catch (Exception e) {
            log.warn("[FlowPlanReview] failed to emit review event runId={} err={}", state.getRunId(), e.getMessage());
        }
    }

    private List<FlowPlanReviewStep> toReviewSteps(Map<String, String> stepsMap, Map<Integer, Set<Integer>> stepDependencies) {
        List<FlowPlanReviewStep> steps = new ArrayList<>();
        int fallbackNo = 1;
        for (Map.Entry<String, String> entry : stepsMap.entrySet()) {
            int stepNo = extractStepNo(entry.getKey(), fallbackNo);
            String content = entry.getValue() == null ? "" : entry.getValue();
            String strippedContent = stripDependencyLines(content).trim();
            String title = titleFromContent(strippedContent, stepNo);
            Set<Integer> deps = stepDependencies != null
                    ? stepDependencies.getOrDefault(stepNo, Collections.emptySet())
                    : Collections.emptySet();
            steps.add(FlowPlanReviewStep.builder()
                    .stepNo(stepNo)
                    .title(title)
                    .content(contentForReview(strippedContent, title, stepNo))
                    .dependsOn(new ArrayList<>(new TreeSet<>(deps)))
                    .build());
            fallbackNo++;
        }
        steps.sort((a, b) -> Integer.compare(nullSafeStepNo(a), nullSafeStepNo(b)));
        return steps;
    }

    private List<FlowPlanReviewStep> normalizeSubmittedSteps(List<FlowPlanReviewStep> rawSteps) {
        if (rawSteps == null) {
            return List.of();
        }
        List<FlowPlanReviewStep> normalized = new ArrayList<>();
        for (int i = 0; i < rawSteps.size(); i++) {
            FlowPlanReviewStep raw = rawSteps.get(i);
            if (raw == null) {
                continue;
            }
            int stepNo = raw.getStepNo() != null ? raw.getStepNo() : i + 1;
            List<Integer> deps = raw.getDependsOn() == null ? List.of() : raw.getDependsOn();
            normalized.add(FlowPlanReviewStep.builder()
                    .stepNo(stepNo)
                    .title(cleanOneLine(raw.getTitle()))
                    .content(raw.getContent() == null ? "" : raw.getContent().trim())
                    .dependsOn(new ArrayList<>(deps))
                    .build());
        }
        normalized.sort((a, b) -> Integer.compare(nullSafeStepNo(a), nullSafeStepNo(b)));
        return normalized;
    }

    private boolean hasCycle(List<FlowPlanReviewStep> steps, Map<Integer, Set<Integer>> depsMap) {
        Set<Integer> nodes = new HashSet<>();
        Map<Integer, Integer> indegree = new HashMap<>();
        Map<Integer, List<Integer>> outgoing = new HashMap<>();
        for (FlowPlanReviewStep step : steps) {
            Integer stepNo = step.getStepNo();
            if (stepNo == null) {
                continue;
            }
            nodes.add(stepNo);
            indegree.put(stepNo, 0);
            outgoing.put(stepNo, new ArrayList<>());
        }
        for (Integer stepNo : nodes) {
            for (Integer dep : depsMap.getOrDefault(stepNo, Collections.emptySet())) {
                if (!nodes.contains(dep)) {
                    continue;
                }
                indegree.put(stepNo, indegree.getOrDefault(stepNo, 0) + 1);
                outgoing.computeIfAbsent(dep, k -> new ArrayList<>()).add(stepNo);
            }
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (Map.Entry<Integer, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            Integer node = queue.removeFirst();
            visited++;
            for (Integer next : outgoing.getOrDefault(node, List.of())) {
                int nextDegree = indegree.get(next) - 1;
                indegree.put(next, nextDegree);
                if (nextDegree == 0) {
                    queue.add(next);
                }
            }
        }
        return visited != nodes.size();
    }

    private FlowPlanReviewValidationResult validation(List<String> errors,
                                                      List<String> warnings,
                                                      Map<String, String> stepsMap,
                                                      Map<Integer, Set<Integer>> depsMap) {
        return FlowPlanReviewValidationResult.builder()
                .valid(errors == null || errors.isEmpty())
                .errors(errors == null ? List.of() : errors)
                .warnings(warnings == null ? List.of() : warnings)
                .stepsMap(stepsMap)
                .stepDependencies(depsMap)
                .build();
    }

    private int extractStepNo(String key, int fallbackNo) {
        if (key == null) {
            return fallbackNo;
        }
        Matcher matcher = STEP_KEY_PATTERN.matcher(key);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return fallbackNo;
    }

    private String buildStepContent(int stepNo, String title, String content, Set<Integer> deps) {
        String body = stripDependencyLines(stripPlanHeader(content)).trim();
        StringBuilder builder = new StringBuilder();
        builder.append(stepKey(stepNo)).append("\uff1a").append(cleanOneLine(title));
        if (!body.isBlank() && !cleanOneLine(body).equals(cleanOneLine(title))) {
            builder.append('\n').append(body);
        }
        builder.append('\n').append("DEPENDS_ON: ");
        if (deps == null || deps.isEmpty()) {
            builder.append("NONE");
        } else {
            builder.append(String.join(",", deps.stream().map(String::valueOf).toList()));
        }
        return builder.toString();
    }

    private String titleFromContent(String content, int stepNo) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String firstLine = content.stripLeading().split("\\R", 2)[0].trim();
        firstLine = firstLine.replaceFirst("^#{1,6}\\s*", "");
        firstLine = firstLine.replaceFirst("^\\u7b2c\\s*" + stepNo + "\\s*\\u6b65\\s*[:\\uff1a]\\s*", "");
        firstLine = firstLine.replaceFirst("^\\u7b2c\\s*\\d+\\s*\\u6b65\\s*[:\\uff1a]\\s*", "");
        return cleanOneLine(firstLine);
    }

    private String stripPlanHeader(String content) {
        if (content == null) {
            return "";
        }
        return PLAN_HEADER_PATTERN.matcher(content).replaceFirst("");
    }

    private String stripDependencyLines(String content) {
        if (content == null) {
            return "";
        }
        return DEPENDS_LINE_PATTERN.matcher(content).replaceAll("").trim();
    }

    private String contentForReview(String content, String title, int stepNo) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String withoutHeader = stripPlanHeader(content).trim();
        String[] lines = withoutHeader.split("\\R", 2);
        if (lines.length > 1) {
            String firstLine = lines[0].trim()
                    .replaceFirst("^#{1,6}\\s*", "")
                    .replaceFirst("^\\u7b2c\\s*" + stepNo + "\\s*\\u6b65\\s*[:\\uff1a]\\s*", "")
                    .replaceFirst("^\\u7b2c\\s*\\d+\\s*\\u6b65\\s*[:\\uff1a]\\s*", "");
            if (cleanOneLine(firstLine).equals(cleanOneLine(title))) {
                return lines[1].trim();
            }
        }
        return withoutHeader.isBlank() ? content.trim() : withoutHeader;
    }

    private String stepKey(int stepNo) {
        return "\u7b2c" + stepNo + "\u6b65";
    }

    private int nullSafeStepNo(FlowPlanReviewStep step) {
        return step == null || step.getStepNo() == null ? Integer.MAX_VALUE : step.getStepNo();
    }

    private String cleanOneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isReviewEnabled(ExecuteCommandEntity request) {
        if (request != null && request.getPlanReviewEnabled() != null) {
            return Boolean.TRUE.equals(request.getPlanReviewEnabled());
        }
        return enabled;
    }

}
