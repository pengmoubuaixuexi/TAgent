package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunStepSnapshot;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.run-snapshot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisRunSnapshotService implements RunSnapshotService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${agent.run-snapshot.key-prefix:agent:run:snapshot:}")
    private String keyPrefix;

    @Value("${agent.run-snapshot.session-index-prefix:agent:run:snapshot:session:}")
    private String sessionIndexPrefix;

    @Value("${agent.run-snapshot.ttl-seconds:21600}")
    private long ttlSeconds;

    @Value("${agent.run-snapshot.max-content-chars:0}")
    private int maxContentChars;

    @Value("${agent.run-snapshot.session-index-size:30}")
    private int sessionIndexSize;

    @Override
    public void startRun(ExecuteCommandEntity request, String agentType, String agentName) {
        if (request == null || blank(request.getRunId())) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            RunSnapshot existing = find(request.getRunId()).orElse(null);
            RunSnapshot snapshot = existing != null ? existing : RunSnapshot.builder()
                    .runId(request.getRunId())
                    .createdAt(now)
                    .steps(new ArrayList<>())
                    .build();
            snapshot.setSessionId(request.getSessionId());
            snapshot.setAgentId(request.getAiAgentId());
            snapshot.setAgentName(agentName);
            snapshot.setAgentType(agentType);
            snapshot.setOriginalMessage(request.getMessage());
            snapshot.setSourceRunId(request.getSourceRunId());
            snapshot.setRedoFromStep(request.getRedoFromStep());
            snapshot.setStatus(STATUS_RUNNING);
            snapshot.setLastError(null);
            snapshot.setUpdatedAt(now);
            snapshot.setExpiresAt(now + Math.max(60, ttlSeconds) * 1000L);
            save(snapshot);
            indexBySession(snapshot);
        } catch (Exception e) {
            log.warn("[RunSnapshot] startRun failed runId={} err={}", request.getRunId(), e.getMessage());
        }
    }

    @Override
    public Optional<RunSnapshot> find(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key(runId));
            if (blank(json)) {
                return Optional.empty();
            }
            return Optional.ofNullable(JSON.parseObject(json, RunSnapshot.class));
        } catch (Exception e) {
            log.warn("[RunSnapshot] find failed runId={} err={}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<RunSnapshot> listRecent(String sessionId, int limit) {
        if (blank(sessionId)) {
            return List.of();
        }
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 30));
        try {
            List<String> runIds = stringRedisTemplate.opsForList().range(sessionIndexKey(sessionId), 0, sessionIndexSize - 1);
            if (runIds == null || runIds.isEmpty()) {
                return List.of();
            }
            List<RunSnapshot> result = new ArrayList<>();
            for (String runId : runIds) {
                find(runId).ifPresent(snapshot -> {
                    if (sessionId.equals(snapshot.getSessionId()) && result.size() < effectiveLimit) {
                        result.add(snapshot);
                    }
                });
                if (result.size() >= effectiveLimit) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[RunSnapshot] listRecent failed sessionId={} err={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void recordStep(String runId,
                           String stepId,
                           String title,
                           String type,
                           Integer stepNo,
                           String content,
                           String status) {
        if (blank(runId) || blank(content)) {
            return;
        }
        try {
            RunSnapshot snapshot = find(runId).orElseGet(() -> {
                long now = System.currentTimeMillis();
                return RunSnapshot.builder()
                        .runId(runId)
                        .status(STATUS_RUNNING)
                        .createdAt(now)
                        .updatedAt(now)
                        .expiresAt(now + Math.max(60, ttlSeconds) * 1000L)
                        .steps(new ArrayList<>())
                        .build();
            });
            List<RunStepSnapshot> steps = snapshot.getSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                snapshot.setSteps(steps);
            }
            String resolvedStepId = !blank(stepId) ? stepId : fallbackStepId(type, title, stepNo, steps.size() + 1);
            RunStepSnapshot step = findStep(steps, resolvedStepId).orElse(null);
            long now = System.currentTimeMillis();
            String clipped = clip(content, maxContentChars);
            if (step == null) {
                step = RunStepSnapshot.builder()
                        .ordinal(steps.size() + 1)
                        .createdAt(now)
                        .build();
                steps.add(step);
            }
            step.setStepNo(stepNo);
            step.setStepId(resolvedStepId);
            step.setTitle(!blank(title) ? title : defaultTitle(type, stepNo));
            step.setType(type);
            step.setStatus(!blank(status) ? status : STATUS_COMPLETED);
            step.setContent(clipped);
            step.setPreview(preview(clipped));
            step.setInherited(Boolean.FALSE);
            step.setUpdatedAt(now);
            normalizeOrdinals(steps);
            snapshot.setUpdatedAt(now);
            save(snapshot);
        } catch (Exception e) {
            log.warn("[RunSnapshot] recordStep failed runId={} stepId={} err={}", runId, stepId, e.getMessage());
        }
    }

    @Override
    public void recordStepContent(String runId,
                                  String stepId,
                                  String title,
                                  String type,
                                  Integer stepNo,
                                  String stepContent) {
        if (blank(runId) || blank(stepContent)) {
            return;
        }
        try {
            RunSnapshot snapshot = find(runId).orElseGet(() -> {
                long now = System.currentTimeMillis();
                return RunSnapshot.builder()
                        .runId(runId)
                        .status(STATUS_RUNNING)
                        .createdAt(now)
                        .updatedAt(now)
                        .expiresAt(now + Math.max(60, ttlSeconds) * 1000L)
                        .steps(new ArrayList<>())
                        .build();
            });
            List<RunStepSnapshot> steps = snapshot.getSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                snapshot.setSteps(steps);
            }
            String resolvedStepId = !blank(stepId) ? stepId : fallbackStepId(type, title, stepNo, steps.size() + 1);
            RunStepSnapshot step = findStep(steps, resolvedStepId).orElse(null);
            long now = System.currentTimeMillis();
            if (step == null) {
                step = RunStepSnapshot.builder()
                        .ordinal(steps.size() + 1)
                        .createdAt(now)
                        .build();
                steps.add(step);
            }
            step.setStepNo(stepNo);
            step.setStepId(resolvedStepId);
            step.setTitle(!blank(title) ? title : defaultTitle(type, stepNo));
            step.setType(type);
            step.setStatus(!blank(step.getStatus()) ? step.getStatus() : STATUS_RUNNING);
            step.setStepContent(clip(stepContent, maxContentChars));
            step.setInherited(Boolean.FALSE);
            step.setUpdatedAt(now);
            normalizeOrdinals(steps);
            snapshot.setUpdatedAt(now);
            save(snapshot);
        } catch (Exception e) {
            log.warn("[RunSnapshot] recordStepContent failed runId={} stepId={} err={}", runId, stepId, e.getMessage());
        }
    }

    @Override
    public void markStatus(String runId, String status, String lastError) {
        if (blank(runId)) {
            return;
        }
        try {
            RunSnapshot snapshot = find(runId).orElse(null);
            if (snapshot == null) {
                return;
            }
            snapshot.setStatus(!blank(status) ? status : snapshot.getStatus());
            snapshot.setLastError(lastError);
            snapshot.setUpdatedAt(System.currentTimeMillis());
            save(snapshot);
        } catch (Exception e) {
            log.warn("[RunSnapshot] markStatus failed runId={} status={} err={}", runId, status, e.getMessage());
        }
    }

    @Override
    public Optional<String> buildRedoContext(String sourceRunId, Integer redoFromStep, String sessionId) {
        RunSnapshot snapshot = find(sourceRunId).orElse(null);
        if (snapshot == null || !sameSession(snapshot, sessionId)) {
            return Optional.empty();
        }
        List<RunStepSnapshot> steps = sortedSteps(snapshot);
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        int targetOrdinal = normalizeTargetOrdinal(redoFromStep, steps.size());
        StringBuilder sb = new StringBuilder();
        sb.append("【历史运行快照】\n");
        sb.append("原始 runId: ").append(sourceRunId).append('\n');
        if (!blank(snapshot.getOriginalMessage())) {
            sb.append("原始用户请求: ").append(snapshot.getOriginalMessage()).append("\n\n");
        }
        RunStepSnapshot targetStep = steps.stream()
                .filter(step -> ordinal(step) == targetOrdinal)
                .findFirst()
                .orElse(null);
        String targetLabel = targetStep != null ? displayStepLabel(targetStep) : "Step" + targetOrdinal;
        sb.append("用户要求从 ").append(targetLabel).append(" 开始修正。");
        sb.append(targetLabel).append(" 之前的内容视为已沿用上下文；目标步骤及其后续步骤需要重新生成。\n\n");

        sb.append("【重做语义】\n");
        sb.append("- 用户本次输入是对历史运行的修订指令，不是一个需要直接回答的新普通问题。\n");
        sb.append("- 本轮有效需求 = 原始用户请求 + 用户本次修订指令；两者冲突时，以用户本次修订指令为准。\n");
        sb.append("- 如果用户本次修订指出历史运行理解错了，请围绕被纠正后的真实需求重新执行。\n");
        sb.append("- 旧目标步骤只作为被重做步骤的反例/待修订材料；后续步骤只使用本轮新输出。\n");
        sb.append("- 质量检查只审查本轮新输出，不要因为旧目标步骤本身错误而判定失败；只有新输出重复了旧错误，才据此判定失败。\n\n");

        if (targetOrdinal > 1) {
            sb.append("【已沿用步骤】\n");
            for (RunStepSnapshot step : steps) {
                if (ordinal(step) >= targetOrdinal) {
                    continue;
                }
                appendStep(sb, step);
            }
            sb.append('\n');
        }

        return Optional.of(sb.toString());
    }

    @Override
    public Optional<String> buildRedoTargetStepContext(String sourceRunId, Integer redoFromStep, String sessionId) {
        RunSnapshot snapshot = find(sourceRunId).orElse(null);
        if (snapshot == null || !sameSession(snapshot, sessionId)) {
            return Optional.empty();
        }
        List<RunStepSnapshot> steps = sortedSteps(snapshot);
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        int targetOrdinal = normalizeTargetOrdinal(redoFromStep, steps.size());
        RunStepSnapshot targetStep = steps.stream()
                .filter(step -> ordinal(step) == targetOrdinal)
                .findFirst()
                .orElse(null);
        if (targetStep == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        String targetLabel = displayStepLabel(targetStep);
        sb.append("【被重做的旧").append(targetLabel).append("，仅供当前目标步骤修订参考】\n");
        sb.append("- 下面内容不是本轮新输出，也不是后续步骤需要继续相信的事实。\n");
        sb.append("- 当前目标步骤应参考它暴露的问题进行修订；后续步骤只使用本轮新生成的结果。\n\n");
        for (RunStepSnapshot step : steps) {
            if (ordinal(step) == targetOrdinal) {
                appendStep(sb, step);
                break;
            }
        }
        return Optional.of(sb.toString());
    }

    @Override
    public List<RunStepSnapshot> inheritedSteps(String sourceRunId, Integer redoFromStep, String sessionId) {
        RunSnapshot snapshot = find(sourceRunId).orElse(null);
        if (snapshot == null || !sameSession(snapshot, sessionId)) {
            return List.of();
        }
        List<RunStepSnapshot> steps = sortedSteps(snapshot);
        if (steps.isEmpty()) {
            return List.of();
        }
        int targetOrdinal = normalizeTargetOrdinal(redoFromStep, steps.size());
        List<RunStepSnapshot> inherited = new ArrayList<>();
        for (RunStepSnapshot step : steps) {
            if (ordinal(step) >= targetOrdinal) {
                continue;
            }
            RunStepSnapshot copy = RunStepSnapshot.builder()
                    .ordinal(step.getOrdinal())
                    .stepNo(step.getStepNo())
                    .stepId(step.getStepId())
                    .title(step.getTitle())
                    .displayLabel(displayStepLabel(step))
                    .type(step.getType())
                    .status(step.getStatus())
                    .inherited(Boolean.TRUE)
                    .preview(step.getPreview())
                    .content(step.getContent())
                    .stepContent(step.getStepContent())
                    .createdAt(step.getCreatedAt())
                    .updatedAt(step.getUpdatedAt())
                    .build();
            inherited.add(copy);
        }
        return inherited;
    }

    private void save(RunSnapshot snapshot) {
        if (snapshot == null || blank(snapshot.getRunId())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long expiresAt = snapshot.getExpiresAt();
        long ttlMillis = expiresAt != null ? expiresAt - now : Math.max(60, ttlSeconds) * 1000L;
        if (ttlMillis <= 0) {
            stringRedisTemplate.delete(key(snapshot.getRunId()));
            return;
        }
        stringRedisTemplate.opsForValue().set(key(snapshot.getRunId()), JSON.toJSONString(snapshot), Duration.ofMillis(ttlMillis));
    }

    private void indexBySession(RunSnapshot snapshot) {
        if (snapshot == null || blank(snapshot.getSessionId()) || blank(snapshot.getRunId())) {
            return;
        }
        String key = sessionIndexKey(snapshot.getSessionId());
        stringRedisTemplate.opsForList().remove(key, 0, snapshot.getRunId());
        stringRedisTemplate.opsForList().leftPush(key, snapshot.getRunId());
        stringRedisTemplate.opsForList().trim(key, 0, Math.max(0, sessionIndexSize - 1));
        stringRedisTemplate.expire(key, Duration.ofSeconds(Math.max(60, ttlSeconds)));
    }

    private Optional<RunStepSnapshot> findStep(List<RunStepSnapshot> steps, String stepId) {
        if (steps == null || blank(stepId)) {
            return Optional.empty();
        }
        return steps.stream()
                .filter(step -> step != null && stepId.equals(step.getStepId()))
                .findFirst();
    }

    private List<RunStepSnapshot> sortedSteps(RunSnapshot snapshot) {
        if (snapshot == null || snapshot.getSteps() == null) {
            return List.of();
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && !blank(step.getContent()))
                .sorted(stepComparator())
                .toList();
    }

    private void normalizeOrdinals(List<RunStepSnapshot> steps) {
        if (steps == null) {
            return;
        }
        steps.sort(stepComparator());
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) != null) {
                steps.get(i).setOrdinal(i + 1);
                steps.get(i).setDisplayLabel(displayStepLabel(steps.get(i)));
            }
        }
    }

    private Comparator<RunStepSnapshot> stepComparator() {
        return Comparator
                .comparingInt(this::sortOrder)
                .thenComparingLong(step -> step != null && step.getCreatedAt() != null ? step.getCreatedAt() : Long.MAX_VALUE);
    }

    private int sortOrder(RunStepSnapshot step) {
        if (step == null) {
            return Integer.MAX_VALUE;
        }
        String stepId = step.getStepId();
        if (!blank(stepId)) {
            Integer flowStep4No = parseTrailingNumber(stepId, "flow_step4_execute_step_");
            if (flowStep4No != null) {
                return 4000 + flowStep4No;
            }
            Integer fixedNo = parseTrailingNumber(stepId, "fixed_strategy_");
            if (fixedNo != null) {
                return 1000 + fixedNo;
            }
            if ("fixed_strategy".equals(stepId)) {
                return 1000;
            }
            if ("summary:final".equals(stepId) || stepId.startsWith("summary:")) {
                return 9000;
            }
            if (stepId.startsWith("thinking:")) {
                return thinkingSortOrder(step.getTitle());
            }
        }
        if ("summary".equals(step.getType())) {
            return 9000;
        }
        if ("error".equals(step.getType())) {
            return 9500;
        }
        if (step.getStepNo() != null) {
            return step.getStepNo() * 1000;
        }
        return ordinal(step);
    }

    private int thinkingSortOrder(String title) {
        String t = title == null ? "" : title;
        if (t.contains("工具") || t.contains("任务分析") || t.contains("需求分析")) {
            return 1000;
        }
        if (t.contains("规划") || t.contains("精确") || t.contains("精准")) {
            return 2000;
        }
        if (t.contains("解析") || t.contains("质量") || t.contains("评审")) {
            return 3000;
        }
        return 5000;
    }

    private boolean sameSession(RunSnapshot snapshot, String sessionId) {
        return blank(sessionId) || sessionId.equals(snapshot.getSessionId());
    }

    private int normalizeTargetOrdinal(Integer redoFromStep, int size) {
        int target = redoFromStep == null ? 1 : redoFromStep;
        if (target < 1) {
            return 1;
        }
        return Math.min(target, Math.max(1, size));
    }

    private int ordinal(RunStepSnapshot step) {
        return step != null && step.getOrdinal() != null ? step.getOrdinal() : Integer.MAX_VALUE;
    }

    private void appendStep(StringBuilder sb, RunStepSnapshot step) {
        if (step == null) {
            return;
        }
        String label = displayStepLabel(step);
        String title = !blank(step.getTitle()) ? step.getTitle() : defaultTitle(step.getType(), step.getStepNo());
        sb.append(label);
        if (!label.equals(title)) {
            sb.append(" - ").append(title);
        }
        sb.append(":\n");
        sb.append(step.getContent()).append("\n\n");
    }

    private String displayStepLabel(RunStepSnapshot step) {
        if (step == null) {
            return "Step";
        }
        String stepId = step.getStepId();
        if (!blank(stepId)) {
            if (stepId.startsWith("thinking:")) {
                String title = step.getTitle() == null ? "" : step.getTitle();
                if (title.contains("工具分析")) {
                    return "Step1";
                }
                if (title.contains("步骤规划")) {
                    return "Step2";
                }
                if (title.contains("计划解析")) {
                    return "Step3";
                }
            }
            Integer flowStep4No = parseTrailingNumber(stepId, "flow_step4_execute_step_");
            if (flowStep4No != null) {
                return "Step4_execute" + flowStep4No;
            }
            Integer fixedNo = parseTrailingNumber(stepId, "fixed_strategy_");
            if (fixedNo != null) {
                return "Fixed_" + fixedNo;
            }
            if ("fixed_strategy".equals(stepId)) {
                return "Fixed";
            }
            if ("summary:final".equals(stepId) || stepId.startsWith("summary:")) {
                return "最终回答";
            }
        }
        return "Step" + ordinal(step);
    }

    private Integer parseTrailingNumber(String value, String prefix) {
        if (blank(value) || !value.startsWith(prefix)) {
            return null;
        }
        String raw = value.substring(prefix.length());
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9].*$", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String fallbackStepId(String type, String title, Integer stepNo, int ordinal) {
        String base = (!blank(type) ? type : "step") + ":" + (!blank(title) ? title : "untitled");
        if (stepNo != null) {
            base += ":" + stepNo;
        }
        return base + ":" + ordinal;
    }

    private String defaultTitle(String type, Integer stepNo) {
        if ("summary".equals(type)) {
            return "最终回答";
        }
        if ("error".equals(type)) {
            return "执行错误";
        }
        if ("execution".equals(type)) {
            return stepNo != null ? "执行步骤 " + stepNo : "执行步骤";
        }
        if ("supervision".equals(type)) {
            return "质量监督";
        }
        if ("analysis".equals(type)) {
            return "分析步骤";
        }
        return stepNo != null ? "Step " + stepNo : "Step";
    }

    private String preview(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private String clip(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (maxChars <= 0) {
            return value;
        }
        int max = Math.max(1000, maxChars);
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n...(truncated)";
    }

    private String key(String runId) {
        return keyPrefix + runId;
    }

    private String sessionIndexKey(String sessionId) {
        return sessionIndexPrefix + sessionId;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
