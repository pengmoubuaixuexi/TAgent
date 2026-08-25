package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunAwareResponseBodyEmitter;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.infrastructure.adapter.repository.SummarizingChatMemory;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCaseResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCodeVersion;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDatasetVersion;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunService {

    private static final long HARD_CASE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    private static final String TRACE_SCHEMA_VERSION = "eval-trace-v2-old-compatible";
    private static final DateTimeFormatter TRACE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final IAiEvalOpsDao dao;
    private final EvalDatasetService datasetService;
    private final EvalRuleEngine ruleEngine;
    private final IAgentDispatchService agentDispatchService;
    private final RunEventPublisher runEventPublisher;
    private final RunSnapshotService runSnapshotService;
    private final EvalCodeVersionService codeVersionService;

    /**
     * 与 Agent advisor 共用的 ChatMemory。评测在每题执行前读取一次，保存 Agent 当时真正会看到的
     * summary + recent window；该 Bean 在未启用滚动摘要时可以不存在。
     */
    @Autowired(required = false)
    private SummarizingChatMemory summarizingChatMemory;

    private final ExecutorService coordinators = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "eval-ops-coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, ExecutorService> activeWorkers = new ConcurrentHashMap<>();

    @Transactional
    public Map<String, Object> createRun(RunCommand command) {
        if (command == null) throw new IllegalArgumentException("评测配置不能为空");
        AiEvalDatasetVersion version = dao.findVersion(requireText(command.getVersionId(), "versionId 不能为空"));
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw new IllegalArgumentException("只能使用已发布的数据集版本发起评测");
        }
        AiEvalDataset dataset = dao.findDataset(version.getDatasetId());
        if (dataset == null) throw new IllegalArgumentException("数据集不存在");
        List<AiEvalCase> cases = dao.listCases(version.getVersionId(), true);
        if (cases.isEmpty()) throw new IllegalArgumentException("数据集版本中没有启用的题目");

        String evalRunId = UUID.randomUUID().toString();
        String memoryPolicy = normalizeMemoryPolicy(command.getMemoryPolicy());
        String requestedUser = blankToDefault(command.getUserId(), dataset.getOwnerUserId());
        String effectiveUser = "ISOLATED_USER".equals(memoryPolicy) ? "eval-" + shortId(evalRunId) : requestedUser;
        int concurrency = "SCENARIO".equals(dataset.getExecutionMode()) ? 1
                : Math.max(1, Math.min(command.getConcurrency() == null ? 3 : command.getConcurrency(), 8));
        long interCaseDelayMs = "SCENARIO".equals(dataset.getExecutionMode())
                ? Math.max(0L, Math.min(command.getInterCaseDelayMs() == null ? 30_000L : command.getInterCaseDelayMs(), 120_000L))
                : 0L;
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("datasetName", dataset.getName());
        snapshot.put("versionNo", version.getVersionNo());
        snapshot.put("requestedUserId", requestedUser);
        snapshot.put("effectiveUserId", effectiveUser);
        snapshot.put("selectedAgentId", blankToNull(command.getSelectedAgentId()));
        snapshot.put("memoryPolicy", memoryPolicy);
        snapshot.put("executionMode", dataset.getExecutionMode());
        snapshot.put("interCaseDelayMs", interCaseDelayMs);
        snapshot.put("evaluatorConfig", parseJson(version.getEvaluatorConfigJson()));

        AiEvalRun run = AiEvalRun.builder()
                .evalRunId(evalRunId).datasetId(dataset.getDatasetId()).versionId(version.getVersionId())
                .versionNo(version.getVersionNo())
                .name(blankToDefault(command.getName(), dataset.getName() + " v" + version.getVersionNo()))
                .status("QUEUED").executionMode(dataset.getExecutionMode())
                .userId(effectiveUser).tenantId(blankToDefault(command.getTenantId(), "default"))
                .selectedAgentId(blankToNull(command.getSelectedAgentId())).memoryPolicy(memoryPolicy)
                .concurrency(concurrency).evaluatorVersion(EvalRuleEngine.VERSION)
                .configSnapshotJson(JSON.toJSONString(snapshot)).totalCases(cases.size())
                .completedCases(0).passedCases(0).failedCases(0).createdAt(now).updatedAt(now).build();
        dao.insertRun(run);
        AiEvalCodeVersion codeVersion = codeVersionService.createRunSnapshot(evalRunId, now);
        dao.insertCodeVersion(codeVersion);
        for (AiEvalCase evalCase : cases) {
            dao.insertCaseResult(AiEvalCaseResult.builder()
                    .resultId(UUID.randomUUID().toString()).evalRunId(evalRunId).caseId(evalCase.getCaseId())
                    .stableKey(evalCase.getStableKey()).attemptNo(1).status("QUEUED")
                    .createdAt(now).updatedAt(now).build());
        }
        executeAfterCommit(evalRunId);
        return runView(run);
    }

    private void executeAfterCommit(String evalRunId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            coordinators.execute(() -> executeRun(evalRunId));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                coordinators.execute(() -> executeRun(evalRunId));
            }
        });
    }

    public List<Map<String, Object>> listRuns(String datasetId, int limit) {
        return dao.listRuns(blankToNull(datasetId), Math.max(1, Math.min(limit, 200)))
                .stream().map(this::runView).toList();
    }

    public Map<String, Object> getRun(String evalRunId) {
        AiEvalRun run = requireRun(evalRunId);
        Map<String, Object> view = runView(run);
        view.put("codeVersion", codeVersionService.getCodeVersion(evalRunId));
        view.put("results", listResults(evalRunId, false));
        return view;
    }

    public List<Map<String, Object>> listResults(String evalRunId, boolean includeTrace) {
        requireRun(evalRunId);
        Map<String, AiEvalCase> cases = dao.listCases(requireRun(evalRunId).getVersionId(), false).stream()
                .collect(Collectors.toMap(AiEvalCase::getCaseId, Function.identity()));
        return dao.listCaseResults(evalRunId).stream()
                .map(result -> resultView(result, cases.get(result.getCaseId()), includeTrace)).toList();
    }

    public Map<String, Object> getResult(String evalRunId, String resultId) {
        AiEvalCaseResult result = dao.findCaseResult(resultId);
        if (result == null || !evalRunId.equals(result.getEvalRunId())) throw new IllegalArgumentException("评测结果不存在");
        return resultView(result, dao.findCase(result.getCaseId()), true);
    }

    @Transactional
    public Map<String, Object> cancelRun(String evalRunId) {
        AiEvalRun run = requireRun(evalRunId);
        if (!List.of("QUEUED", "RUNNING", "JUDGING").contains(run.getStatus())) {
            return runView(run);
        }
        if ("JUDGING".equals(run.getStatus())) {
            dao.updateRunStatus(evalRunId, "RULE_COMPLETED", "LLM Judge 已中断，可重新发起");
            return runView(requireRun(evalRunId));
        }
        if (List.of("QUEUED", "RUNNING").contains(run.getStatus())) {
            cancelledRuns.add(evalRunId);
            for (AiEvalCaseResult result : dao.listCaseResults(evalRunId)) {
                if (!"RUNNING".equals(result.getStatus()) || result.getSessionId() == null || result.getAgentRunId() == null) continue;
                try {
                    agentDispatchService.cancelExecute(result.getSessionId(), result.getAgentRunId());
                } catch (Exception error) {
                    log.warn("Failed to cancel agent run evalRunId={} agentRunId={}", evalRunId, result.getAgentRunId(), error);
                }
            }
            ExecutorService workers = activeWorkers.get(evalRunId);
            if (workers != null) workers.shutdownNow();
        }
        LocalDateTime now = LocalDateTime.now();
        dao.cancelPendingCaseResults(evalRunId, now, "用户中断评测");
        dao.refreshRunProgress(evalRunId);
        dao.finishRuleRun(evalRunId, "CANCELLED", now, "用户中断评测");
        return runView(requireRun(evalRunId));
    }

    @Transactional
    public void deleteRun(String evalRunId) {
        AiEvalRun run = requireRun(evalRunId);
        if (List.of("QUEUED", "RUNNING", "JUDGING").contains(run.getStatus())) cancelRun(evalRunId);
        if (dao.deleteRun(evalRunId) != 1) throw new IllegalArgumentException("评测任务不存在或已被删除");
    }

    private void executeRun(String evalRunId) {
        ExecutorService workers = null;
        try {
            if (cancelledRuns.contains(evalRunId)) return;
            AiEvalRun run = requireRun(evalRunId);
            dao.markRunStarted(evalRunId, LocalDateTime.now());
            if (cancelledRuns.contains(evalRunId)) return;
            List<AiEvalCase> cases = dao.listCases(run.getVersionId(), true);
            Map<String, AiEvalCaseResult> results = dao.listCaseResults(evalRunId).stream()
                    .collect(Collectors.toMap(AiEvalCaseResult::getCaseId, Function.identity()));
            JSONObject runConfig = JSON.parseObject(run.getConfigSnapshotJson());
            long interCaseDelayMs = runConfig == null ? 0L : Math.max(0L, runConfig.getLongValue("interCaseDelayMs"));

            Map<String, List<AiEvalCase>> groups = new LinkedHashMap<>();
            if ("SCENARIO".equals(run.getExecutionMode())) {
                for (AiEvalCase evalCase : cases) groups.computeIfAbsent(
                        blankToDefault(evalCase.getConversationGroup(), "default"), ignored -> new ArrayList<>()).add(evalCase);
            } else {
                for (AiEvalCase evalCase : cases) groups.put(evalCase.getCaseId(), new ArrayList<>(List.of(evalCase)));
            }
            groups.values().forEach(group -> group.sort(Comparator.comparing(AiEvalCase::getSequenceNo)));

            workers = Executors.newFixedThreadPool(Math.max(1, Math.min(run.getConcurrency(), groups.size())), runnable -> {
                Thread thread = new Thread(runnable, "eval-ops-worker-" + shortId(evalRunId));
                thread.setDaemon(true);
                return thread;
            });
            activeWorkers.put(evalRunId, workers);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map.Entry<String, List<AiEvalCase>> entry : groups.entrySet()) {
                String group = entry.getKey();
                futures.add(CompletableFuture.runAsync(() -> {
                    String sessionId = "eval-" + shortId(evalRunId) + "-" + safeId(group);
                    List<AiEvalCase> groupCases = entry.getValue();
                    for (int index = 0; index < groupCases.size(); index++) {
                        if (cancelledRuns.contains(evalRunId)) return;
                        AiEvalCase evalCase = groupCases.get(index);
                        executeCase(run, evalCase, results.get(evalCase.getCaseId()), sessionId);
                        if (cancelledRuns.contains(evalRunId)) return;
                        if (interCaseDelayMs > 0 && index + 1 < groupCases.size()) {
                            try {
                                Thread.sleep(interCaseDelayMs);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("场景评测等待被中断", interrupted);
                            }
                        }
                    }
                }, workers));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            if (cancelledRuns.contains(evalRunId)) return;
            dao.refreshRunProgress(evalRunId);
            AiEvalRun finished = requireRun(evalRunId);
            String status = finished.getCompletedCases() != null && finished.getCompletedCases().equals(finished.getTotalCases())
                    ? "RULE_COMPLETED" : "FAILED";
            dao.finishRuleRun(evalRunId, status, LocalDateTime.now(), "FAILED".equals(status) ? "部分题目未完成" : null);
        } catch (Exception error) {
            if (cancelledRuns.contains(evalRunId)) {
                log.info("Eval run cancelled evalRunId={}", evalRunId);
            } else {
                log.error("Eval run failed evalRunId={}", evalRunId, error);
                dao.refreshRunProgress(evalRunId);
                dao.finishRuleRun(evalRunId, "FAILED", LocalDateTime.now(), message(error));
            }
        } finally {
            if (workers != null) workers.shutdownNow();
            if (workers != null) activeWorkers.remove(evalRunId, workers);
            cancelledRuns.remove(evalRunId);
        }
    }

    private void executeCase(AiEvalRun run, AiEvalCase evalCase, AiEvalCaseResult stored, String sessionId) {
        if (stored == null || cancelledRuns.contains(run.getEvalRunId())) return;
        String agentRunId = UUID.randomUUID().toString();
        LocalDateTime started = LocalDateTime.now();
        long startedMs = System.currentTimeMillis();
        dao.markCaseResultRunning(stored.getResultId(), agentRunId, sessionId, started);
        if (cancelledRuns.contains(run.getEvalRunId())) return;
        EvalCaseConfig config = datasetService.parseConfig(evalCase.getConfigJson());
        JSONObject stmSnapshot = captureStmSnapshot(run, sessionId);
        RunSnapshot snapshot = null;
        String terminalStatus = "ERROR";
        String error = null;
        try {
            ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                    .aiAgentId(run.getSelectedAgentId()).message(evalCase.getQuestion()).sessionId(sessionId)
                    .runId(agentRunId).userId(run.getUserId()).tenantId(run.getTenantId())
                    .maxStep(Math.max(config.getMaxSteps(), 2)).planReviewEnabled(false).build();
            RunAwareResponseBodyEmitter emitter = new RunAwareResponseBodyEmitter(Long.MAX_VALUE, agentRunId, sessionId, runEventPublisher);
            agentDispatchService.dispatch(request, emitter);

            long deadline = System.currentTimeMillis() + HARD_CASE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (cancelledRuns.contains(run.getEvalRunId())) {
                    agentDispatchService.cancelExecute(sessionId, agentRunId);
                    return;
                }
                snapshot = runSnapshotService.find(agentRunId).orElse(null);
                if (snapshot != null && terminal(snapshot.getStatus())) break;
                Thread.sleep(500L);
            }
            if (snapshot == null || !terminal(snapshot.getStatus())) {
                agentDispatchService.cancelExecute(sessionId, agentRunId);
                terminalStatus = "TIMEOUT";
                error = "达到单题 1800 秒硬超时上限";
                snapshot = runSnapshotService.find(agentRunId).orElse(snapshot);
            } else if (RunSnapshotService.STATUS_COMPLETED.equals(snapshot.getStatus())) {
                terminalStatus = "PASS";
            } else {
                terminalStatus = "ERROR";
                error = blankToDefault(snapshot.getLastError(), "Agent run 状态=" + snapshot.getStatus());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (cancelledRuns.contains(run.getEvalRunId())) return;
            terminalStatus = "ERROR";
            error = "评测线程被中断";
        } catch (Exception failure) {
            terminalStatus = "ERROR";
            error = message(failure);
            snapshot = runSnapshotService.find(agentRunId).orElse(snapshot);
        }

        if (cancelledRuns.contains(run.getEvalRunId())) return;

        long latency = System.currentTimeMillis() - startedMs;
        String answer = finalAnswer(snapshot);
        List<RunEventRecord> events = snapshot == null || snapshot.getTimelineEvents() == null ? List.of() : snapshot.getTimelineEvents();
        EvalRuleEngine.RuleResult score = ruleEngine.evaluate(EvalRuleEngine.Observation.builder()
                .config(config).agentId(snapshot == null ? null : snapshot.getAgentId())
                .strategy(snapshot == null ? null : snapshot.getAgentType()).status(terminalStatus)
                .answer(answer).latencyMs(latency).events(events).build());

        LocalDateTime finishedAt = LocalDateTime.now();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceSchemaVersion", TRACE_SCHEMA_VERSION);
        trace.put("no", evalCase.getSequenceNo());
        trace.put("category", evalCase.getCategory());
        trace.put("question", evalCase.getQuestion());
        trace.put("agentId", snapshot == null ? null : snapshot.getAgentId());
        trace.put("strategy", snapshot == null ? null : snapshot.getAgentType());
        trace.put("status", terminalStatus);
        trace.put("startTs", startedMs);
        trace.put("endTs", startedMs + latency);
        trace.put("startTime", started.format(TRACE_TIME_FORMAT));
        trace.put("endTime", finishedAt.format(TRACE_TIME_FORMAT));
        trace.put("costMs", latency);
        trace.put("evalSpec", JSON.toJSON(config));
        trace.put("case", datasetService.caseView(evalCase));
        trace.put("agentRunId", agentRunId);
        trace.put("sessionId", sessionId);
        trace.put("stmSnapshot", stmSnapshot);
        trace.put("finalAnswer", answer);
        trace.put("events", oldCompatibleEvents(events));
        trace.put("steps", snapshot == null ? List.of() : snapshot.getSteps());
        trace.put("toolEvidences", snapshot == null ? List.of() : snapshot.getToolEvidences());
        JSONObject traceSignals = score.getSignals() == null
                ? new JSONObject(true)
                : JSON.parseObject(JSON.toJSONString(score.getSignals()));
        traceSignals.put("ruleScores", Map.of(
                "route", score.getRouteScore(), "answer", score.getAnswerScore(), "step", score.getStepScore(),
                "tool", score.getToolScore(), "grounding", score.getGroundingScore(), "memory", score.getMemoryScore(),
                "stability", score.getStabilityScore(), "efficiency", score.getEfficiencyScore(),
                "safety", score.getSafetyScore(), "overall", score.getOverallScore()));
        traceSignals.put("grade", score.getGrade());
        traceSignals.put("warnings", score.getWarnings());
        trace.put("signals", traceSignals);
        // 保留新版可恢复运行快照，旧版兼容字段则供 Judge 和人工审查稳定消费。
        trace.put("runSnapshot", snapshot);
        trace.put("capturedAt", finishedAt.toString());
        AiEvalCaseResult finished = AiEvalCaseResult.builder()
                .resultId(stored.getResultId()).status(terminalStatus).agentRunId(agentRunId).sessionId(sessionId)
                .agentId(snapshot == null ? null : snapshot.getAgentId()).strategy(snapshot == null ? null : snapshot.getAgentType())
                .finalAnswer(answer).traceJson(JSON.toJSONString(trace)).signalsJson(JSON.toJSONString(score.getSignals()))
                .routeScore(decimal(score.getRouteScore())).answerScore(decimal(score.getAnswerScore()))
                .stepScore(decimal(score.getStepScore())).toolScore(decimal(score.getToolScore()))
                .groundingScore(decimal(score.getGroundingScore())).memoryScore(decimal(score.getMemoryScore()))
                .stabilityScore(decimal(score.getStabilityScore())).efficiencyScore(decimal(score.getEfficiencyScore()))
                .safetyScore(decimal(score.getSafetyScore())).overallScore(decimal(score.getOverallScore()))
                .grade(score.getGrade()).warningsJson(JSON.toJSONString(score.getWarnings()))
                .latencyMs(latency).errorMessage(error).finishedAt(finishedAt).build();
        if (cancelledRuns.contains(run.getEvalRunId())) return;
        dao.finishCaseResult(finished);
        dao.refreshRunProgress(run.getEvalRunId());
    }

    private Map<String, Object> runView(AiEvalRun run) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("evalRunId", run.getEvalRunId());
        view.put("datasetId", run.getDatasetId());
        view.put("versionId", run.getVersionId());
        view.put("versionNo", run.getVersionNo());
        view.put("name", run.getName());
        view.put("status", run.getStatus());
        view.put("executionMode", run.getExecutionMode());
        view.put("userId", run.getUserId());
        view.put("tenantId", run.getTenantId());
        view.put("selectedAgentId", run.getSelectedAgentId());
        view.put("memoryPolicy", run.getMemoryPolicy());
        view.put("concurrency", run.getConcurrency());
        view.put("evaluatorVersion", run.getEvaluatorVersion());
        view.put("configSnapshot", parseJson(run.getConfigSnapshotJson()));
        view.put("totalCases", run.getTotalCases());
        view.put("completedCases", run.getCompletedCases());
        view.put("passedCases", run.getPassedCases());
        view.put("failedCases", run.getFailedCases());
        view.put("ruleScore", run.getRuleScore());
        view.put("judgeScore", run.getJudgeScore());
        view.put("startedAt", run.getStartedAt());
        view.put("finishedAt", run.getFinishedAt());
        view.put("errorMessage", run.getErrorMessage());
        view.put("createdAt", run.getCreatedAt());
        return view;
    }

    private Map<String, Object> resultView(AiEvalCaseResult result, AiEvalCase evalCase, boolean includeTrace) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("resultId", result.getResultId());
        view.put("caseId", result.getCaseId());
        view.put("stableKey", result.getStableKey());
        view.put("status", result.getStatus());
        if (evalCase != null) {
            view.put("sequenceNo", evalCase.getSequenceNo());
            view.put("category", evalCase.getCategory());
            view.put("conversationGroup", evalCase.getConversationGroup());
            view.put("question", evalCase.getQuestion());
            view.put("caseConfig", datasetService.parseConfig(evalCase.getConfigJson()));
        }
        view.put("agentRunId", result.getAgentRunId());
        view.put("sessionId", result.getSessionId());
        view.put("agentId", result.getAgentId());
        view.put("strategy", result.getStrategy());
        if (includeTrace) view.put("finalAnswer", result.getFinalAnswer());
        view.put("signals", parseJson(result.getSignalsJson()));
        LinkedHashMap<String, Object> scores = new LinkedHashMap<>();
        scores.put("route", result.getRouteScore()); scores.put("answer", result.getAnswerScore());
        scores.put("step", result.getStepScore()); scores.put("tool", result.getToolScore());
        scores.put("grounding", result.getGroundingScore()); scores.put("memory", result.getMemoryScore());
        scores.put("stability", result.getStabilityScore()); scores.put("efficiency", result.getEfficiencyScore());
        scores.put("safety", result.getSafetyScore()); scores.put("overall", result.getOverallScore());
        view.put("scores", scores);
        view.put("grade", result.getGrade());
        view.put("warnings", parseArray(result.getWarningsJson()));
        view.put("latencyMs", result.getLatencyMs());
        view.put("errorMessage", result.getErrorMessage());
        view.put("startedAt", result.getStartedAt());
        view.put("finishedAt", result.getFinishedAt());
        if (includeTrace) view.put("trace", parseJson(result.getTraceJson()));
        return view;
    }

    private static String finalAnswer(RunSnapshot snapshot) {
        if (snapshot == null) return "";
        List<RunEventRecord> events = snapshot.getTimelineEvents();
        if (events != null) {
            for (int i = events.size() - 1; i >= 0; i--) {
                RunEventRecord event = events.get(i);
                try {
                    JSONObject payload = JSON.parseObject(event.getPayloadJson());
                    if (payload == null) continue;
                    String content = firstNonBlank(payload.getString("content"), payload.getString("answer"), payload.getString("text"));
                    String type = payload.getString("type");
                    if (content != null && ("summary".equals(type) || "final".equals(type) || "answer".equals(type))) return content;
                } catch (Exception ignored) {
                }
            }
        }
        if (snapshot.getSteps() != null) {
            for (int i = snapshot.getSteps().size() - 1; i >= 0; i--) {
                String content = snapshot.getSteps().get(i).getContent();
                if (content != null && !content.isBlank()) return content;
            }
        }
        return "";
    }

    private JSONObject captureStmSnapshot(AiEvalRun run, String sessionId) {
        JSONObject snapshot = new JSONObject(true);
        String conversationId = buildConversationId(run.getTenantId(), run.getUserId(), sessionId);
        snapshot.put("conversationId", conversationId);
        snapshot.put("capturedAt", LocalDateTime.now().toString());
        if (summarizingChatMemory == null) {
            snapshot.put("available", false);
            snapshot.put("reason", "SummarizingChatMemory bean unavailable");
            return snapshot;
        }
        try {
            List<Message> messages = summarizingChatMemory.get(conversationId);
            String summary = "";
            JSONArray window = new JSONArray();
            for (Message message : messages) {
                String text = message.getText();
                if (text != null && text.startsWith("【对话历史摘要】")) {
                    summary = text.substring("【对话历史摘要】".length());
                    continue;
                }
                JSONObject item = new JSONObject(true);
                item.put("role", message.getMessageType() == null ? "?" : message.getMessageType().name());
                item.put("content", text);
                window.add(item);
            }
            snapshot.put("available", true);
            snapshot.put("stmSummary", summary);
            snapshot.put("windowSize", window.size());
            snapshot.put("conversationWindow", window);
        } catch (Exception error) {
            snapshot.put("available", false);
            snapshot.put("reason", message(error));
            log.warn("Capture EvalOps STM snapshot failed conversationId={}", conversationId, error);
        }
        return snapshot;
    }

    /** 将新版 RunEventRecord 投影成旧版 Q*.json 的 event/data 契约。 */
    private static List<Map<String, Object>> oldCompatibleEvents(List<RunEventRecord> events) {
        if (events == null || events.isEmpty()) return List.of();
        List<Map<String, Object>> values = new ArrayList<>(events.size());
        for (RunEventRecord event : events) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("event", event.getEventType());
            value.put("data", parseJson(event.getPayloadJson()));
            value.put("eventId", event.getEventId());
            value.put("createdAt", event.getCreatedAt());
            values.add(value);
        }
        return values;
    }

    private static String buildConversationId(String tenantId, String userId, String sessionId) {
        if (userId == null || userId.isBlank()) return sessionId;
        if (tenantId == null || tenantId.isBlank()) return userId + ":" + sessionId;
        return tenantId + ":" + userId + ":" + sessionId;
    }

    private AiEvalRun requireRun(String evalRunId) {
        AiEvalRun run = dao.findRun(requireText(evalRunId, "evalRunId 不能为空"));
        if (run == null) throw new IllegalArgumentException("评测任务不存在");
        return run;
    }

    private static boolean terminal(String status) {
        return List.of(RunSnapshotService.STATUS_COMPLETED, RunSnapshotService.STATUS_FAILED,
                RunSnapshotService.STATUS_CANCELLED, RunSnapshotService.STATUS_BLOCKED).contains(status);
    }

    private static Object parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return JSON.parse(value); } catch (Exception ignored) { return value; }
    }

    private static Object parseArray(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { JSONArray array = JSON.parseArray(value); return array == null ? List.of() : array; }
        catch (Exception ignored) { return List.of(value); }
    }

    private static BigDecimal decimal(double value) { return BigDecimal.valueOf(value); }
    private static String normalizeMemoryPolicy(String value) {
        String policy = blankToDefault(value, "ISOLATED_USER").toUpperCase(Locale.ROOT);
        if (!List.of("ISOLATED_USER", "FIXED_USER").contains(policy)) throw new IllegalArgumentException("memoryPolicy 只支持 ISOLATED_USER 或 FIXED_USER");
        return policy;
    }
    private static String safeId(String value) { return value.replaceAll("[^a-zA-Z0-9_-]", "-").substring(0, Math.min(36, value.length())); }
    private static String shortId(String value) { return value == null ? "unknown" : value.substring(0, Math.min(8, value.length())); }
    private static String requireText(String value, String message) { if (value == null || value.isBlank()) throw new IllegalArgumentException(message); return value.trim(); }
    private static String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }

    @PreDestroy
    public void shutdown() {
        activeWorkers.values().forEach(ExecutorService::shutdownNow);
        coordinators.shutdownNow();
        try { coordinators.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Data
    public static class RunCommand {
        private String versionId;
        private String name;
        private String userId;
        private String tenantId;
        private String selectedAgentId;
        private String memoryPolicy;
        private Integer concurrency;
        private Long interCaseDelayMs;
    }
}
