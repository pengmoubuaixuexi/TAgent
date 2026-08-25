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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ExecutorService coordinators = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "eval-judge-coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, ExecutorService> activeWorkers = new ConcurrentHashMap<>();

    @Transactional
    public Map<String, Object> createJudgeJob(String evalRunId, JudgeCommand command) {
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
        String model = blankToDefault(normalized.getModelId(), "deepseek-v4-pro");
        String apiId = blankToDefault(normalized.getApiId(), "2344_api");
        int concurrency = Math.max(1, Math.min(normalized.getConcurrency() == null ? 3 : normalized.getConcurrency(), 8));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("threshold", normalized.getThreshold() == null ? 0.8 : normalized.getThreshold());
        config.put("selectedResultIds", normalized.getSelectedResultIds() == null ? List.of() : normalized.getSelectedResultIds());
        config.put("concurrency", concurrency);
        config.put("temperature", 0);
        LocalDateTime now = LocalDateTime.now();
        AiEvalJudgeJob job = AiEvalJudgeJob.builder()
                .judgeJobId(UUID.randomUUID().toString()).evalRunId(evalRunId).scopeType(scope).status("QUEUED")
                .modelId(model).apiId(apiId).rubricVersion("semantic-v2-full-trace").configJson(JSON.toJSONString(config))
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
                    try {
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
                        dao.insertJudgeResult(AiEvalJudgeResult.builder()
                                .judgeResultId(UUID.randomUUID().toString()).judgeJobId(currentJob.getJudgeJobId())
                                .resultId(result.getResultId()).status("FAILED").errorMessage(message(error))
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
            dao.updateRunJudgeScore(run.getEvalRunId(), average, "COMPLETED");
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
                extractMemoryContext(trace), extractToolContext(trace), stmContext,
                extractRagContext(trace), extractStepContext(trace), trace.getString("startTime"));
        String raw = call(api, job.getModelId(), prompt);
        JSONObject parsed;
        try {
            parsed = parseJudgeJson(raw);
        } catch (Exception firstParseError) {
            // 旧版 Judge 对非法 JSON 会以同一 prompt 完整重判一次，避免单题因偶发格式问题丢失。
            raw = call(api, job.getModelId(), prompt);
            parsed = parseJudgeJson(raw);
        }
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
                + "{\"correctness\":5,\"relevance\":5,\"completeness\":5,\"usefulness\":5,\"safety\":5,\"overall\":95,\"verdict\":\"EXCELLENT\",\"reason\":\"\",\"issues\":[]}\n\n"
                + nowBlock + memoryBlock + stmBlock + ragBlock + toolBlock + stepBlock
                + "【参考答案/判定要点】\n" + blankToDefault(evalCase.getReferenceAnswer(), "未提供；请依据问题、上下文与证据独立判断") + "\n"
                + "参考答案只表示应覆盖的事实或约束，语义等价即可，不要求逐字匹配。\n"
                + "【用户问题】\n" + evalCase.getQuestion() + "\n"
                + "【待评答案】\n" + cap(result.getFinalAnswer(), 14_000);
    }

    private String call(AiClientApiVO api, String model, String prompt) throws Exception {
        JSONObject body = new JSONObject(true);
        body.put("model", model); body.put("stream", false); body.put("max_tokens", 3000); body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        String path = blankToDefault(api.getCompletionsPath(), "/v1/chat/completions");
        String url = api.getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180))
                        .header("Authorization", "Bearer " + api.getApiKey()).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Judge HTTP " + response.statusCode() + ": " + cap(response.body(), 300));
                }
                JSONObject root = JSON.parseObject(response.body());
                return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            } catch (Exception error) {
                last = error;
                Thread.sleep(1000L * attempt);
            }
        }
        throw last;
    }

    private static JSONObject parseJudgeJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{'), end = value.lastIndexOf('}');
        if (start >= 0 && end > start) value = value.substring(start, end + 1);
        JSONObject parsed = JSON.parseObject(value);
        if (parsed == null) throw new IllegalArgumentException("Judge 返回为空");
        for (String field : List.of("correctness", "relevance", "completeness", "usefulness", "safety", "overall")) {
            if (!parsed.containsKey(field)) throw new IllegalArgumentException("Judge 缺少字段：" + field);
        }
        return parsed;
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
        if (traceJson == null || traceJson.isBlank()) return new JSONObject(true);
        try {
            JSONObject trace = JSON.parseObject(traceJson);
            if (trace == null) throw new IllegalArgumentException("评测 Trace 为空");
            return trace;
        } catch (Exception error) {
            throw new IllegalArgumentException("评测 Trace 解析失败：" + message(error), error);
        }
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
            Map<String, AiEvalCaseResult> caseResults = new LinkedHashMap<>();
            for (AiEvalCaseResult item : dao.listCaseResults(job.getEvalRunId())) caseResults.put(item.getResultId(), item);
            Map<String, AiEvalCase> cases = new LinkedHashMap<>();
            AiEvalRun run = dao.findRun(job.getEvalRunId());
            if (run != null) {
                for (AiEvalCase item : dao.listCases(run.getVersionId(), false)) cases.put(item.getCaseId(), item);
            }
            view.put("results", dao.listJudgeResults(job.getJudgeJobId()).stream().map(result -> {
                AiEvalCaseResult caseResult = caseResults.get(result.getResultId());
                AiEvalCase evalCase = caseResult == null ? null : cases.get(caseResult.getCaseId());
                return resultView(result, caseResult, evalCase);
            }).toList());
        }
        return view;
    }

    private Map<String, Object> resultView(AiEvalJudgeResult result, AiEvalCaseResult caseResult, AiEvalCase evalCase) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("judgeResultId", result.getJudgeResultId()); view.put("resultId", result.getResultId());
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
    private static int score(JSONObject object, String field, int min, int max) { int value = object.getIntValue(field); if (value < min || value > max) throw new IllegalArgumentException("Judge 字段越界：" + field); return value; }
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
