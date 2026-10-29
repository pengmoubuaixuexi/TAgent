package cn.bugstack.ai.test.improvement;

import cn.bugstack.ai.domain.agent.service.trace.RunTrace;
import cn.bugstack.ai.domain.agent.service.trace.TraceRecorder;
import cn.bugstack.ai.infrastructure.adapter.repository.trace.RunTraceStore;
import cn.bugstack.ai.trigger.eval.EvalDatasetService;
import cn.bugstack.ai.trigger.eval.EvalRunService;
import cn.bugstack.ai.trigger.improvement.ImprovementService;
import cn.bugstack.ai.trigger.improvement.RunReviewService;
import cn.bugstack.ai.trigger.workspace.WorkspaceService;
import cn.bugstack.ai.trigger.workspace.WorkspaceVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ImprovementServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate db;
    private RunTraceStore traces;
    private RunReviewService reviewer;
    private WorkspaceService workspace;
    private WorkspaceVersionService versions;
    private EvalDatasetService datasets;
    private EvalRunService evalRuns;
    private ImprovementService service;
    private ObjectNode config;
    private final String run = UUID.randomUUID().toString();

    @Before
    public void setup() throws Exception {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        for (String name : List.of("V073__run_trace.sql", "V074__improvement_suggestions.sql")) {
            Path migration = Path.of("../docs/dev-ops/sql-migrations/" + name);
            if (!Files.exists(migration)) migration = Path.of("docs/dev-ops/sql-migrations/" + name);
            String sql = Files.readString(migration).replaceAll("COMMENT '[^']*'", "").replace(" JSON NULL", " CLOB NULL")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
            for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        }
        db.execute("CREATE TABLE ai_run_history(run_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(128),session_id VARCHAR(255),agent_id VARCHAR(64),"
                + "agent_version INT,strategy VARCHAR(32),status VARCHAR(32),question CLOB,answer CLOB,last_error CLOB)");
        db.execute("CREATE TABLE ai_run_feedback(owner_user_id VARCHAR(64),run_id VARCHAR(128),rating VARCHAR(16),comment_text CLOB,corrected_answer CLOB)");
        db.execute("CREATE TABLE ai_agent(agent_id VARCHAR(64) PRIMARY KEY,agent_name VARCHAR(80),workspace_config CLOB,source_agent_id VARCHAR(64),"
                + "channel VARCHAR(32),owner_user_id VARCHAR(128),workspace_version INT DEFAULT 2)");
        db.execute("CREATE TABLE ai_agent_draft(agent_id VARCHAR(64),owner_user_id VARCHAR(128),base_version INT,revision INT,config_json CLOB)");
        db.execute("CREATE TABLE ai_model_usage_attempt(run_id VARCHAR(128),actual_tokens BIGINT)");
        db.execute("CREATE TABLE ai_eval_dataset(dataset_id VARCHAR(64),owner_user_id VARCHAR(64),name VARCHAR(255))");
        db.execute("CREATE TABLE ai_eval_run(eval_run_id VARCHAR(64),dataset_id VARCHAR(64),selected_agent_id VARCHAR(64))");
        db.execute("CREATE TABLE ai_eval_case(case_id VARCHAR(64),question CLOB)");
        db.execute("CREATE TABLE ai_eval_case_result(result_id VARCHAR(64),eval_run_id VARCHAR(64),case_id VARCHAR(64),agent_run_id VARCHAR(64),"
                + "agent_id VARCHAR(64),status VARCHAR(32),error_message CLOB,overall_score DECIMAL(7,4),warnings_json CLOB,final_answer CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        config = (ObjectNode) json.readTree("""
                {"name":"天气助手","strategy":"fixed","nodes":[{"nodeId":"step_1","role":"DEFAULT","name":"回答",
                 "systemPrompt":"你是天气助手。","outputRequirement":"简洁回答。","taskPrompt":"","mcpBindings":[],"advisors":[],
                 "usePublicTools":true,"codeExecution":true,"modelId":"m1"}]}""");
        db.update("INSERT INTO ai_agent(agent_id,agent_name,workspace_config,owner_user_id,channel) VALUES('wa_1','天气助手',?,'alice','agent')", config.toString());
        db.update("INSERT INTO ai_run_history(run_id,user_id,agent_id,agent_version,strategy,status,question,answer) VALUES(?,?,?,?,?,?,?,?)",
                run, "alice", "wa_1", 2, "fixed", "COMPLETED", "明天北京天气怎么样？", "明天北京晴，25 度。");
        db.update("INSERT INTO ai_run_feedback(owner_user_id,run_id,rating,comment_text) VALUES('alice',?,'NOT_HELPFUL','没有查询天气就回答了')", run);
        db.update("INSERT INTO ai_eval_dataset VALUES('old','alice','我的天气题')");
        db.update("INSERT INTO ai_eval_run VALUES('old-run','old','wa_1')");
        db.update("INSERT INTO ai_eval_case VALUES('c1','上海后天下雨吗？')");
        db.update("INSERT INTO ai_eval_case_result(result_id,eval_run_id,case_id,agent_run_id) VALUES('r-old','old-run','c1','x')");

        traces = new RunTraceStore(db, 1000);
        TraceRecorder recorder = traces.recorder();
        recorder.model(new RunTrace.ModelExchange(run, "alice", "fixed_strategy#attempt-1", "api", null, 0, 900,
                Map.of("model", "mimo", "messages", List.of(Map.of("role", "user", "content", "明天北京天气怎么样？"))), 200,
                new RunTrace.ModelReply("mimo", "明天北京晴，25 度。", null, List.of(), "stop", 100L, 20L), RunTrace.OUTCOME_OK, null));
        // Long-term memory extraction after the answer: a helper request, not part of answering.
        recorder.model(new RunTrace.ModelExchange(run, "alice", "CHAT_HELPER", "api", "CHAT_HELPER", 0, 300,
                Map.of("model", "mimo", "messages", List.of(Map.of("role", "user", "content", "抽取用户长期记忆"))), 200,
                new RunTrace.ModelReply("mimo", "NONE", null, List.of(), "stop", 50L, 2L), RunTrace.OUTCOME_OK, null));
        traces.drainNow();

        reviewer = mock(RunReviewService.class);
        workspace = mock(WorkspaceService.class);
        versions = mock(WorkspaceVersionService.class);
        datasets = mock(EvalDatasetService.class);
        evalRuns = mock(EvalRunService.class);
        when(versions.version("alice", "wa_1", 2)).thenReturn(Map.of("version", 2, "config", config));
        service = new ImprovementService(db, json, traces, reviewer, workspace, versions, datasets, evalRuns);
    }

    private RunReviewService.Review review(String body) throws Exception {
        return new RunReviewService.Review(json.readTree(body), "mimo-v2.6-pro", 1234);
    }

    @After
    public void cleanup() {
        if (service != null) service.stop();
        if (db != null) db.execute("SHUTDOWN");
    }

    private String seedSuggestion(String status) {
        String id = "imp_" + UUID.randomUUID();
        ObjectNode suggestion = json.createObjectNode();
        suggestion.putArray("promptChanges").addObject().put("nodeId", "step_1").put("field", "systemPrompt")
                .put("before", config.path("nodes").get(0).path("systemPrompt").asText()).put("after", "查询实时天气后再回答");
        ObjectNode validation = json.createObjectNode().put("questions", 2).put("baselineRunId", "e-base").put("candidateRunId", "e-draft");
        db.update("INSERT INTO ai_improvement_suggestion(suggestion_id,owner_user_id,agent_id,agent_version,source_kind,source_run_id,status,"
                        + "suggestion_json,draft_base_version,draft_revision,candidate_agent_id,validation_json) VALUES(?,'alice','wa_1',2,'RUN',?,?,?,2,1,'wa_candidate',?)",
                id, run, status, suggestion.toString(), validation.toString());
        return id;
    }

    private Map<String,Object> completeRun(String status) {
        return new LinkedHashMap<>(Map.of("status", status, "totalCases", 2, "completedCases", 2,
                "results", List.of(Map.of("stableKey", "improvement-1", "status", "PASS"),
                        Map.of("stableKey", "improvement-2", "status", "FAIL"))));
    }

    private void prepareValidation(ObjectNode latest, int version) {
        when(versions.draft("alice", "wa_1")).thenReturn(Map.of("config", latest, "baseVersion", version, "revision", 0));
        when(versions.saveDraft(eq("alice"), eq("wa_1"), any())).thenReturn(Map.of("revision", 1));
        when(workspace.createCandidate(anyString(), anyString(), any())).thenReturn("wa_candidate");
        when(datasets.createDataset(anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("datasetId", "d1"));
        when(datasets.publish(anyString(), anyString())).thenReturn(Map.of("versionId", "v1"));
        when(evalRuns.createRun(any())).thenReturn(Map.of("evalRunId", "e-base"), Map.of("evalRunId", "e-draft"));
        when(evalRuns.getRun(anyString())).thenReturn(Map.of("status", "RUNNING"));
    }

    @Test
    public void aComparisonDoesNotRequestFourForegroundSlotsForTheSameOwner() {
        String id = seedSuggestion("PENDING_VALIDATION");
        prepareValidation(config.deepCopy(), 2);

        assertEquals("VALIDATING", service.validate("alice", id).get("status"));

        ArgumentCaptor<EvalRunService.RunCommand> commands = ArgumentCaptor.forClass(EvalRunService.RunCommand.class);
        verify(evalRuns, times(2)).createRun(commands.capture());
        List<EvalRunService.RunCommand> pair = commands.getAllValues();
        assertEquals("wa_1", pair.get(0).getSelectedAgentId());
        assertEquals("wa_candidate", pair.get(1).getSelectedAgentId());
        for (EvalRunService.RunCommand command : pair) {
            assertEquals("alice", command.getUserId());
            assertEquals("FIXED_USER", command.getMemoryPolicy());
            assertEquals(Integer.valueOf(1), command.getConcurrency());
        }
        assertTrue(pair.stream().mapToInt(EvalRunService.RunCommand::getConcurrency).sum() <= 3);
    }

    @Test
    public void changedPromptsAndDeletedNodesCannotBeOverwrittenByOldSuggestions() {
        String id = seedSuggestion("PENDING_VALIDATION");
        ObjectNode latest = config.deepCopy();
        ((ObjectNode) latest.path("nodes").get(0)).put("systemPrompt", "用户后来新增的安全要求");
        for (ObjectNode current : List.of(latest, json.createObjectNode().set("nodes", json.createArrayNode()))) {
            when(versions.draft("alice", "wa_1")).thenReturn(Map.of("config", current, "baseVersion", 3, "revision", 0));
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.validate("alice", id));
            assertEquals(409, error.getStatusCode().value());
        }
        verify(versions, never()).saveDraft(anyString(), anyString(), any());
        verifyNoInteractions(datasets, evalRuns);
    }

    @Test
    public void aNewVersionWithUnchangedTargetPromptsCanStillBeValidated() {
        String id = seedSuggestion("PENDING_VALIDATION");
        ObjectNode latest = config.deepCopy();
        ((ObjectNode) latest.path("nodes").get(0)).put("outputRequirement", "用户修改了另一个字段，需保留");
        prepareValidation(latest, 3);
        assertEquals("VALIDATING", service.validate("alice", id).get("status"));
        ArgumentCaptor<JsonNode> saved = ArgumentCaptor.forClass(JsonNode.class);
        verify(versions).saveDraft(eq("alice"), eq("wa_1"), saved.capture());
        assertEquals(3, saved.getValue().path("version").asInt());
        assertEquals("查询实时天气后再回答", saved.getValue().path("nodes").get(0).path("systemPrompt").asText());
        assertEquals("用户修改了另一个字段，需保留", saved.getValue().path("nodes").get(0).path("outputRequirement").asText());
    }

    @Test
    public void interruptedOrIncompleteEvaluationsFailValidationAndNeverPublish() {
        List<Map<String,Object>> invalid = new ArrayList<>();
        invalid.add(completeRun("CANCELLED"));
        invalid.add(completeRun("FAILED"));
        var missing = completeRun("RULE_COMPLETED"); missing.put("results", List.of()); invalid.add(missing);
        var unfinished = completeRun("RULE_COMPLETED"); unfinished.put("completedCases", 1); invalid.add(unfinished);
        var duplicated = completeRun("RULE_COMPLETED"); duplicated.put("results", List.of(
                Map.of("stableKey", "improvement-1", "status", "PASS"), Map.of("stableKey", "improvement-1", "status", "PASS"))); invalid.add(duplicated);
        var wrongKeys = completeRun("RULE_COMPLETED"); wrongKeys.put("results", List.of(
                Map.of("stableKey", "improvement-1", "status", "PASS"), Map.of("stableKey", "unrelated", "status", "PASS"))); invalid.add(wrongKeys);
        var errorCase = completeRun("RULE_COMPLETED"); errorCase.put("results", List.of(
                Map.of("stableKey", "improvement-1", "status", "PASS"), Map.of("stableKey", "improvement-2", "status", "ERROR"))); invalid.add(errorCase);
        for (Map<String,Object> failed : invalid) {
            for (boolean baselineFailed : List.of(true, false)) {
                String id = seedSuggestion("VALIDATING");
                db.update("INSERT INTO ai_agent_draft VALUES('wa_1','alice',2,1,'{}')");
                when(evalRuns.getRun("e-base")).thenReturn(baselineFailed ? failed : completeRun("RULE_COMPLETED"));
                when(evalRuns.getRun("e-draft")).thenReturn(baselineFailed ? completeRun("RULE_COMPLETED") : failed);
                assertEquals("VALIDATION_FAILED", service.view("alice", id).get("status"));
                assertThrows(ResponseStatusException.class, () -> service.adopt("alice", id));
                assertEquals(Integer.valueOf(0), db.queryForObject("SELECT COUNT(*) FROM ai_agent_draft", Integer.class));
            }
        }
        verify(versions, never()).publish(anyString(), anyString(), any());
    }

    @Test
    public void adoptionRechecksPreviouslyValidatedSuggestionsAndPreservesAnEditedDraft() {
        String id = seedSuggestion("VALIDATED");
        db.update("INSERT INTO ai_agent_draft VALUES('wa_1','alice',2,2,'{}')");
        when(evalRuns.getRun(anyString())).thenReturn(completeRun("CANCELLED"));
        assertThrows(ResponseStatusException.class, () -> service.adopt("alice", id));
        assertEquals("VALIDATION_FAILED", service.view("alice", id).get("status"));
        assertEquals(Integer.valueOf(1), db.queryForObject("SELECT COUNT(*) FROM ai_agent_draft WHERE revision=2", Integer.class));
        verify(versions, never()).publish(anyString(), anyString(), any());
    }

    private Map<String, Object> awaitAnalysis(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> view = service.view("alice", id);
            if (!"ANALYZING".equals(view.get("status"))) return view;
            Thread.sleep(50);
        }
        fail("analysis did not finish");
        return null;
    }

    @Test
    public void reviewKeepsOnlyApplicableChangesValidatesTheDraftAndPublishesOnlyOnAdoption() throws Exception {
        String longText = "x".repeat(5000);
        when(reviewer.review(eq("alice"), anyString(), any())).thenReturn(review("""
                {"external":false,"category":"PROMPT_UNCLEAR","summary":"回答节点没有要求先查询实时天气",
                 "problemNode":"step_1","evidence":[{"seq":1,"observation":"没有调用工具就回答"},{"seq":99,"observation":"不存在的记录"},{"seq":0,"observation":"节点配置里禁止查询工具"}],
                 "promptChanges":[
                   {"nodeId":"step_1","field":"systemPrompt","after":"你是天气助手。回答天气问题前必须先调用天气工具查询。","reason":"补上先查询的要求"},
                   {"nodeId":"step_9","field":"systemPrompt","after":"不存在的节点","reason":"x"},
                   {"nodeId":"step_1","field":"taskPrompt","after":"Fixed 节点没有任务指令","reason":"x"},
                   {"nodeId":"step_1","field":"outputRequirement","after":"%s","reason":"太长"}],
                 "otherAdvice":[{"type":"TOOL_BINDING","advice":"给回答节点绑定天气查询工具"}]}""".formatted(longText)));
        Map<String, Object> created = service.request("alice", json.createObjectNode().put("runId", run));
        Map<String, Object> view = awaitAnalysis((String) created.get("suggestionId"));
        assertEquals("PENDING_VALIDATION", view.get("status"));
        assertEquals(2, view.get("currentVersion"));
        JsonNode suggestion = (JsonNode) view.get("suggestion");
        assertEquals("only the change to a known node's own field survives", 1, suggestion.path("promptChanges").size());
        assertEquals("你是天气助手。", suggestion.path("promptChanges").get(0).path("before").asText());
        JsonNode kept = ((JsonNode) view.get("diagnosis")).path("evidence");
        assertEquals("unknown records are dropped; seq 0 cites the node configuration", 2, kept.size());
        assertEquals(0, kept.get(1).path("seq").asInt());
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(reviewer).review(eq("alice"), evidence.capture(), any());
        assertTrue(evidence.getValue().contains("没有查询天气就回答了"));
        assertTrue(evidence.getValue().contains("#1 模型请求"));
        assertTrue("helper requests say they are not part of answering",
                evidence.getValue().contains("长期记忆抽取、知识检索判断等），不参与生成回答"));
        assertFalse(evidence.getValue().contains("用途CHAT_HELPER"));
        assertEquals("a second request for the same run returns the open suggestion", created.get("suggestionId"),
                service.request("alice", json.createObjectNode().put("runId", run)).get("suggestionId"));

        when(versions.draft("alice", "wa_1")).thenReturn(Map.of("config", config, "baseVersion", 2, "revision", 0));
        when(versions.saveDraft(eq("alice"), eq("wa_1"), any())).thenReturn(Map.of("revision", 1));
        when(workspace.createCandidate(eq("alice"), eq("天气助手"), any())).thenReturn("wa_candidate");
        when(datasets.createDataset(anyString(), anyString(), eq("INDEPENDENT"), eq("alice"))).thenReturn(Map.of("datasetId", "d1"));
        when(datasets.publish(eq("d1"), anyString())).thenReturn(Map.of("versionId", "v1"));
        when(evalRuns.createRun(any())).thenReturn(Map.of("evalRunId", "e-base"), Map.of("evalRunId", "e-draft"));
        when(evalRuns.getRun(anyString())).thenReturn(Map.of("status", "RUNNING", "results", List.of()));
        String id = (String) created.get("suggestionId");
        assertEquals("VALIDATING", service.validate("alice", id).get("status"));
        ArgumentCaptor<JsonNode> draft = ArgumentCaptor.forClass(JsonNode.class);
        verify(versions).saveDraft(eq("alice"), eq("wa_1"), draft.capture());
        assertEquals("你是天气助手。回答天气问题前必须先调用天气工具查询。", draft.getValue().path("nodes").get(0).path("systemPrompt").asText());
        ArgumentCaptor<EvalDatasetService.CaseCommand> cases = ArgumentCaptor.forClass(EvalDatasetService.CaseCommand.class);
        verify(datasets, times(2)).saveCase(eq("d1"), isNull(), cases.capture());
        assertEquals(List.of("明天北京天气怎么样？", "上海后天下雨吗？"), cases.getAllValues().stream().map(EvalDatasetService.CaseCommand::getQuestion).toList());
        ArgumentCaptor<EvalRunService.RunCommand> runs = ArgumentCaptor.forClass(EvalRunService.RunCommand.class);
        verify(evalRuns, times(2)).createRun(runs.capture());
        assertEquals(List.of("wa_1", "wa_candidate"), runs.getAllValues().stream().map(EvalRunService.RunCommand::getSelectedAgentId).toList());

        when(evalRuns.getRun("e-base")).thenReturn(Map.of("status", "RULE_COMPLETED", "totalCases", 2, "completedCases", 2, "results", List.of(
                Map.of("stableKey", "improvement-1", "question", "明天北京天气怎么样？", "status", "PASS", "scores", Map.of("overall", 0.6), "latencyMs", 3000, "agentRunId", "b1"),
                Map.of("stableKey", "improvement-2", "question", "上海后天下雨吗？", "status", "FAIL", "scores", Map.of("overall", 0.6)))));
        when(evalRuns.getRun("e-draft")).thenReturn(Map.of("status", "RULE_COMPLETED", "totalCases", 2, "completedCases", 2, "results", List.of(
                Map.of("stableKey", "improvement-1", "question", "明天北京天气怎么样？", "status", "PASS", "scores", Map.of("overall", 0.85), "latencyMs", 5000, "agentRunId", "d1", "resultId", "res-d1"),
                Map.of("stableKey", "improvement-2", "question", "上海后天下雨吗？", "status", "PASS", "scores", Map.of("overall", 0.85)))));
        db.update("INSERT INTO ai_eval_case_result(result_id,eval_run_id,final_answer) VALUES('res-d1','e-draft','根据查询结果，明天北京多云。')");
        db.update("INSERT INTO ai_model_usage_attempt VALUES('b1',1000),('d1',1500)");
        Map<String, Object> validated = service.view("alice", id);
        assertEquals("VALIDATED", validated.get("status"));
        JsonNode comparison = ((JsonNode) validated.get("validation")).path("comparison");
        assertEquals(60.0, comparison.path("baseline").path("averageScore").asDouble(), 0.01);
        assertEquals(85.0, comparison.path("candidate").path("averageScore").asDouble(), 0.01);
        assertEquals(1500, comparison.path("candidate").path("tokens").asLong());
        assertEquals("根据查询结果，明天北京多云。", comparison.path("cases").get(0).path("candidate").path("answer").asText());
        verify(workspace).archiveCandidate("alice", "wa_candidate");
        verify(versions, never()).publish(anyString(), anyString(), any());

        assertEquals("ADOPTED", service.adopt("alice", id).get("status"));
        ArgumentCaptor<JsonNode> published = ArgumentCaptor.forClass(JsonNode.class);
        verify(versions).publish(eq("alice"), eq("wa_1"), published.capture());
        assertEquals(2, published.getValue().path("baseVersion").asInt());
        assertEquals(1, published.getValue().path("draftRevision").asInt());
    }

    @Test
    public void anExternalFailureIsReportedWithoutAskingTheModelForChanges() throws Exception {
        db.update("UPDATE ai_run_history SET status='FAILED',answer=NULL,last_error='所有模型都不可用：mimo-v2.6-pro 返回 503' WHERE run_id=?", run);
        db.update("DELETE FROM ai_run_trace");
        traces.recorder().model(new RunTrace.ModelExchange(run, "alice", null, "api", null, 0, 1,
                Map.of("model", "mimo", "messages", List.of(Map.of("role", "user", "content", "hi"))), 503, null, RunTrace.OUTCOME_ERROR, "Service Unavailable"));
        traces.drainNow();
        Map<String, Object> view = awaitAnalysis((String) service.request("alice", json.createObjectNode().put("runId", run)).get("suggestionId"));
        assertEquals("EXTERNAL", view.get("status"));
        assertTrue(((JsonNode) view.get("diagnosis")).path("external").asBoolean());
        verifyNoInteractions(reviewer);
    }

    @Test
    public void rejectingDuringValidationStopsItAndRemovesTheUnchangedDraft() throws Exception {
        when(reviewer.review(eq("alice"), anyString(), any())).thenReturn(review("""
                {"category":"PROMPT_UNCLEAR","summary":"s","problemNode":"step_1","evidence":[],
                 "promptChanges":[{"nodeId":"step_1","field":"systemPrompt","after":"新的提示词","reason":"r"}],"otherAdvice":[]}"""));
        String id = (String) awaitAnalysis((String) service.request("alice", json.createObjectNode().put("runId", run)).get("suggestionId")).get("suggestionId");
        when(versions.draft("alice", "wa_1")).thenReturn(Map.of("config", config, "baseVersion", 2, "revision", 0));
        when(versions.saveDraft(eq("alice"), eq("wa_1"), any())).thenAnswer(inv -> {
            db.update("INSERT INTO ai_agent_draft VALUES('wa_1','alice',2,1,'{}')");
            return Map.of("revision", 1);
        });
        when(workspace.createCandidate(anyString(), anyString(), any())).thenReturn("wa_candidate");
        when(datasets.createDataset(anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("datasetId", "d1"));
        when(datasets.publish(anyString(), anyString())).thenReturn(Map.of("versionId", "v1"));
        when(evalRuns.createRun(any())).thenReturn(Map.of("evalRunId", "e-base"), Map.of("evalRunId", "e-draft"));
        when(evalRuns.getRun(anyString())).thenReturn(Map.of("status", "RUNNING", "results", List.of()));
        service.validate("alice", id);
        assertEquals("REJECTED", service.reject("alice", id).get("status"));
        verify(evalRuns).cancelRun("e-base");
        verify(evalRuns).cancelRun("e-draft");
        verify(workspace).archiveCandidate("alice", "wa_candidate");
        assertEquals(Integer.valueOf(0), db.queryForObject("SELECT COUNT(*) FROM ai_agent_draft", Integer.class));
        verify(versions, never()).publish(anyString(), anyString(), any());
    }

    @Test
    public void candidatesRunOnlyWhenAnEvaluationNamesThem() {
        JdbcTemplate h2 = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        h2.execute("CREATE TABLE ai_agent(agent_id VARCHAR(64),owner_user_id VARCHAR(64),status INT,archived INT,source_agent_id VARCHAR(64),is_public INT,channel VARCHAR(32))");
        h2.execute("CREATE TABLE ai_agent_flow_config(agent_id VARCHAR(64),client_id VARCHAR(64),status INT)");
        h2.execute("CREATE TABLE ai_client_config(source_type VARCHAR(16),source_id VARCHAR(64),target_type VARCHAR(16),target_id VARCHAR(64),status INT)");
        h2.execute("CREATE TABLE ai_client_model(model_id VARCHAR(64),status INT,api_id VARCHAR(64),owner_user_id VARCHAR(64),source_model_id VARCHAR(64),platform_enabled INT)");
        h2.execute("CREATE TABLE ai_client_api(api_id VARCHAR(64),status INT,owner_user_id VARCHAR(64),secret_ref VARCHAR(64))");
        h2.update("INSERT INTO ai_agent VALUES('wa_visible','alice',1,0,NULL,0,'agent'),('wa_candidate','alice',1,0,NULL,0,'candidate')");
        var access = new cn.bugstack.ai.infrastructure.adapter.repository.WorkspaceAccessRepository(h2);
        assertTrue(access.ownedAgentIds("alice").contains("wa_visible"));
        assertFalse("never listed or routed to", access.ownedAgentIds("alice").contains("wa_candidate"));
        assertTrue("an evaluation naming it may run it", access.ownsAgent("alice", "wa_candidate"));
        assertFalse(access.ownsAgent("bob", "wa_candidate"));
        h2.update("UPDATE ai_agent SET archived=1 WHERE agent_id='wa_candidate'");
        assertFalse("archived after validation", access.ownsAgent("alice", "wa_candidate"));
    }

    @Test
    public void aFailedEvaluationCaseIsReviewedWithItsResult() throws Exception {
        // The result row lacks the agent; the run history knows which agent and version ran.
        db.update("INSERT INTO ai_eval_case_result(result_id,eval_run_id,case_id,agent_run_id,status,error_message) VALUES('res-x','old-run','c1',?,'ERROR','回答缺少实时数据')", run);
        when(reviewer.review(eq("alice"), anyString(), any())).thenReturn(review("{\"category\":\"NONE\",\"summary\":\"没有问题\"}"));
        Map<String, Object> created = service.request("alice", json.createObjectNode().put("evalRunId", "old-run").put("resultId", "res-x"));
        assertEquals("EVAL_CASE", created.get("sourceKind"));
        assertEquals("wa_1", created.get("agentId"));
        assertEquals(2, created.get("agentVersion"));
        awaitAnalysis((String) created.get("suggestionId"));
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(reviewer).review(eq("alice"), evidence.capture(), any());
        assertTrue(evidence.getValue().contains("评测结果：ERROR"));
        try {
            service.request("bob", json.createObjectNode().put("evalRunId", "old-run").put("resultId", "res-x"));
            fail("only the dataset owner may review its cases");
        } catch (ResponseStatusException expected) {
            assertEquals(404, expected.getStatusCode().value());
        }
    }

    @Test
    public void suggestionsBelongToTheirOwner() throws Exception {
        try {
            service.request("bob", json.createObjectNode().put("runId", run));
            fail("bob cannot review alice's run");
        } catch (ResponseStatusException expected) {
            assertEquals(404, expected.getStatusCode().value());
        }
        when(reviewer.review(anyString(), anyString(), any())).thenReturn(review("{\"category\":\"NONE\",\"summary\":\"没有问题\"}"));
        String id = (String) service.request("alice", json.createObjectNode().put("runId", run)).get("suggestionId");
        assertEquals("NO_CHANGE", awaitAnalysis(id).get("status"));
        try {
            service.view("bob", id);
            fail("bob cannot read alice's suggestion");
        } catch (ResponseStatusException expected) {
            assertEquals(404, expected.getStatusCode().value());
        }
    }
}
