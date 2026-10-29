package cn.bugstack.ai.trigger.improvement;

import cn.bugstack.ai.infrastructure.adapter.repository.trace.RunTraceStore;
import cn.bugstack.ai.trigger.eval.EvalCaseConfig;
import cn.bugstack.ai.trigger.eval.EvalDatasetService;
import cn.bugstack.ai.trigger.eval.EvalRunService;
import cn.bugstack.ai.trigger.workspace.WorkspaceService;
import cn.bugstack.ai.trigger.workspace.WorkspaceVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Continuous improvement: review a run, propose node changes, save them as a draft, compare the
 * draft with the active version on evaluation questions, and publish only when the owner says so.
 */
@Slf4j
@Service
public class ImprovementService {

    static final String ANALYZING = "ANALYZING", FAILED = "FAILED", EXTERNAL = "EXTERNAL", NO_CHANGE = "NO_CHANGE",
            PENDING_VALIDATION = "PENDING_VALIDATION", VALIDATING = "VALIDATING", VALIDATION_FAILED = "VALIDATION_FAILED",
            VALIDATED = "VALIDATED", ADOPTED = "ADOPTED", REJECTED = "REJECTED";
    static final Set<String> CATEGORIES = Set.of("PROMPT_UNCLEAR", "WRONG_TOOL", "MISSING_TOOL", "RETRIEVAL_MISS",
            "MODEL_LIMIT", "EXTERNAL_FAILURE", "NONE");
    static final Map<String, Integer> FIELD_LIMITS = Map.of("systemPrompt", 12000, "outputRequirement", 4000, "taskPrompt", 12000);
    static final int MAX_CHANGES = 4;
    static final int MAX_EXTRA_CASES = 9;
    private static final Set<String> OPEN = Set.of(ANALYZING, PENDING_VALIDATION, VALIDATING, VALIDATION_FAILED, VALIDATED);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RunTraceStore traces;
    private final RunReviewService reviewer;
    private final WorkspaceService workspace;
    private final WorkspaceVersionService versions;
    private final EvalDatasetService datasets;
    private final EvalRunService evalRuns;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "run-review");
        thread.setDaemon(true);
        return thread;
    });
    private final Cache<String, AtomicInteger> hourly = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(1)).build();

    public ImprovementService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db, ObjectMapper json, RunTraceStore traces,
                              RunReviewService reviewer, WorkspaceService workspace, WorkspaceVersionService versions,
                              EvalDatasetService datasets, EvalRunService evalRuns) {
        this.db = db;
        this.json = json;
        this.traces = traces;
        this.reviewer = reviewer;
        this.workspace = workspace;
        this.versions = versions;
        this.datasets = datasets;
        this.evalRuns = evalRuns;
    }

    @PreDestroy
    public void stop() {
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ request and review

    /** Starts a review of an owned run ({@code runId}) or of a case of an owned evaluation ({@code evalRunId}, {@code resultId}). */
    public Map<String, Object> request(String user, JsonNode input) {
        String runId, kind, evalRunId = null, resultId = null, agentId;
        Integer version;
        if (input.hasNonNull("resultId")) {
            resultId = text(input, "resultId");
            evalRunId = text(input, "evalRunId");
            var rows = db.queryForList("""
                    SELECT r.agent_run_id,r.agent_id,e.selected_agent_id FROM ai_eval_case_result r JOIN ai_eval_run e ON e.eval_run_id=r.eval_run_id
                    JOIN ai_eval_dataset d ON d.dataset_id=e.dataset_id WHERE r.result_id=? AND r.eval_run_id=? AND d.owner_user_id=?""",
                    resultId, evalRunId, user);
            if (rows.isEmpty() || rows.get(0).get("agent_run_id") == null) throw notFound();
            runId = (String) rows.get(0).get("agent_run_id");
            kind = "EVAL_CASE";
            // The run history records the agent and version actually used; a result may lack them.
            var history = db.queryForList("SELECT agent_id,agent_version FROM ai_run_history WHERE run_id=?", runId);
            Object recorded = history.isEmpty() ? null : history.get(0).get("agent_id");
            agentId = (String) (recorded != null ? recorded : rows.get(0).get("agent_id") != null ? rows.get(0).get("agent_id") : rows.get(0).get("selected_agent_id"));
            version = history.isEmpty() || history.get(0).get("agent_version") == null ? null : ((Number) history.get(0).get("agent_version")).intValue();
        } else {
            runId = text(input, "runId");
            var rows = db.queryForList("SELECT agent_id,agent_version FROM ai_run_history WHERE run_id=? AND user_id=?", runId, user);
            if (rows.isEmpty()) throw notFound();
            agentId = (String) rows.get(0).get("agent_id");
            version = rows.get(0).get("agent_version") == null ? null : ((Number) rows.get(0).get("agent_version")).intValue();
            kind = "RUN";
        }
        if (agentId == null) throw bad("这次运行没有绑定 Agent，无法复盘");
        var open = db.queryForList("SELECT suggestion_id,status FROM ai_improvement_suggestion WHERE owner_user_id=? AND source_run_id=? ORDER BY created_at DESC LIMIT 1", user, runId);
        if (!open.isEmpty() && OPEN.contains((String) open.get(0).get("status"))) return view(user, (String) open.get(0).get("suggestion_id"));
        if (hourly.get(user, key -> new AtomicInteger()).incrementAndGet() > 10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "复盘分析过于频繁，请稍后再试");
        Integer running = db.queryForObject("SELECT COUNT(*) FROM ai_improvement_suggestion WHERE owner_user_id=? AND status=?", Integer.class, user, ANALYZING);
        if (running != null && running >= 2) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已有两项复盘正在分析，请等完成后再发起");
        String id = "imp_" + UUID.randomUUID().toString().replace("-", "");
        db.update("""
                INSERT INTO ai_improvement_suggestion(suggestion_id,owner_user_id,agent_id,agent_version,source_kind,source_run_id,eval_run_id,eval_result_id,status)
                VALUES(?,?,?,?,?,?,?,?,?)""", id, user, agentId, version, kind, runId, evalRunId, resultId, ANALYZING);
        workers.execute(() -> analyze(id));
        return view(user, id);
    }

    void analyze(String id) {
        Map<String, Object> row = db.queryForMap("SELECT * FROM ai_improvement_suggestion WHERE suggestion_id=?", id);
        String user = (String) row.get("owner_user_id"), runId = (String) row.get("source_run_id"), agentId = (String) row.get("agent_id");
        Integer version = row.get("agent_version") == null ? null : ((Number) row.get("agent_version")).intValue();
        try {
            var runs = db.queryForList("SELECT question,answer,status,last_error,strategy FROM ai_run_history WHERE run_id=?", runId);
            if (runs.isEmpty()) throw new IllegalStateException("运行记录不存在");
            Map<String, Object> run = runs.get(0);
            List<Map<String, Object>> nodes = traces.nodes(runId);
            if (nodes.isEmpty()) throw new IllegalStateException("这次运行没有轨迹记录（轨迹功能上线前的运行，或已按保留期清理），无法复盘");
            ObjectNode diagnosis = json.createObjectNode();
            String external = ReviewDigest.externalFailure(run, nodes);
            if (external != null) {
                diagnosis.put("external", true).put("externalReason", external).put("category", "EXTERNAL_FAILURE")
                        .put("summary", "运行因外部故障失败，修改 Agent 解决不了：" + external);
                finish(id, EXTERNAL, diagnosis, json.createObjectNode(), null, 0);
                return;
            }
            AgentSource source = agentSource(user, agentId, version);
            ReviewDigest.Config config = ReviewDigest.config(source.config(), version);
            String evidence = "## 运行\n" + ReviewDigest.run(run, complaint(row)) + "\n## Agent 节点配置\n" + config.text()
                    + (source.editable() ? "" : "\n注意：这个 Agent 是公共模板的副本，不能直接修改。promptChanges 请留空，把建议写进 otherAdvice。\n")
                    + "\n## 运行轨迹\n" + ReviewDigest.trace(nodes);
            RunReviewService.Review review = reviewer.review(user, evidence, seqs -> expand(runId, seqs));
            Set<Integer> known = new HashSet<>();
            for (Map<String, Object> node : nodes) known.add(((Number) node.get("seq")).intValue());
            ObjectNode suggestion = json.createObjectNode();
            diagnosis = diagnosis(review.result(), known, config);
            ArrayNode changes = changes(review.result(), config, source.editable());
            suggestion.set("promptChanges", changes);
            suggestion.set("otherAdvice", advice(review.result()));
            String status = diagnosis.path("external").asBoolean() ? EXTERNAL : changes.isEmpty() ? NO_CHANGE : PENDING_VALIDATION;
            if (!diagnosis.path("external").asBoolean() && !source.editable()) suggestion.put("notEditable", "这个 Agent 是公共模板的副本，建议需要在你自己的 Agent 中手动采用");
            finish(id, status, diagnosis, suggestion, review.model(), review.tokens());
        } catch (Exception failure) {
            log.warn("[Improvement] review failed suggestion={} run={}: {}", id, runId, failure.toString());
            db.update("UPDATE ai_improvement_suggestion SET status=?,error_message=?,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=?",
                    FAILED, clip(message(failure), 1000), id);
        }
    }

    private void finish(String id, String status, ObjectNode diagnosis, ObjectNode suggestion, String model, long tokens) {
        db.update("""
                UPDATE ai_improvement_suggestion SET status=?,diagnosis_json=?,suggestion_json=?,review_model=?,review_tokens=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE suggestion_id=? AND status=?""", status, diagnosis.toString(), suggestion.toString(), model, tokens, id, ANALYZING);
    }

    private ReviewDigest.Complaint complaint(Map<String, Object> row) {
        if ("EVAL_CASE".equals(row.get("source_kind"))) {
            var results = db.queryForList("SELECT status,error_message,overall_score,warnings_json FROM ai_eval_case_result WHERE result_id=?", row.get("eval_result_id"));
            if (results.isEmpty()) return null;
            var r = results.get(0);
            return new ReviewDigest.Complaint(null, null, null, (String) r.get("status"), (String) r.get("error_message"),
                    r.get("overall_score") == null ? null : ((Number) r.get("overall_score")).doubleValue(), (String) r.get("warnings_json"));
        }
        var feedback = db.queryForList("SELECT rating,comment_text,corrected_answer FROM ai_run_feedback WHERE owner_user_id=? AND run_id=?",
                row.get("owner_user_id"), row.get("source_run_id"));
        if (feedback.isEmpty()) return null;
        var f = feedback.get(0);
        return new ReviewDigest.Complaint((String) f.get("rating"), (String) f.get("comment_text"), (String) f.get("corrected_answer"),
                null, null, null, null);
    }

    record AgentSource(JsonNode config, boolean editable, String name) { }

    /** Configuration the run used: the recorded version of an editable agent, else the agent's current configuration. */
    private AgentSource agentSource(String user, String agentId, Integer version) {
        var rows = db.queryForList("SELECT agent_name,workspace_config,source_agent_id,channel FROM ai_agent WHERE agent_id=? AND owner_user_id=?", agentId, user);
        if (rows.isEmpty()) return new AgentSource(null, false, agentId);
        Map<String, Object> agent = rows.get(0);
        boolean editable = agent.get("workspace_config") != null && agent.get("source_agent_id") == null && !"candidate".equals(agent.get("channel"));
        JsonNode config = null;
        if (editable && version != null) {
            try {
                config = (JsonNode) versions.version(user, agentId, version).get("config");
            } catch (RuntimeException missing) {
                config = null;
            }
        }
        if (config == null && agent.get("workspace_config") != null) {
            try { config = json.readTree(agent.get("workspace_config").toString()); } catch (Exception ignored) { }
        }
        return new AgentSource(config, editable, (String) agent.get("agent_name"));
    }

    /** Full content of trace nodes the reviewer asked for, bounded per node. */
    String expand(String runId, List<Integer> seqs) {
        StringBuilder out = new StringBuilder();
        for (Integer seq : seqs) {
            ObjectNode node = traces.storedNode(runId, seq, 20_000);
            if (node == null) { out.append("#").append(seq).append("：不存在\n"); continue; }
            out.append("\n#").append(seq).append('\n');
            if (node.has("request")) {
                for (JsonNode message : node.path("request").path("messages")) {
                    out.append("[新增消息 ").append(message.path("role").asText()).append("] ")
                            .append(ReviewDigest.clip(message.path("content").isTextual() ? message.path("content").asText() : message.path("content").toString(), 3000));
                    if (message.has("tool_calls")) out.append(" 调用工具 ").append(ReviewDigest.clip(message.path("tool_calls").toString(), 1500));
                    out.append('\n');
                }
                JsonNode reply = node.path("reply");
                if (reply.path("reasoning").isTextual()) out.append("[思考] ").append(ReviewDigest.clip(reply.path("reasoning").asText(), 2000)).append('\n');
                if (reply.path("content").isTextual()) out.append("[回复] ").append(ReviewDigest.clip(reply.path("content").asText(), 4000)).append('\n');
                if (reply.has("toolCalls")) out.append("[请求调用工具] ").append(ReviewDigest.clip(reply.path("toolCalls").toString(), 2000)).append('\n');
            } else if (node.has("data")) {
                out.append(ReviewDigest.clip(node.path("data").toString(), 3000)).append('\n');
            } else {
                out.append("[参数] ").append(ReviewDigest.clip(node.path("arguments").asText(), 3000)).append('\n')
                        .append("[返回] ").append(ReviewDigest.clip(node.path("result").asText(), 6000)).append('\n');
            }
            if (node.has("error")) out.append("[错误] ").append(ReviewDigest.clip(node.path("error").asText(), 1500)).append('\n');
        }
        return out.toString();
    }

    private ObjectNode diagnosis(JsonNode result, Set<Integer> known, ReviewDigest.Config config) {
        ObjectNode out = json.createObjectNode();
        boolean external = result.path("external").asBoolean(false) || "EXTERNAL_FAILURE".equals(result.path("category").asText());
        String category = external ? "EXTERNAL_FAILURE" : result.path("category").asText("NONE");
        out.put("external", external);
        if (external) out.put("externalReason", clip(result.path("externalReason").asText(""), 1000));
        out.put("category", CATEGORIES.contains(category) ? category : "NONE");
        out.put("summary", clip(result.path("summary").asText(""), 1000));
        String node = result.path("problemNode").asText("");
        if (config.nodes().containsKey(node)) {
            out.put("problemNode", node);
            out.put("problemNodeName", config.nodes().get(node).path("name").asText(node));
        }
        ArrayNode evidence = out.putArray("evidence");
        for (JsonNode item : result.path("evidence")) {
            // seq 0 marks evidence taken from the node configuration rather than a trace record.
            if (evidence.size() >= 10 || !item.path("seq").canConvertToInt()
                    || item.path("seq").asInt() != 0 && !known.contains(item.path("seq").asInt())) continue;
            evidence.addObject().put("seq", item.path("seq").asInt()).put("observation", clip(item.path("observation").asText(""), 600));
        }
        return out;
    }

    /** Only text fields of nodes the reviewer saw in full; the "before" is the configuration the run used. */
    private ArrayNode changes(JsonNode result, ReviewDigest.Config config, boolean editable) {
        ArrayNode out = json.createArrayNode();
        if (!editable || result.path("external").asBoolean(false)) return out;
        Set<String> seen = new HashSet<>();
        for (JsonNode change : result.path("promptChanges")) {
            if (out.size() >= MAX_CHANGES) break;
            String nodeId = change.path("nodeId").asText(""), field = change.path("field").asText(""), after = change.path("after").asText("");
            JsonNode node = config.nodes().get(nodeId);
            if (node == null || !config.completeNodes().contains(nodeId) || !FIELD_LIMITS.containsKey(field) || !seen.add(nodeId + "/" + field)) continue;
            if ("taskPrompt".equals(field) && !"EXECUTOR_CLIENT".equals(node.path("role").asText())) continue;
            if (after.isBlank() || after.length() > FIELD_LIMITS.get(field) || after.equals(node.path(field).asText(""))) continue;
            out.addObject().put("nodeId", nodeId).put("nodeName", node.path("name").asText(nodeId)).put("field", field)
                    .put("before", node.path(field).asText("")).put("after", after)
                    .put("reason", clip(change.path("reason").asText(""), 600));
        }
        return out;
    }

    private ArrayNode advice(JsonNode result) {
        ArrayNode out = json.createArrayNode();
        if (result.path("external").asBoolean(false)) return out;
        for (JsonNode item : result.path("otherAdvice")) {
            if (out.size() >= 6 || item.path("advice").asText("").isBlank()) continue;
            String type = item.path("type").asText("OTHER");
            out.addObject().put("type", Set.of("TOOL_BINDING", "PARAMETER", "KNOWLEDGE", "MODEL").contains(type) ? type : "OTHER")
                    .put("advice", clip(item.path("advice").asText(), 600));
        }
        return out;
    }

    // ------------------------------------------------------------------ draft and validation

    /** Saves the changes as a draft of the agent and compares it with the active version on evaluation questions. */
    public Map<String, Object> validate(String user, String id) {
        Map<String, Object> row = owned(user, id);
        if (!Set.of(PENDING_VALIDATION, VALIDATION_FAILED).contains((String) row.get("status")))
            throw conflict("当前状态不能开始验证");
        String agentId = (String) row.get("agent_id");
        ObjectNode suggestion = object(row.get("suggestion_json"));
        if (suggestion.path("promptChanges").isEmpty()) throw bad("这条建议没有可以自动应用的修改");
        Map<String, Object> current = versions.draft(user, agentId);
        if (((Number) current.get("revision")).intValue() != 0)
            throw conflict("这个 Agent 有尚未发布的草稿（编辑器中保存的修改）。请先发布或放弃它，再验证这条建议");
        int baseVersion = ((Number) current.get("baseVersion")).intValue();
        ObjectNode config = ((JsonNode) current.get("config")).deepCopy();
        for (JsonNode change : suggestion.path("promptChanges")) {
            ObjectNode node = null;
            for (JsonNode candidate : config.path("nodes")) if (change.path("nodeId").asText().equals(candidate.path("nodeId").asText())) node = (ObjectNode) candidate;
            if (node == null || !FIELD_LIMITS.containsKey(change.path("field").asText())
                    || !change.path("before").isTextual()
                    || !node.path(change.path("field").asText()).asText("").equals(change.path("before").asText()))
                throw conflict("建议涉及的节点或提示词已变化，请基于当前版本重新运行并复盘，避免覆盖后续修改");
            node.put(change.path("field").asText(), change.path("after").asText());
        }
        config.put("version", baseVersion);
        config.put("draftRevision", 0);
        String candidate = null;
        int revision = 0;
        try {
            revision = ((Number) versions.saveDraft(user, agentId, config).get("revision")).intValue();
            String name = Objects.toString(db.queryForObject("SELECT agent_name FROM ai_agent WHERE agent_id=?", String.class, agentId), "Agent");
            candidate = workspace.createCandidate(user, name, config);
            Map<String, Object> dataset = datasets.createDataset("改进验证 · " + clip(name, 40),
                    "改进建议 " + id + " 的对比验证：出问题的原始问题，加上这个 Agent 评测过的题目（最多 " + MAX_EXTRA_CASES + " 道）。", "INDEPENDENT", user);
            String datasetId = String.valueOf(dataset.get("datasetId"));
            List<String> questions = questions(user, row, agentId);
            for (int i = 0; i < questions.size(); i++) {
                var command = new EvalDatasetService.CaseCommand();
                command.setStableKey("improvement-" + (i + 1));
                command.setSequenceNo(i + 1);
                command.setQuestion(questions.get(i));
                command.setCategory(i == 0 ? "problem-run" : "regression");
                command.setConfig(new EvalCaseConfig().normalized());
                command.setEnabled(true);
                datasets.saveCase(datasetId, null, command);
            }
            String versionId = String.valueOf(datasets.publish(datasetId, "改进验证").get("versionId"));
            String baseline = run(user, versionId, agentId, "改进验证 · 当前版本 v" + baseVersion);
            String trial = run(user, versionId, candidate, "改进验证 · 草稿");
            ObjectNode validation = json.createObjectNode().put("datasetId", datasetId).put("versionId", versionId)
                    .put("questions", questions.size()).put("baselineRunId", baseline).put("candidateRunId", trial);
            db.update("""
                    UPDATE ai_improvement_suggestion SET status=?,draft_base_version=?,draft_revision=?,candidate_agent_id=?,validation_json=?,
                    error_message=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=?""",
                    VALIDATING, baseVersion, revision, candidate, validation.toString(), id);
        } catch (RuntimeException failure) {
            if (candidate != null) workspace.archiveCandidate(user, candidate);
            if (revision > 0) db.update("DELETE FROM ai_agent_draft WHERE agent_id=? AND owner_user_id=? AND revision=? AND base_version=?", agentId, user, revision, baseVersion);
            db.update("UPDATE ai_improvement_suggestion SET status=?,error_message=?,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=?",
                    VALIDATION_FAILED, clip(message(failure), 1000), id);
            if (failure instanceof ResponseStatusException) throw failure;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "验证没有启动：" + message(failure));
        }
        return view(user, id);
    }

    /** The problem question first, then distinct questions this agent was evaluated on by its owner. */
    private List<String> questions(String user, Map<String, Object> row, String agentId) {
        Set<String> questions = new LinkedHashSet<>();
        String problem = db.queryForObject("SELECT question FROM ai_run_history WHERE run_id=?", String.class, row.get("source_run_id"));
        if (problem == null || problem.isBlank()) throw bad("原始问题为空，无法验证");
        questions.add(clip(problem, 20000));
        for (String question : db.queryForList("""
                SELECT c.question FROM ai_eval_case_result r JOIN ai_eval_run e ON e.eval_run_id=r.eval_run_id
                JOIN ai_eval_case c ON c.case_id=r.case_id JOIN ai_eval_dataset d ON d.dataset_id=e.dataset_id
                WHERE e.selected_agent_id=? AND d.owner_user_id=? AND d.name NOT LIKE '改进验证%' ORDER BY r.created_at DESC LIMIT 50""",
                String.class, agentId, user)) {
            if (questions.size() > MAX_EXTRA_CASES) break;
            if (question != null && !question.isBlank()) questions.add(question);
        }
        return new ArrayList<>(questions);
    }

    private String run(String user, String versionId, String agentId, String name) {
        var command = new EvalRunService.RunCommand();
        command.setVersionId(versionId);
        command.setSelectedAgentId(agentId);
        command.setUserId(user);
        command.setTenantId("default");
        // The owner's own agent and models; an isolated evaluation user could not run them.
        command.setMemoryPolicy("FIXED_USER");
        // Both evaluations start together. One worker per side uses two foreground slots,
        // rather than four competing for the normal three-run account limit.
        command.setConcurrency(1);
        command.setName(name);
        return String.valueOf(evalRuns.createRun(command).get("evalRunId"));
    }

    /** Collects the comparison once both evaluation runs have finished. */
    private void refresh(Map<String, Object> row) {
        if (!VALIDATING.equals(row.get("status"))) return;
        ObjectNode validation = object(row.get("validation_json"));
        Map<String, Object> baseline = evalRuns.getRun(validation.path("baselineRunId").asText());
        Map<String, Object> candidate = evalRuns.getRun(validation.path("candidateRunId").asText());
        if (!finished(baseline) || !finished(candidate)) return;
        if (!completeEvaluation(baseline, validation.path("questions").asInt())
                || !completeEvaluation(candidate, validation.path("questions").asInt())) {
            failValidation(row);
            return;
        }
        ObjectNode comparison = validation.putObject("comparison");
        comparison.set("baseline", metrics(baseline));
        comparison.set("candidate", metrics(candidate));
        ArrayNode cases = comparison.putArray("cases");
        Map<String, Map<String, Object>> after = new LinkedHashMap<>();
        for (Map<String, Object> result : results(candidate)) after.put(String.valueOf(result.get("stableKey")), result);
        for (Map<String, Object> before : results(baseline)) {
            Map<String, Object> next = after.getOrDefault(String.valueOf(before.get("stableKey")), Map.of());
            ObjectNode line = cases.addObject().put("question", clip(String.valueOf(before.get("question")), 300));
            line.set("baseline", caseView(before));
            line.set("candidate", caseView(next));
        }
        String user = (String) row.get("owner_user_id");
        workspace.archiveCandidate(user, (String) row.get("candidate_agent_id"));
        db.update("UPDATE ai_improvement_suggestion SET status=?,validation_json=?,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=? AND status=?",
                VALIDATED, validation.toString(), row.get("suggestion_id"), VALIDATING);
    }

    /** Rule scoring is final once the run leaves the queue; a later LLM judge does not change it. */
    private static boolean finished(Map<String, Object> evalRun) {
        Object status = evalRun == null ? null : evalRun.get("status");
        return status != null && !Set.of("QUEUED", "RUNNING").contains(String.valueOf(status));
    }

    /** Completed rules, all expected questions and no interrupted/error cases. A low score is still a valid comparison. */
    private boolean completeEvaluation(Map<String, Object> run, int expected) {
        if (expected < 1 || run == null || !Set.of("RULE_COMPLETED", "JUDGING", "COMPLETED").contains(String.valueOf(run.get("status")))) return false;
        if (!(run.get("totalCases") instanceof Number total) || total.intValue() != expected
                || !(run.get("completedCases") instanceof Number completed) || completed.intValue() != expected) return false;
        List<Map<String,Object>> cases = results(run);
        if (cases.size() != expected) return false;
        Set<String> keys = new HashSet<>();
        for (Map<String,Object> result : cases) {
            if (!Set.of("PASS", "FAIL").contains(String.valueOf(result.get("status"))) || !keys.add(String.valueOf(result.get("stableKey")))) return false;
        }
        for (int i = 1; i <= expected; i++) if (!keys.contains("improvement-" + i)) return false;
        return true;
    }

    private void failValidation(Map<String,Object> row) {
        String user = (String) row.get("owner_user_id");
        workspace.archiveCandidate(user, (String) row.get("candidate_agent_id"));
        // Preserve a draft the owner edited after validation started.
        db.update("DELETE FROM ai_agent_draft WHERE agent_id=? AND owner_user_id=? AND revision=? AND base_version=?",
                row.get("agent_id"), user, row.get("draft_revision"), row.get("draft_base_version"));
        db.update("UPDATE ai_improvement_suggestion SET status=?,error_message=?,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=? AND status IN (?,?)",
                VALIDATION_FAILED, "对比评测已取消、异常结束或题目结果不完整，请重新验证", row.get("suggestion_id"), VALIDATING, VALIDATED);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> evalRun) {
        return evalRun.get("results") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private ObjectNode metrics(Map<String, Object> evalRun) {
        List<Map<String, Object>> results = results(evalRun);
        int passed = 0, scored = 0;
        double score = 0;
        long latency = 0;
        List<String> runIds = new ArrayList<>();
        for (Map<String, Object> result : results) {
            if ("PASS".equals(result.get("status"))) passed++;
            if (result.get("scores") instanceof Map<?, ?> scores && scores.get("overall") instanceof Number overall) {
                score += overall.doubleValue();
                scored++;
            }
            if (result.get("latencyMs") instanceof Number ms) latency += ms.longValue();
            if (result.get("agentRunId") != null) runIds.add(String.valueOf(result.get("agentRunId")));
        }
        Long tokens = runIds.isEmpty() ? 0L : db.queryForObject("SELECT COALESCE(SUM(actual_tokens),0) FROM ai_model_usage_attempt WHERE run_id IN ("
                + String.join(",", runIds.stream().map(r -> "?").toList()) + ")", Long.class, runIds.toArray());
        ObjectNode out = json.createObjectNode();
        out.put("status", String.valueOf(evalRun.get("status")));
        out.put("total", results.size()).put("passed", passed);
        out.put("passRate", results.isEmpty() ? 0 : Math.round(passed * 1000.0 / results.size()) / 10.0);
        out.put("averageScore", scored == 0 ? 0 : Math.round(score / scored * 1000) / 10.0);
        out.put("averageLatencyMs", results.isEmpty() ? 0 : latency / results.size());
        out.put("tokens", tokens == null ? 0 : tokens);
        return out;
    }

    private ObjectNode caseView(Map<String, Object> result) {
        ObjectNode out = json.createObjectNode();
        if (result.isEmpty()) return out.put("status", "MISSING");
        out.put("status", String.valueOf(result.get("status")));
        if (result.get("scores") instanceof Map<?, ?> scores && scores.get("overall") instanceof Number overall)
            out.put("score", Math.round(overall.doubleValue() * 1000) / 10.0);
        if (result.get("latencyMs") instanceof Number ms) out.put("latencyMs", ms.longValue());
        if (result.get("agentRunId") != null) out.put("runId", String.valueOf(result.get("agentRunId")));
        // Rule scores do not tell a looked-up answer from a guessed one; the owner compares the answers.
        if (result.get("resultId") != null) {
            db.queryForList("SELECT final_answer FROM ai_eval_case_result WHERE result_id=?", String.class, result.get("resultId"))
                    .stream().filter(Objects::nonNull).findFirst().ifPresent(answer -> out.put("answer", clip(answer, 1500)));
        }
        return out;
    }

    // ------------------------------------------------------------------ decision

    /** Publishes the validated draft. The only path from a suggestion to an active version. */
    public Map<String, Object> adopt(String user, String id) {
        Map<String, Object> row = owned(user, id);
        refresh(row);
        row = owned(user, id);
        if (!VALIDATED.equals(row.get("status"))) throw conflict("请等对比验证完成后再启用");
        // Recheck old suggestions too: an earlier application version may have marked an interrupted evaluation as validated.
        ObjectNode validation = object(row.get("validation_json"));
        if (!completeEvaluation(evalRuns.getRun(validation.path("baselineRunId").asText()), validation.path("questions").asInt())
                || !completeEvaluation(evalRuns.getRun(validation.path("candidateRunId").asText()), validation.path("questions").asInt())) {
            failValidation(row);
            throw conflict("对比验证没有完整完成，请重新验证后再启用");
        }
        try {
            versions.publish(user, (String) row.get("agent_id"), json.createObjectNode()
                    .put("baseVersion", ((Number) row.get("draft_base_version")).intValue())
                    .put("draftRevision", ((Number) row.get("draft_revision")).intValue()));
        } catch (ResponseStatusException changed) {
            throw conflict("草稿或已启用的版本已经变化（可能在编辑器中改过），请在编辑器中确认后手动发布");
        }
        db.update("UPDATE ai_improvement_suggestion SET status=?,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=?", ADOPTED, id);
        return view(user, id);
    }

    /** Discards the suggestion, its draft (unless edited since) and a running validation. */
    public Map<String, Object> reject(String user, String id) {
        Map<String, Object> row = owned(user, id);
        String status = (String) row.get("status");
        if (Set.of(ADOPTED, REJECTED, ANALYZING).contains(status)) throw conflict("当前状态不能放弃");
        if (VALIDATING.equals(status)) {
            ObjectNode validation = object(row.get("validation_json"));
            for (String run : List.of(validation.path("baselineRunId").asText(""), validation.path("candidateRunId").asText(""))) {
                try { if (!run.isBlank()) evalRuns.cancelRun(run); } catch (RuntimeException ignored) { }
            }
        }
        if (row.get("draft_revision") != null) {
            db.update("DELETE FROM ai_agent_draft WHERE agent_id=? AND owner_user_id=? AND revision=? AND base_version=?",
                    row.get("agent_id"), user, row.get("draft_revision"), row.get("draft_base_version"));
        }
        workspace.archiveCandidate(user, (String) row.get("candidate_agent_id"));
        db.update("UPDATE ai_improvement_suggestion SET status=?,updated_at=CURRENT_TIMESTAMP(3) WHERE suggestion_id=?", REJECTED, id);
        return view(user, id);
    }

    // ------------------------------------------------------------------ reading

    public Map<String, Object> view(String user, String id) {
        Map<String, Object> row = owned(user, id);
        if (VALIDATING.equals(row.get("status"))) {
            try { refresh(row); } catch (RuntimeException unavailable) { log.debug("[Improvement] refresh deferred: {}", unavailable.toString()); }
            row = owned(user, id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestionId", row.get("suggestion_id"));
        out.put("status", row.get("status"));
        out.put("sourceKind", row.get("source_kind"));
        out.put("runId", row.get("source_run_id"));
        out.put("evalRunId", row.get("eval_run_id"));
        out.put("agentId", row.get("agent_id"));
        out.put("agentVersion", row.get("agent_version"));
        var agent = db.queryForList("SELECT agent_name,workspace_version FROM ai_agent WHERE agent_id=?", row.get("agent_id"));
        out.put("agentName", agent.isEmpty() ? null : agent.get(0).get("agent_name"));
        out.put("currentVersion", agent.isEmpty() ? null : agent.get(0).get("workspace_version"));
        out.put("diagnosis", object(row.get("diagnosis_json")));
        out.put("suggestion", object(row.get("suggestion_json")));
        out.put("validation", object(row.get("validation_json")));
        out.put("draftRevision", row.get("draft_revision"));
        out.put("error", row.get("error_message"));
        out.put("reviewModel", row.get("review_model"));
        out.put("reviewTokens", row.get("review_tokens"));
        out.put("createdAt", row.get("created_at") == null ? null : row.get("created_at").toString());
        out.put("updatedAt", row.get("updated_at") == null ? null : row.get("updated_at").toString());
        return out;
    }

    public List<Map<String, Object>> list(String user, String runId) {
        List<Map<String, Object>> rows = runId == null || runId.isBlank()
                ? db.queryForList("SELECT suggestion_id FROM ai_improvement_suggestion WHERE owner_user_id=? ORDER BY created_at DESC LIMIT 50", user)
                : db.queryForList("SELECT suggestion_id FROM ai_improvement_suggestion WHERE owner_user_id=? AND source_run_id=? ORDER BY created_at DESC LIMIT 20", user, runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = view(user, (String) row.get("suggestion_id"));
            item.remove("suggestion");
            item.remove("validation");
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> owned(String user, String id) {
        var rows = db.queryForList("SELECT * FROM ai_improvement_suggestion WHERE suggestion_id=? AND owner_user_id=?", id, user);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    private ObjectNode object(Object value) {
        if (value == null) return json.createObjectNode();
        try {
            JsonNode node = json.readTree(value.toString());
            return node instanceof ObjectNode object ? object : json.createObjectNode();
        } catch (Exception broken) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode input, String field) {
        String value = input == null ? null : input.path(field).asText(null);
        if (value == null || value.isBlank() || value.length() > 128) throw bad("缺少 " + field);
        return value;
    }

    static String clip(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String message(Throwable failure) {
        if (failure instanceof ResponseStatusException status && status.getReason() != null) return status.getReason();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权访问"); }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
