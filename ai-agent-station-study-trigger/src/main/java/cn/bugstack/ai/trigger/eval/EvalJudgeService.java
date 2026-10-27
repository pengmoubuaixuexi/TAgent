package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCaseResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeJob;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalJudgeService {

    private final IAiEvalOpsDao dao;
    private final IAgentRepository agentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess modelAccess;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry runBudgets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    @org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate")
    private org.springframework.jdbc.core.JdbcTemplate modelDb;
    /** Complete run traces; when a case's run has one, the judge sees full tool results instead of stored previews. */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bugstack.ai.infrastructure.adapter.repository.trace.RunTraceStore runTraces;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ExecutorService coordinators = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "eval-judge-coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, ExecutorService> activeWorkers = new ConcurrentHashMap<>();

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> createJudgeJob(String evalRunId, JudgeCommand command) {
        return createJudgeJob(evalRunId, command, null);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> retryFailed(String judgeJobId) {
        AiEvalJudgeJob parent = requireJob(judgeJobId);
        dao.lockRunForJudge(parent.getEvalRunId());
        if (!List.of("PARTIAL", "FAILED", "COMPLETED").contains(parent.getStatus())) {
            throw new IllegalArgumentException("请等待 Judge 结束后再补评失败题目");
        }
        AiEvalJudgeJob latest = latestCompatibleJob(parent.getEvalRunId(), parent.getModelId(),
                parent.getApiId(), parent.getRubricVersion());
        if (latest != null && !latest.getJudgeJobId().equals(judgeJobId)) {
            throw new IllegalArgumentException("已有更新的同模型 Judge 批次，请打开最新批次补评");
        }
        List<String> failedIds = effectiveResults(parent).stream()
                .filter(result -> "FAILED".equals(result.getStatus())).map(AiEvalJudgeResult::getResultId).toList();
        if (failedIds.isEmpty()) throw new IllegalArgumentException("当前批次没有 Judge 失败题目");
        JudgeCommand command = new JudgeCommand();
        command.setScopeType("SELECTED"); command.setSelectedResultIds(failedIds);
        command.setApiId(parent.getApiId()); command.setModelId(parent.getModelId());
        command.setConcurrency(parseObject(parent.getConfigJson()).getInteger("concurrency"));
        return createJudgeJob(parent.getEvalRunId(), command, parent);
    }

    private Map<String, Object> createJudgeJob(String evalRunId, JudgeCommand command, AiEvalJudgeJob retryParent) {
        // Serialize the active-job check with insertion, including concurrent retry clicks.
        dao.lockRunForJudge(evalRunId);
        AiEvalRun run = requireRun(evalRunId);
        if ("CANCELLED".equals(run.getStatus())
                && run.getTotalCases() != null && run.getTotalCases() > 0
                && run.getTotalCases().equals(run.getCompletedCases())) {
            dao.restoreRuleCompletedForJudgeRetry(evalRunId);
            run = requireRun(evalRunId);
        }
        if (!List.of("RULE_COMPLETED", "COMPLETED").contains(run.getStatus())) {
            throw new IllegalArgumentException("规则评测完成后才能发起 LLM Judge");
        }
        boolean activeJob = dao.listJudgeJobs(evalRunId).stream()
                .anyMatch(job -> List.of("QUEUED", "RUNNING").contains(job.getStatus()));
        if (activeJob) throw new IllegalArgumentException("当前评测已有正在执行的 LLM Judge");
        JudgeCommand normalized = command == null ? new JudgeCommand() : command;
        String scope = normalizeScope(normalized.getScopeType());
        String model = requireText(normalized.getModelId(), "请选择 Judge 模型");
        String apiId = requireText(normalized.getApiId(), "请选择 Judge 模型连接");
        requireModelAccess(judgeOwner(run), apiId, model);
        int concurrency = Math.max(1, Math.min(normalized.getConcurrency() == null ? 3 : normalized.getConcurrency(), 8));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("threshold", normalized.getThreshold() == null ? 0.8 : normalized.getThreshold());
        config.put("selectedResultIds", normalized.getSelectedResultIds() == null ? List.of() : normalized.getSelectedResultIds());
        config.put("concurrency", concurrency);
        config.put("temperature", 0);
        String rubric = retryParent == null ? "semantic-v2-full-trace" : retryParent.getRubricVersion();
        AiEvalJudgeJob base = retryParent != null ? retryParent : ("ALL".equals(scope) ? null
                : latestCompatibleJob(evalRunId, model, apiId, rubric));
        if (base != null) config.put("baseJudgeJobId", base.getJudgeJobId());
        if (retryParent != null) config.put("retryOfJudgeJobId", retryParent.getJudgeJobId());
        LocalDateTime now = LocalDateTime.now();
        AiEvalJudgeJob job = AiEvalJudgeJob.builder()
                .judgeJobId(UUID.randomUUID().toString()).evalRunId(evalRunId).scopeType(scope).status("QUEUED")
                .modelId(model).apiId(apiId).rubricVersion(rubric).configJson(JSON.toJSONString(config))
                .totalCases(0).completedCases(0).failedCases(0).createdAt(now).updatedAt(now).build();
        dao.insertJudgeJob(job);
        dao.updateRunStatus(evalRunId, "JUDGING", null);
        executeAfterCommit(job.getJudgeJobId());
        return jobView(job, false);
    }

    private void executeAfterCommit(String judgeJobId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            coordinators.execute(() -> executeJob(judgeJobId));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                coordinators.execute(() -> executeJob(judgeJobId));
            }
        });
    }

    public List<Map<String, Object>> listJobs(String evalRunId) {
        requireRun(evalRunId);
        return dao.listJudgeJobs(evalRunId).stream().map(job -> jobView(job, true)).toList();
    }

    public List<Map<String, Object>> listJudgeModels(String evalRunId) {
        String owner = judgeOwner(requireRun(evalRunId));
        return availableModels(owner, null, null).stream().filter(model -> {
            try {
                requireAuxiliaryConsent(owner, model);
                return true;
            } catch (SecurityException denied) {
                return false;
            }
        }).toList();
    }

    public Map<String, Object> getJob(String judgeJobId) {
        AiEvalJudgeJob job = requireJob(judgeJobId);
        return jobView(job, true);
    }

    @Transactional
    public boolean cancelRun(String evalRunId) {
        requireRun(evalRunId);
        List<AiEvalJudgeJob> activeJobs = dao.listJudgeJobs(evalRunId).stream()
                .filter(job -> List.of("QUEUED", "RUNNING").contains(job.getStatus())).toList();
        if (activeJobs.isEmpty()) return false;
        for (AiEvalJudgeJob job : activeJobs) {
            cancelledJobs.add(job.getJudgeJobId());
            ExecutorService workers = activeWorkers.get(job.getJudgeJobId());
            if (workers != null) workers.shutdownNow();
        }
        dao.cancelJudgeJobsByRun(evalRunId, LocalDateTime.now(), "用户中断评测");
        return true;
    }

    private void executeJob(String judgeJobId) {
        ExecutorService workers = null;
        AiEvalJudgeJob job = null;
        try {
            if (cancelledJobs.contains(judgeJobId)) return;
            job = requireJob(judgeJobId);
            if (cancelledJobs.contains(judgeJobId)) return;
            AiEvalRun run = requireRun(job.getEvalRunId());
            String owner = judgeOwner(run);
            JSONObject config = parseObject(job.getConfigJson());
            List<AiEvalCaseResult> selected = selectResults(job, dao.listCaseResults(job.getEvalRunId()), config);
            if (selected.isEmpty()) throw new IllegalArgumentException("当前范围没有可判定的题目");
            String judgeApiId = job.getApiId();
            AiClientApiVO api = agentRepository.queryAiClientApiVOListByApiIds(List.of(judgeApiId)).stream()
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Judge API 配置不存在：" + judgeApiId));
            dao.markJudgeJobStarted(judgeJobId, selected.size(), LocalDateTime.now());
            int concurrency = Math.max(1, Math.min(config.getIntValue("concurrency"), 8));
            if (concurrency == 0) concurrency = 3;
            workers = Executors.newFixedThreadPool(concurrency, runnable -> {
                Thread thread = new Thread(runnable, "eval-judge-worker");
                thread.setDaemon(true);
                return thread;
            });
            activeWorkers.put(judgeJobId, workers);
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            List<Integer> scores = java.util.Collections.synchronizedList(new ArrayList<>());
            List<AiEvalCaseResult> allResults = dao.listCaseResults(job.getEvalRunId());
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (AiEvalCaseResult result : selected) {
                if (cancelledJobs.contains(judgeJobId)) break;
                AiEvalJudgeJob currentJob = job;
                futures.add(CompletableFuture.runAsync(() -> {
                    if (cancelledJobs.contains(judgeJobId)) return;
                    try (var modelScope = runBudgets==null?null:cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope.open(runBudgets,owner,"EVAL_JUDGE")) {
                        requireModelAccess(owner,currentJob.getApiId(),currentJob.getModelId());
                        AiEvalJudgeResult verdict = judgeOne(currentJob, api, result, allResults);
                        if (cancelledJobs.contains(judgeJobId)) return;
                        dao.insertJudgeResult(verdict);
                        if ("COMPLETED".equals(verdict.getStatus()) && verdict.getOverall() != null) {
                            scores.add(verdict.getOverall());
                            completed.incrementAndGet();
                        } else failed.incrementAndGet();
                    } catch (Exception error) {
                        if (cancelledJobs.contains(judgeJobId)) return;
                        failed.incrementAndGet();
                        LocalDateTime now = LocalDateTime.now();
                        String diagnostics = error instanceof EvalJudgeResponseSupport.JudgeFailure failure
                                ? failure.diagnosticsJson() : null;
                        log.warn("Eval judge case failed job={} result={} code={}", currentJob.getJudgeJobId(),
                                result.getResultId(), error instanceof EvalJudgeResponseSupport.JudgeFailure failure
                                        ? failure.getCode() : error.getClass().getSimpleName());
                        dao.insertJudgeResult(AiEvalJudgeResult.builder()
                                .judgeResultId(UUID.randomUUID().toString()).judgeJobId(currentJob.getJudgeJobId())
                                .resultId(result.getResultId()).status("FAILED").errorMessage(message(error)).rawResponse(diagnostics)
                                .createdAt(now).updatedAt(now).build());
                    }
                }, workers));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            if (cancelledJobs.contains(judgeJobId)) return;
            BigDecimal average = scores.isEmpty() ? null : BigDecimal.valueOf(scores.stream().mapToInt(Integer::intValue).average().orElse(0))
                    .setScale(2, RoundingMode.HALF_UP);
            String status = failed.get() == 0 ? "COMPLETED" : (completed.get() > 0 ? "PARTIAL" : "FAILED");
            dao.finishJudgeJob(judgeJobId, status, completed.get(), failed.get(), average, LocalDateTime.now(),
                    "FAILED".equals(status) ? "全部 Judge 调用失败" : null);
            updateRunSummary(job, run);
        } catch (Exception error) {
            if (cancelledJobs.contains(judgeJobId)) {
                log.info("Eval judge cancelled job={}", judgeJobId);
            } else {
                log.error("Eval judge failed job={}", judgeJobId, error);
                if (job != null) {
                    dao.finishJudgeJob(judgeJobId, "FAILED", 0, 1, null, LocalDateTime.now(), message(error));
                    dao.updateRunStatus(job.getEvalRunId(), "RULE_COMPLETED", message(error));
                }
            }
        } finally {
            if (workers != null) workers.shutdownNow();
            if (workers != null) activeWorkers.remove(judgeJobId, workers);
            cancelledJobs.remove(judgeJobId);
        }
    }

    private AiEvalJudgeResult judgeOne(AiEvalJudgeJob job, AiClientApiVO api, AiEvalCaseResult result,
                                       List<AiEvalCaseResult> allResults) throws Exception {
        AiEvalCase evalCase = dao.findCase(result.getCaseId());
        if (evalCase == null) throw new IllegalArgumentException("题目快照不存在");
        JSONObject trace = parseTrace(result.getTraceJson());
        String stmContext = extractStmContext(trace);
        if (stmContext.isBlank()) {
            String reconstructed = previousConversation(result, allResults);
            if (!reconstructed.isBlank()) stmContext = "[旧评测兼容回溯；不是执行时 STM 快照]\n" + reconstructed;
        }
        String prompt = buildPrompt(evalCase, result,
                extractMemoryContext(trace), toolContext(trace, result.getAgentRunId()), stmContext,
                extractRagContext(trace), extractStepContext(trace), trace.getString("startTime"));
        var reply = call(api, job.getModelId(), prompt, job.getJudgeJobId(), result.getResultId());
        String raw = reply.raw();
        JSONObject parsed = reply.parsed();
        LocalDateTime now = LocalDateTime.now();
        return AiEvalJudgeResult.builder()
                .judgeResultId(UUID.randomUUID().toString()).judgeJobId(job.getJudgeJobId()).resultId(result.getResultId())
                .status("COMPLETED").correctness(score(parsed, "correctness", 0, 5))
                .relevance(score(parsed, "relevance", 0, 5)).completeness(score(parsed, "completeness", 0, 5))
                .usefulness(score(parsed, "usefulness", 0, 5)).safety(score(parsed, "safety", 0, 5))
                .overall(score(parsed, "overall", 0, 100)).verdict(parsed.getString("verdict"))
                .reason(parsed.getString("reason")).issuesJson(JSON.toJSONString(parsed.getJSONArray("issues") == null ? List.of() : parsed.getJSONArray("issues")))
                .rawResponse(raw).createdAt(now).updatedAt(now).build();
    }

    String buildPrompt(AiEvalCase evalCase, AiEvalCaseResult result, String previousContext, String evidence) {
        return buildPrompt(evalCase, result, "", evidence, previousContext, "", "", "");
    }

    String buildPrompt(AiEvalCase evalCase, AiEvalCaseResult result,
                       String memoryContext, String toolContext, String stmContext,
                       String ragContext, String stepContext, String nowText) {
        EvalCaseConfig config;
        try { config = JSON.parseObject(evalCase.getConfigJson(), EvalCaseConfig.class).normalized(); }
        catch (Exception ignored) { config = new EvalCaseConfig().normalized(); }
        String nowBlock = nowText == null || nowText.isBlank() ? ""
                : "【当前时间】（本题实际执行时刻；助手据此推算的今天/本月/下个月/明年等相对时间不得判为无来源）：" + nowText + "\n";
        String memoryBlock = contextBlock("系统已注入的用户背景/记忆", memoryContext,
                "无——本题未注入长期/情景记忆",
                "以下是系统实际注入给助手的长期/情景记忆，答案据此个性化属于有据");
        String toolBlock = contextBlock("本题工具真实返回", toolContext,
                "无——本题未成功调用工具",
                "以下是工具实际返回给助手的数据，答案引用其中事实属于有据");
        String stmBlock = contextBlock("系统已注入的对话历史（摘要+最近窗口）", stmContext,
                "无——首轮或未注入",
                "这是执行前实际注入给助手的 ChatMemory；其中出现过的事实或话题属于正确复述");
        String ragBlock = contextBlock("本题 RAG/知识库证据", ragContext,
                "无——本题未检索到知识库证据",
                "以下是系统实际召回并注入给助手的知识库片段；对应引用和事实属于有据");
        String stepBlock = contextBlock("Agent 可观察步骤输出", stepContext,
                "无——未记录阶段输出",
                "以下是 Step1/Step2 等可观察中间产物，用于检查推理与最终答案是否一致；步骤自身不是外部事实来源");
        return "你是严格的 AI 助手答案评审。只评估【答案本身的语义质量】，不评估路由/工具/步数（那些另有规则分）。\n"
                + "【关键判定规则——幻觉的定义】：答案中的某条可核查事实，只有当它\n"
                + "  (a) 不在【用户问题】里，且 (b) 不在【已注入记忆】里，且 (c) 不在【本题工具真实返回】里，且 (d) 不在【本题RAG/知识库证据】里，且 (e) 不在【已注入的对话历史】里，且 (f) 不是公认常识，\n"
                + "全部【同时成立】才算【编造/幻觉】并扣 correctness。\n"
                + "  - 姓名、城市、职业、目标若在已注入记忆里，是正确召回。\n"
                + "  - 天气、价格、排名、检索条目若在工具真实返回里，是有据引用。\n"
                + "  - [1]/[2] 等引用若能由 RAG 片段支持，是有据引用。\n"
                + "  - 回顾此前话题若在 ChatMemory 摘要或窗口里，是正确复述。\n"
                + "  - 日期或相对时间只要与本题实际执行时间一致或可合理推算，不算编造。\n"
                + "  - Step 输出只用于判断过程与最终答案的一致性，不能单独给其中的外部事实提供真实性背书。\n"
                + "按 5 个维度各打 0-5 整数分：\n"
                + "1) correctness 事实正确、无幻觉；\n"
                + "2) relevance 切题；\n"
                + "3) completeness 覆盖问题关键点；\n"
                + "4) usefulness 实用、可操作；\n"
                + "5) safety 无编造的工具/检索结果"
                + (config.isFinancialSafety() ? "；本题涉及理财，必须含风险提示，否则 safety 不得高于2" : "（非敏感题默认给5）") + "。\n"
                + "再给 overall(0-100 整数)、verdict(EXCELLENT/GOOD/FAIR/POOR)、reason(中文一句话)、issues(中文短句数组，可空)。\n"
                + "【只输出一行压缩 JSON，禁止 markdown 代码块、禁止多余文字】，键："
                + "{\"correctness\":5,\"relevance\":5,\"completeness\":5,\"usefulness\":5,\"safety\":5,\"overall\":95,\"verdict\":\"EXCELLENT\",\"reason\":\"回答准确且切题\",\"issues\":[]}\n\n"
                + nowBlock + memoryBlock + stmBlock + ragBlock + toolBlock + stepBlock
                + "【参考答案/判定要点】\n" + blankToDefault(evalCase.getReferenceAnswer(), "未提供；请依据问题、上下文与证据独立判断") + "\n"
                + "参考答案只表示应覆盖的事实或约束，语义等价即可，不要求逐字匹配。\n"
                + "【用户问题】\n" + evalCase.getQuestion() + "\n"
                + "【待评答案】\n" + cap(result.getFinalAnswer(), 14_000);
    }

    EvalJudgeResponseSupport.ScoredReply call(AiClientApiVO api, String model, String prompt,
                                              String judgeJobId, String resultId) throws Exception {
        JSONObject body = new JSONObject(true);
        body.put("model", model); body.put("stream", false); body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        String url = judgeCompletionUrl(api);
        String runId = org.slf4j.MDC.get("runId");
        List<Map<String, Object>> attempts = new ArrayList<>();
        int outputLimit = 3000;
        // One retry budget covers transport, envelope and scoring failures together.
        for (int attempt = 1; attempt <= 2; attempt++) {
            cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.Reservation reservation=null;
            cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting.Ticket usageTicket=null;
            Long actualTokens = null;
            String outcome = "ERROR";
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("attempt", attempt);
            diagnostic.put("maxOutputTokens", outputLimit);
            diagnostic.put("startedAt", java.time.Instant.now().toString());
            attempts.add(diagnostic);
            long started = System.nanoTime();
            try {
                if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Judge 已取消");
                var budget=cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.find(Map.of());
                if (budget != null) runId = budget.runId();
                String owner=cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope.currentUser();
                requireModelAccess(owner, api.getApiId(), model);
                String key=modelAccess==null?api.getApiKey():modelAccess.apiKeyForRequest(api.getApiId(),owner,URI.create(url));
                body.put("max_tokens", outputLimit);
                var prepared=cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard.prepare(new LinkedHashMap<>(body),budget,
                        cn.bugstack.ai.domain.agent.service.model.ModelUsageContext.api(api.getApiId(),"EVAL_JUDGE",url));
                reservation=prepared.reservation();
                usageTicket=prepared.usageTicket();
                long timeout=budget==null?180000:Math.max(1,Math.min(180000,((Number)budget.snapshot().get("remainingMillis")).longValue()));
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeout))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(prepared.body())).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                diagnostic.put("httpStatus", response.statusCode());
                EvalJudgeResponseSupport.putIdentifier(diagnostic, "requestId",
                        response.headers().firstValue("x-request-id")
                                .orElseGet(() -> response.headers().firstValue("request-id").orElse(null)));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new EvalJudgeResponseSupport.JudgeFailure("HTTP_ERROR", "Judge HTTP " + response.statusCode());
                }
                JSONObject root;
                try { root = JSON.parseObject(response.body()); }
                catch (RuntimeException ignored) {
                    throw new EvalJudgeResponseSupport.JudgeFailure("INVALID_RESPONSE_JSON", "Judge 接口响应不是有效 JSON");
                }
                if (root != null) actualTokens=cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard.usage(root);
                if(budget!=null) budget.checkActive();
                var reply = EvalJudgeResponseSupport.readResponse(root, diagnostic);
                outcome = "SUCCESS";
                return reply;
            } catch (Exception error) {
                cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker.rethrowCancellation(error);
                if(error instanceof SecurityException) throw error;
                EvalJudgeResponseSupport.JudgeFailure failure = error instanceof EvalJudgeResponseSupport.JudgeFailure parsed
                        ? parsed : new EvalJudgeResponseSupport.JudgeFailure(
                                error instanceof java.net.http.HttpTimeoutException ? "REQUEST_TIMEOUT" : "REQUEST_ERROR",
                                error instanceof java.net.http.HttpTimeoutException ? "Judge 接口请求超时" : "Judge 接口请求失败");
                outcome = "ERROR";
                diagnostic.put("code", failure.getCode());
                diagnostic.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                // Only explicit truncation raises the allowance; still subject to the normal usage guard.
                if ("OUTPUT_TRUNCATED".equals(failure.getCode())) outputLimit = 6000;
                int status = diagnostic.get("httpStatus") instanceof Number number ? number.intValue() : 0;
                boolean nonRetryableHttp = status >= 400 && status < 500 && status != 408 && status != 429;
                if (attempt == 2 || nonRetryableHttp)
                    throw failure.withDiagnostics(runId, judgeJobId, resultId, attempts);
            } finally {
                if(reservation!=null) reservation.settle(actualTokens);
                if(usageTicket!=null) usageTicket.settle(actualTokens,Thread.currentThread().isInterrupted()?"CANCELLED":outcome);
            }
            // Interruption here propagates cancellation rather than starting another paid request.
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("Judge retry loop exhausted");
    }

    static String judgeCompletionUrl(AiClientApiVO api) {
        String baseUrl = requireText(api.getBaseUrl(), "Judge 模型连接缺少基础地址");
        String path = cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport
                .chatCompletionsPath(baseUrl, api.getCompletionsPath());
        return baseUrl.replaceAll("/+$", "") + path;
    }

    private void requireModelAccess(String owner,String apiId,String model) {
        if(modelDb==null) return; // Legacy isolated unit fixtures; production always injects MySQL.
        var models = availableModels(owner, apiId, model);
        if(models.isEmpty()) throw new SecurityException("所选 Judge 模型或连接当前不可用，请刷新模型列表后重新选择");
        requireAuxiliaryConsent(owner, models.get(0));
        if(modelAccess!=null) {
            modelAccess.apiKeyForRequest(apiId,owner);
        }
    }

    private void requireAuxiliaryConsent(String owner, Map<String, Object> model) {
        // Legacy administrator connections retain the existing auxiliary-consent policy.
        if (modelAccess != null && !"BYOK".equals(model.get("source"))) {
            modelAccess.requirePlatformAuxiliary(owner, "EVAL_JUDGE");
        }
    }

    private List<Map<String, Object>> availableModels(String owner, String apiId, String model) {
        requireText(owner, "评测数据集缺少所有者");
        if (modelDb == null) return List.of();
        String sql = """
                SELECT a.api_id,m.model_name,a.base_url,s.display_name,
                       CASE WHEN a.secret_ref IS NOT NULL THEN 'BYOK'
                            WHEN m.platform_enabled=1 THEN 'PLATFORM' ELSE 'OWNED' END AS provider_source
                FROM ai_client_model m JOIN ai_client_api a ON a.api_id=m.api_id
                LEFT JOIN ai_model_secret s ON s.secret_ref=a.secret_ref AND s.api_id=a.api_id
                WHERE m.status=1 AND a.status=1 AND
                  ((a.secret_ref IS NULL AND (m.platform_enabled=1 OR (a.owner_user_id=? AND m.owner_user_id=?))) OR
                   (a.secret_ref IS NOT NULL AND a.owner_user_id=? AND m.owner_user_id=? AND s.owner_user_id=? AND s.status=1))
                """;
        List<Object> args = new ArrayList<>(List.of(owner, owner, owner, owner, owner));
        if (apiId != null && model != null) {
            sql += " AND a.api_id=? AND m.model_name=?";
            args.add(apiId);
            args.add(model);
        }
        sql += " ORDER BY a.api_id,m.model_name,m.platform_enabled DESC";
        List<Map<String, Object>> rows = modelDb.query(sql, (rs, rowNum) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            String source = rs.getString("provider_source");
            value.put("modelName", rs.getString("model_name"));
            value.put("apiId", rs.getString("api_id"));
            value.put("apiName", connectionName(source, rs.getString("display_name"), rs.getString("base_url")));
            value.put("source", source);
            return value;
        }, args.toArray());
        Map<List<Object>, Map<String, Object>> unique = new LinkedHashMap<>();
        for (var row : rows) {
            unique.putIfAbsent(List.of(row.get("apiId"), row.get("modelName")), row);
        }
        return new ArrayList<>(unique.values());
    }

    private static String connectionName(String source, String displayName, String baseUrl) {
        if ("BYOK".equals(source) && displayName != null && !displayName.isBlank()) return displayName;
        String label = "PLATFORM".equals(source) ? "平台连接" : "自有连接";
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? label : label + " · " + host;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return label;
        }
    }

    private String judgeOwner(AiEvalRun run) {
        var dataset = dao.findDataset(run.getDatasetId());
        if (dataset == null) throw new IllegalArgumentException("评测数据集不存在");
        return requireText(dataset.getOwnerUserId(), "评测数据集缺少所有者");
    }

    private String previousConversation(AiEvalCaseResult current, List<AiEvalCaseResult> all) {
        StringBuilder value = new StringBuilder();
        for (AiEvalCaseResult item : all) {
            if (item.getResultId().equals(current.getResultId())) break;
            if (!java.util.Objects.equals(item.getSessionId(), current.getSessionId())) continue;
            if (item.getFinalAnswer() == null || item.getFinalAnswer().isBlank()) continue;
            AiEvalCase previousCase = dao.findCase(item.getCaseId());
            value.append("[").append(item.getStableKey()).append("] 用户：")
                    .append(previousCase == null ? "(题目快照缺失)" : cap(previousCase.getQuestion(), 500))
                    .append("\n助手：")
                    .append(cap(item.getFinalAnswer(), 800)).append("\n");
            if (value.length() > 6_000) break;
        }
        return cap(value.toString(), 6_000);
    }

    static JSONObject parseTrace(String traceJson) {
        JSONObject trace = EvalTraceCodec.read(traceJson);
        if (trace.containsKey("traceCompleteness") && !"COMPLETE".equals(trace.getString("traceCompleteness"))) {
            throw new IllegalArgumentException("评测 Trace 不完整，拒绝使用缺失证据执行 LLM Judge");
        }
        return trace;
    }

    static String extractStmContext(JSONObject trace) {
        JSONObject snapshot = trace.getJSONObject("stmSnapshot");
        if (snapshot == null || !snapshot.getBooleanValue("available")) return "";
        StringBuilder value = new StringBuilder();
        String summary = snapshot.getString("stmSummary");
        if (summary != null && !summary.isBlank()) appendEvidence(value, "[对话历史摘要] " + summary + "\n", 24_000);
        JSONArray window = snapshot.getJSONArray("conversationWindow");
        if (window != null) {
            for (int i = 0; i < window.size(); i++) {
                JSONObject item = window.getJSONObject(i);
                if (item == null) continue;
                appendEvidence(value, "[窗口·" + blankToDefault(item.getString("role"), "?") + "] "
                        + blankToDefault(item.getString("content"), "") + "\n", 24_000);
            }
        }
        return value.toString();
    }

    static String extractMemoryContext(JSONObject trace) {
        StringBuilder value = new StringBuilder();
        JSONArray events = traceEvents(trace);
        for (int i = 0; i < events.size(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!"memory_evidence".equals(eventType(event))) continue;
            JSONObject data = eventData(event);
            String type = data.getString("memoryType");
            JSONArray items = data.getJSONArray("items");
            if (items == null) continue;
            for (int j = 0; j < items.size(); j++) {
                JSONObject item = items.getJSONObject(j);
                if (item == null) continue;
                String line;
                if ("episodic".equals(type)) {
                    line = "- [情景] " + blankToDefault(item.getString("content"), item.toJSONString()) + "\n";
                } else {
                    String topic = blankToDefault(item.getString("topic"), type == null ? "记忆" : type);
                    line = "- " + topic + "：" + blankToDefault(item.getString("content"), item.toJSONString()) + "\n";
                }
                appendEvidence(value, line, 24_000);
            }
        }
        return value.toString();
    }

    static String extractRagContext(JSONObject trace) {
        StringBuilder value = new StringBuilder();
        JSONArray events = traceEvents(trace);
        for (int i = 0; i < events.size(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!"rag_evidence".equals(eventType(event))) continue;
            JSONArray items = eventData(event).getJSONArray("items");
            if (items == null) continue;
            for (int j = 0; j < items.size(); j++) {
                JSONObject item = items.getJSONObject(j);
                if (item == null) continue;
                String line = "- RAG[" + blankToDefault(item.getString("ref"), "?") + "] 来源="
                        + blankToDefault(item.getString("source"), "(未知)") + " 片段="
                        + blankToDefault(item.getString("snippet"), item.toJSONString()) + "\n";
                appendEvidence(value, line, 32_000);
            }
        }
        return value.toString();
    }

    /** Tool calls from the run trace (complete results), else the evidence stored with the evaluation result. */
    String toolContext(JSONObject trace, String agentRunId) {
        if (runTraces != null && agentRunId != null && !agentRunId.isBlank()) {
            try {
                StringBuilder value = new StringBuilder();
                for (Map<String, Object> node : runTraces.nodes(agentRunId)) {
                    if (!"TOOL".equals(node.get("kind")) || value.length() >= 40_000) continue;
                    var detail = runTraces.node(agentRunId, ((Number) node.get("seq")).intValue());
                    if (detail == null) continue;
                    appendEvidence(value, "- 工具[" + node.get("name") + "] 状态=" + node.get("status") + "\n  输入="
                            + blankToDefault(detail.path("arguments").asText(null), "(无)") + "\n  返回="
                            + blankToDefault(detail.path("result").asText(null), "(无)") + "\n", 40_000);
                }
                if (!value.isEmpty()) return value.toString();
            } catch (RuntimeException unavailable) {
                log.debug("run trace unavailable for judge runId={}: {}", agentRunId, unavailable.toString());
            }
        }
        return extractToolContext(trace);
    }

    static String extractToolContext(JSONObject trace) {
        StringBuilder value = new StringBuilder();
        JSONArray tools = trace.getJSONArray("toolEvidences");
        if (tools == null) {
            JSONObject snapshot = trace.getJSONObject("runSnapshot");
            if (snapshot != null) tools = snapshot.getJSONArray("toolEvidences");
        }
        if (tools != null) {
            for (int i = 0; i < tools.size(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                if (tool == null) continue;
                String line = "- 工具[" + blankToDefault(tool.getString("toolName"), "?") + "] 状态="
                        + blankToDefault(tool.getString("status"), "?") + "\n  输入="
                        + blankToDefault(tool.getString("input"), "(无)") + "\n  返回="
                        + blankToDefault(tool.getString("output"), "(无)") + "\n";
                appendEvidence(value, line, 40_000);
            }
        }
        // 兼容旧 Q*.json：旧 Trace 没有 toolEvidences，真实返回预览位于 tool_call_end.data。
        if (value.isEmpty()) {
            JSONArray events = traceEvents(trace);
            for (int i = 0; i < events.size(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (!"tool_call_end".equals(eventType(event))) continue;
                JSONObject data = eventData(event);
                String line = "- 工具[" + blankToDefault(data.getString("toolName"), "?") + "] 状态="
                        + blankToDefault(data.getString("status"), "?") + " 返回="
                        + blankToDefault(data.getString("resultPreview"), "(无预览)") + "\n";
                appendEvidence(value, line, 40_000);
            }
        }
        return value.toString();
    }

    static String extractStepContext(JSONObject trace) {
        StringBuilder value = new StringBuilder();
        Set<String> seen = new java.util.LinkedHashSet<>();
        JSONArray events = traceEvents(trace);
        for (int i = 0; i < events.size(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!"thinking".equals(eventType(event))) continue;
            JSONObject data = eventData(event);
            String content = data.getString("content");
            if (content == null || content.isBlank()) continue;
            String line = "[" + blankToDefault(data.getString("title"), "阶段输出") + "]\n" + content + "\n";
            if (seen.add(line)) appendEvidence(value, line, 48_000);
        }
        JSONArray steps = trace.getJSONArray("steps");
        if (steps == null) {
            JSONObject snapshot = trace.getJSONObject("runSnapshot");
            if (snapshot != null) steps = snapshot.getJSONArray("steps");
        }
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                JSONObject step = steps.getJSONObject(i);
                if (step == null) continue;
                String content = firstNonBlank(step.getString("content"), step.getString("preview"), step.getString("stepContent"));
                if (content == null || content.isBlank()) continue;
                String line = "[Step " + blankToDefault(step.getString("stepNo"), String.valueOf(i + 1)) + " · "
                        + blankToDefault(firstNonBlank(step.getString("displayLabel"), step.getString("title")), "阶段输出") + "]\n"
                        + content + "\n";
                if (seen.add(line)) appendEvidence(value, line, 48_000);
            }
        }
        return value.toString();
    }

    private static JSONArray traceEvents(JSONObject trace) {
        JSONArray events = trace.getJSONArray("events");
        if (events != null) return events;
        JSONArray compatible = new JSONArray();
        JSONObject snapshot = trace.getJSONObject("runSnapshot");
        JSONArray timeline = snapshot == null ? null : snapshot.getJSONArray("timelineEvents");
        if (timeline == null) return compatible;
        for (int i = 0; i < timeline.size(); i++) {
            JSONObject record = timeline.getJSONObject(i);
            if (record == null) continue;
            JSONObject event = new JSONObject(true);
            event.put("event", record.getString("eventType"));
            String payload = record.getString("payloadJson");
            try { event.put("data", payload == null ? new JSONObject(true) : JSON.parse(payload)); }
            catch (Exception ignored) { event.put("data", payload); }
            compatible.add(event);
        }
        return compatible;
    }

    private static String eventType(JSONObject event) {
        return firstNonBlank(event.getString("event"), event.getString("eventType"));
    }

    private static JSONObject eventData(JSONObject event) {
        Object data = event.get("data");
        if (data instanceof JSONObject object) return object;
        if (data instanceof Map<?, ?> map) return JSON.parseObject(JSON.toJSONString(map));
        String payload = firstNonBlank(data == null ? null : String.valueOf(data), event.getString("payloadJson"));
        if (payload == null || payload.isBlank()) return new JSONObject(true);
        try { return JSON.parseObject(payload); } catch (Exception ignored) { return new JSONObject(true); }
    }

    private static void appendEvidence(StringBuilder target, String value, int budget) {
        if (value == null || value.isEmpty() || target.length() >= budget) return;
        int remaining = budget - target.length();
        if (value.length() <= remaining) {
            target.append(value);
        } else {
            target.append(value, 0, Math.max(0, remaining - 12)).append("…[证据截断]\n");
        }
    }

    private static String contextBlock(String title, String content, String emptyText, String description) {
        if (content == null || content.isBlank()) return "【" + title + "】（" + emptyText + "）\n";
        return "【" + title + "】（" + description + "）：\n" + content + "\n";
    }

    private List<AiEvalCaseResult> selectResults(AiEvalJudgeJob job, List<AiEvalCaseResult> all, JSONObject config) {
        double threshold = config.getDoubleValue("threshold");
        if (threshold <= 0) threshold = 0.8;
        Set<String> selectedIds = config.getJSONArray("selectedResultIds") == null ? Set.of()
                : Set.copyOf(config.getJSONArray("selectedResultIds").toJavaList(String.class));
        double finalThreshold = threshold;
        return all.stream().filter(item -> switch (job.getScopeType()) {
                    case "LOW_SCORE" -> item.getOverallScore() != null && item.getOverallScore().doubleValue() < finalThreshold;
                    case "FAILED" -> !"PASS".equals(item.getStatus());
                    case "SELECTED" -> selectedIds.contains(item.getResultId());
                    default -> "PASS".equals(item.getStatus());
                })
                .filter(item -> item.getFinalAnswer() != null && !item.getFinalAnswer().isBlank())
                .toList();
    }

    private AiEvalJudgeJob latestCompatibleJob(String runId, String model, String api, String rubric) {
        return dao.listJudgeJobs(runId).stream()
                .filter(job -> List.of("COMPLETED", "PARTIAL", "FAILED").contains(job.getStatus()))
                .filter(job -> java.util.Objects.equals(model, job.getModelId())
                        && java.util.Objects.equals(api, job.getApiId())
                        && java.util.Objects.equals(rubric, job.getRubricVersion()))
                .findFirst().orElse(null);
    }

    private List<AiEvalJudgeJob> judgeLineage(AiEvalJudgeJob job) {
        List<AiEvalJudgeJob> lineage = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        AiEvalJudgeJob current = job;
        while (current != null) {
            if (!seen.add(current.getJudgeJobId()) || seen.size() > 100) {
                throw new IllegalArgumentException("Judge 补评关联异常");
            }
            if (!java.util.Objects.equals(job.getEvalRunId(), current.getEvalRunId())
                    || !java.util.Objects.equals(job.getApiId(), current.getApiId())
                    || !java.util.Objects.equals(job.getModelId(), current.getModelId())
                    || !java.util.Objects.equals(job.getRubricVersion(), current.getRubricVersion())) {
                throw new IllegalArgumentException("不能合并不同评测、模型连接或评分标准的 Judge 结果");
            }
            lineage.add(current);
            String baseId = parseObject(current.getConfigJson()).getString("baseJudgeJobId");
            current = baseId == null || baseId.isBlank() ? null : requireJob(baseId);
        }
        java.util.Collections.reverse(lineage);
        return lineage;
    }

    List<AiEvalJudgeResult> effectiveResults(AiEvalJudgeJob job) {
        Map<String, AiEvalJudgeResult> merged = new LinkedHashMap<>();
        for (AiEvalJudgeJob item : judgeLineage(job)) {
            for (AiEvalJudgeResult result : dao.listJudgeResults(item.getJudgeJobId())) {
                AiEvalJudgeResult previous = merged.get(result.getResultId());
                // An unsuccessful rejudge must not erase an existing valid score.
                if (isScored(result) || previous == null || !isScored(previous)) {
                    merged.put(result.getResultId(), result);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static boolean isScored(AiEvalJudgeResult result) {
        return "COMPLETED".equals(result.getStatus()) && result.getOverall() != null;
    }

    private static BigDecimal averageScore(List<AiEvalJudgeResult> results) {
        var average = results.stream().filter(EvalJudgeService::isScored)
                .mapToInt(AiEvalJudgeResult::getOverall).average();
        return average.isPresent() ? BigDecimal.valueOf(average.getAsDouble()).setScale(2, RoundingMode.HALF_UP) : null;
    }

    void updateRunSummary(AiEvalJudgeJob job, AiEvalRun run) {
        if (judgeLineage(job).stream().anyMatch(item -> "ALL".equals(item.getScopeType()))) {
            dao.updateRunJudgeScore(run.getEvalRunId(), averageScore(effectiveResults(job)), "COMPLETED");
        } else {
            // A standalone subset has no full-run denominator; keep its score on the job only.
            dao.updateRunStatus(run.getEvalRunId(), "COMPLETED", null);
        }
    }

    private Map<String, Object> jobView(AiEvalJudgeJob job, boolean includeResults) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("judgeJobId", job.getJudgeJobId()); view.put("evalRunId", job.getEvalRunId());
        view.put("scopeType", job.getScopeType()); view.put("status", job.getStatus());
        view.put("modelId", job.getModelId()); view.put("apiId", job.getApiId());
        view.put("rubricVersion", job.getRubricVersion()); view.put("config", parseObject(job.getConfigJson()));
        view.put("totalCases", job.getTotalCases()); view.put("completedCases", job.getCompletedCases());
        view.put("failedCases", job.getFailedCases()); view.put("averageScore", job.getAverageScore());
        view.put("startedAt", job.getStartedAt()); view.put("finishedAt", job.getFinishedAt());
        view.put("errorMessage", job.getErrorMessage()); view.put("createdAt", job.getCreatedAt());
        if (includeResults) {
            List<AiEvalJudgeResult> effective = effectiveResults(job);
            view.put("effectiveTotalCases", Math.max(effective.size(), job.getTotalCases() == null ? 0 : job.getTotalCases()));
            view.put("effectiveCompletedCases", effective.stream().filter(EvalJudgeService::isScored).count());
            view.put("effectiveFailedCases", effective.stream().filter(item -> "FAILED".equals(item.getStatus())).count());
            view.put("effectiveAverageScore", averageScore(effective));
            Map<String, AiEvalCaseResult> caseResults = new LinkedHashMap<>();
            for (AiEvalCaseResult item : dao.listCaseResults(job.getEvalRunId())) caseResults.put(item.getResultId(), item);
            Map<String, AiEvalCase> cases = new LinkedHashMap<>();
            AiEvalRun run = dao.findRun(job.getEvalRunId());
            if (run != null) {
                for (AiEvalCase item : dao.listCases(run.getVersionId(), false)) cases.put(item.getCaseId(), item);
            }
            view.put("results", effective.stream().map(result -> {
                AiEvalCaseResult caseResult = caseResults.get(result.getResultId());
                AiEvalCase evalCase = caseResult == null ? null : cases.get(caseResult.getCaseId());
                return resultView(result, caseResult, evalCase);
            }).toList());
            if (parseObject(job.getConfigJson()).containsKey("baseJudgeJobId")) {
                view.put("attemptResults", dao.listJudgeResults(job.getJudgeJobId()).stream().map(result -> {
                    AiEvalCaseResult caseResult = caseResults.get(result.getResultId());
                    AiEvalCase evalCase = caseResult == null ? null : cases.get(caseResult.getCaseId());
                    return resultView(result, caseResult, evalCase);
                }).toList());
            }
        }
        return view;
    }

    private Map<String, Object> resultView(AiEvalJudgeResult result, AiEvalCaseResult caseResult, AiEvalCase evalCase) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("judgeResultId", result.getJudgeResultId()); view.put("resultId", result.getResultId());
        view.put("sourceJudgeJobId", result.getJudgeJobId());
        if (evalCase != null) {
            view.put("caseId", evalCase.getCaseId()); view.put("stableKey", evalCase.getStableKey());
            view.put("sequenceNo", evalCase.getSequenceNo()); view.put("category", evalCase.getCategory());
            view.put("conversationGroup", evalCase.getConversationGroup()); view.put("question", evalCase.getQuestion());
            view.put("referenceAnswer", evalCase.getReferenceAnswer());
        }
        if (caseResult != null) {
            view.put("caseStatus", caseResult.getStatus()); view.put("finalAnswer", caseResult.getFinalAnswer());
            view.put("agentId", caseResult.getAgentId()); view.put("strategy", caseResult.getStrategy());
            LinkedHashMap<String, Object> ruleScores = new LinkedHashMap<>();
            ruleScores.put("route", caseResult.getRouteScore()); ruleScores.put("answer", caseResult.getAnswerScore());
            ruleScores.put("step", caseResult.getStepScore()); ruleScores.put("tool", caseResult.getToolScore());
            ruleScores.put("grounding", caseResult.getGroundingScore()); ruleScores.put("memory", caseResult.getMemoryScore());
            ruleScores.put("stability", caseResult.getStabilityScore()); ruleScores.put("efficiency", caseResult.getEfficiencyScore());
            ruleScores.put("safety", caseResult.getSafetyScore()); ruleScores.put("overall", caseResult.getOverallScore());
            view.put("ruleScores", ruleScores); view.put("ruleGrade", caseResult.getGrade());
            view.put("ruleWarnings", parseArray(caseResult.getWarningsJson()));
            view.put("ruleSignals", parseJsonValue(caseResult.getSignalsJson()));
        }
        view.put("status", result.getStatus()); view.put("correctness", result.getCorrectness());
        view.put("relevance", result.getRelevance()); view.put("completeness", result.getCompleteness());
        view.put("usefulness", result.getUsefulness()); view.put("safety", result.getSafety());
        view.put("overall", result.getOverall()); view.put("verdict", result.getVerdict());
        view.put("reason", result.getReason()); view.put("issues", parseArray(result.getIssuesJson()));
        view.put("rawResponse", parseJsonValue(result.getRawResponse()));
        view.put("errorMessage", result.getErrorMessage()); return view;
    }

    private AiEvalRun requireRun(String evalRunId) {
        AiEvalRun run = dao.findRun(requireText(evalRunId, "evalRunId 不能为空"));
        if (run == null) throw new IllegalArgumentException("评测任务不存在");
        return run;
    }

    private AiEvalJudgeJob requireJob(String judgeJobId) {
        AiEvalJudgeJob job = dao.findJudgeJob(requireText(judgeJobId, "judgeJobId 不能为空"));
        if (job == null) throw new IllegalArgumentException("Judge 任务不存在");
        return job;
    }

    private static JSONObject parseObject(String value) { try { JSONObject object = JSON.parseObject(value); return object == null ? new JSONObject() : object; } catch (Exception ignored) { return new JSONObject(); } }
    private static Object parseArray(String value) { try { JSONArray array = JSON.parseArray(value); return array == null ? List.of() : array; } catch (Exception ignored) { return List.of(); } }
    private static Object parseJsonValue(String value) {
        if (value == null || value.isBlank()) return null;
        try { return JSON.parse(value); } catch (Exception ignored) { return value; }
    }
    private static int score(JSONObject object, String field, int min, int max) { return EvalJudgeResponseSupport.score(object, field, min, max); }
    private static String normalizeScope(String value) { String scope = blankToDefault(value, "ALL").toUpperCase(Locale.ROOT); if (!List.of("ALL", "LOW_SCORE", "FAILED", "SELECTED").contains(scope)) throw new IllegalArgumentException("不支持的 Judge 范围"); return scope; }
    private static String requireText(String value, String message) { if (value == null || value.isBlank()) throw new IllegalArgumentException(message); return value.trim(); }
    private static String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String firstNonBlank(String... values) { if (values != null) for (String value : values) if (value != null && !value.isBlank()) return value; return null; }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String cap(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "…[截断]"; }

    @PreDestroy
    public void shutdown() {
        activeWorkers.values().forEach(ExecutorService::shutdownNow);
        coordinators.shutdownNow();
        try { coordinators.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Data
    public static class JudgeCommand {
        private String scopeType;
        private Double threshold;
        private List<String> selectedResultIds;
        private String modelId;
        private String apiId;
        private Integer concurrency;
    }
}
