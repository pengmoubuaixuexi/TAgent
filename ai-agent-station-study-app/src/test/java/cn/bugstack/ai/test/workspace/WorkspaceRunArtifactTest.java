package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.domain.agent.service.execute.common.RunArtifactService;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.trigger.eval.EvalDatasetService;
import cn.bugstack.ai.trigger.workspace.WorkspaceOutcomeService;
import cn.bugstack.ai.trigger.workspace.WorkspaceRunArtifactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Local H2 transactions exercise the production V069 schema and artifact ownership contract. */
public class WorkspaceRunArtifactTest {
    final ObjectMapper json=new ObjectMapper();
    final String run="run-"+UUID.randomUUID();
    JdbcTemplate db;TransactionTemplate tx;WorkspaceOutcomeService outcomes;WorkspaceRunArtifactService tool;
    IRunHistoryRepository history;RunEventPublisher events;
    Map<String,Object> scope;
    @Before public void setup()throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        db.execute("CREATE TABLE admin_user(user_id VARCHAR PRIMARY KEY,status INT)");db.update("INSERT INTO admin_user VALUES('alice',1),('bob',1)");
        Path migration=Path.of("../docs/dev-ops/sql-migrations/V069__artifacts_feedback.sql");
        if(!Files.exists(migration))migration=Path.of("docs/dev-ops/sql-migrations/V069__artifacts_feedback.sql");
        for(String sql:Files.readString(migration).replace("JSON NOT NULL","CLOB NOT NULL").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").split(";"))if(!sql.isBlank())db.execute(sql);
        history=mock(IRunHistoryRepository.class);events=mock(RunEventPublisher.class);
        when(history.findOwned(anyString(),anyString())).thenReturn(Optional.empty());
        when(history.findOwned("alice",run)).thenReturn(Optional.of(Map.of("runId",run,"userId","alice","agentId","travel-agent","agentVersion",7,"status","RUNNING")));
        outcomes=new WorkspaceOutcomeService(db,history,mock(EvalDatasetService.class),json);
        tool=new WorkspaceRunArtifactService(outcomes,events,json);
        RunBudgetRegistry.start(run,"alice",new RunBudgetRegistry.Limits(Duration.ofMinutes(5),100,100,100000));
        scope=Map.of("userId","alice","agent.run_id",run,"sessionId","session-a");
    }
    @After public void finish(){RunBudgetRegistry.finish(run);}
    ObjectNode body(){return json.createObjectNode().put("title","北京三日行程").put("format","MARKDOWN")
            .set("content",json.createObjectNode().put("markdown","# 行程\n依据已查证信息安排。"));}
    JsonNode call(String id,JsonNode body)throws Exception{String response=tx.execute(status->tool.create(id,body.toString(),scope));return json.readTree(response);}
    int count(){return db.queryForObject("SELECT COUNT(*) FROM ai_run_artifact",Integer.class);}

    @Test public void activeRunCreatesDurableOutputWithServerProvenanceAndAuthenticatedUrls()throws Exception {
        JsonNode result=call("call-a",body());assertTrue(result.path("ok").asBoolean());
        assertEquals(7,result.path("agentVersion").asInt());assertEquals("travel-agent",result.path("agentId").asText());
        assertEquals(run,result.path("runId").asText());assertEquals(1,result.path("revision").asInt());
        assertFalse(result.has("content"));assertTrue(result.path("previewUrl").asText().startsWith("/outcomes.html?runId="));
        assertTrue(result.path("downloadUrl").asText().startsWith("/api/v1/workspace/outcomes/artifacts/"));
        String id=result.path("artifactId").asText();
        assertEquals("# 行程\n依据已查证信息安排。",((JsonNode)outcomes.artifact("alice",id,1).get("content")).path("markdown").asText());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->outcomes.artifact("bob",id,1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->outcomes.export("bob",id,1));
        verify(events).publish(eq(run),eq("session-a"),eq("artifact_created"),argThat((Object value)->value instanceof Map<?,?> m&&!m.containsKey("content")&&id.equals(m.get("artifactId"))));
    }

    @Test public void retryNewCallAndManualRevisionDoNotDuplicateOrOverwriteOriginal()throws Exception {
        JsonNode first=call("call-a",body());String id=first.path("artifactId").asText();
        JsonNode same=call("call-a",body()),newCall=call("call-b",body());
        assertEquals(id,same.path("artifactId").asText());assertTrue(same.path("replayed").asBoolean());
        assertEquals(id,newCall.path("artifactId").asText());assertEquals(1,count());
        ObjectNode edit=body().put("revision",1);edit.set("content",json.createObjectNode().put("markdown","用户手工完善"));
        tx.execute(status->outcomes.save("alice",id,edit));
        assertEquals(1,call("call-a",body()).path("revision").asInt());
        assertEquals("用户手工完善",((JsonNode)outcomes.artifact("alice",id,0).get("content")).path("markdown").asText());
        ObjectNode changed=body();changed.set("content",json.createObjectNode().put("markdown","different"));
        assertEquals("ARTIFACT_CONFLICT",call("call-a",changed).path("error").asText());
        verify(events,times(1)).publish(anyString(),anyString(),eq("artifact_created"),any());
    }

    @Test public void forgedProvenanceInvalidBodiesAndMissingRuntimeCannotWrite()throws Exception {
        for(ObjectNode invalid:List.of(body().put("userId","bob"),body().put("runId","foreign"),body().put("agentVersion",999),
                body().put("format","HTML"),body().put("title","")))assertFalse(call("invalid",invalid).path("ok").asBoolean());
        assertFalse(json.readTree(tool.create("call-a",body().toString(),Map.of())).path("ok").asBoolean());
        assertFalse(json.readTree(tool.create("call-a",body().toString(),Map.of("userId","bob","agent.run_id",run))).path("ok").asBoolean());
        assertFalse(json.readTree(tool.create("call-a","{} {}",scope)).path("ok").asBoolean());
        assertFalse(json.readTree(tool.create("call-a","{\"title\":\"a\",\"title\":\"b\"}",scope)).path("ok").asBoolean());
        assertEquals(0,count());verifyNoInteractions(events);
    }

    @Test public void debugEvaluationAndMissingOwnedHistoryStayReadOnly()throws Exception {
        var debug=new HashMap<>(scope);debug.put("agent.debug_read_only",true);
        var eval=new HashMap<>(scope);eval.put("sessionId","eval-case-1");
        for(Map<String,Object> blocked:List.of(debug,eval))assertFalse(json.readTree(tool.create("call-a",body().toString(),blocked)).path("ok").asBoolean());
        when(history.findOwned("alice",run)).thenReturn(Optional.empty());assertFalse(call("call-a",body()).path("ok").asBoolean());
        assertEquals(0,count());verifyNoInteractions(events);
    }

    @Test public void tableContractsAndExportRemainSafe()throws Exception {
        ObjectNode valid=json.createObjectNode().put("title","预算对比").put("format","TABLE");
        valid.set("content",json.readTree("{\"rows\":[[\"=cmd\",\"60\"]],\"columns\":[\"项目\",\"金额\"]}"));
        var result=call("table",valid);assertTrue(result.path("ok").asBoolean());
        String csv=new String(outcomes.export("alice",result.path("artifactId").asText(),1).bytes(),StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=cmd\""));
        ObjectNode reordered=valid.deepCopy();reordered.set("content",json.readTree("{\"columns\":[\"项目\",\"金额\"],\"rows\":[[\"=cmd\",\"60\"]]}"));
        assertEquals(result.path("artifactId"),call("table",reordered).path("artifactId"));
        valid.set("content",json.readTree("{\"columns\":[\"a\",\"b\"],\"rows\":[[\"one\"]]}"));
        assertFalse(call("malformed-table",valid).path("ok").asBoolean());
        ObjectNode huge=body();huge.set("content",json.createObjectNode().put("markdown","x".repeat(200001)));
        assertFalse(call("huge",huge).path("ok").asBoolean());assertEquals(1,count());
    }
    @Test public void codeToolPreservesSourceReturnsSafeMetadataAndDeduplicatesReorderedArguments()throws Exception {
        String source="\n\tprint(\"北京\")  \r\n";
        ObjectNode code=json.createObjectNode().put("title","行程程序").put("format","CODE")
                .set("content",json.createObjectNode().put("code",source).put("language","python").put("filename","行程.py"));
        JsonNode result=call("code",code);assertTrue(result.path("ok").asBoolean());
        assertEquals("CODE",result.path("format").asText());assertEquals("行程.py",result.path("filename").asText());assertEquals("python",result.path("language").asText());
        assertFalse(result.has("code"));assertFalse(result.has("content"));
        ObjectNode reordered=code.deepCopy();reordered.set("content",json.createObjectNode().put("filename","行程.py").put("language","python").put("code",source));
        assertEquals(result.path("artifactId"),call("code",reordered).path("artifactId"));
        assertEquals(result.path("artifactId"),call("code-retry",reordered).path("artifactId"));assertEquals(1,count());
        assertArrayEquals(source.getBytes(StandardCharsets.UTF_8),outcomes.export("alice",result.path("artifactId").asText(),1).bytes());
        verify(events,times(1)).publish(eq(run),eq("session-a"),eq("artifact_created"),argThat((Object value)->value instanceof Map<?,?> m&&"行程.py".equals(m.get("filename"))&&!m.containsKey("code")));
    }
    @Test public void invalidArtifactErrorsExplainExpectedFieldWithoutEchoingRawContent()throws Exception {
        ObjectNode raw=body();raw.put("content","raw Markdown");
        JsonNode response=call("raw",raw);assertFalse(response.path("ok").asBoolean());assertTrue(response.path("message").asText().contains("content 必须为 JSON 对象"));
        ObjectNode missing=body();missing.set("content",json.createObjectNode());
        assertTrue(call("missing",missing).path("message").asText().contains("content.markdown"));
        JsonNode malformed=json.readTree(tool.create("broken","{\"title\":\"private-secret\",\"format\":\"MARKDOWN\",\"content\": # secret body}",scope));
        assertEquals("INVALID_ARGUMENTS",malformed.path("error").asText());
        assertTrue(malformed.path("message").asText().contains("行"));assertTrue(malformed.path("message").asText().contains("\\n"));
        assertFalse(malformed.toString().contains("private-secret"));assertFalse(malformed.toString().contains("secret body"));
        assertEquals(0,count());verifyNoInteractions(events);
    }

    @Test public void rollbackDoesNotPublishAndCommitPublishesOnlyPersistedMetadata()throws Exception {
        tx.execute(status->{String response=tool.create("call-a",body().toString(),scope);assertTrue(response.contains("\"ok\":true"));
            verifyNoInteractions(events);status.setRollbackOnly();return null;});
        assertEquals(0,count());verifyNoInteractions(events);
        assertTrue(call("call-a",body()).path("ok").asBoolean());assertEquals(1,count());
        verify(events).publish(eq(run),eq("session-a"),eq("artifact_created"),any());
    }

    @Test public void concurrentDuplicateToolCallsCreateOneArtifact()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->call("same-call",body()));var second=pool.submit(()->call("same-call",body()));
            JsonNode a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            assertTrue(a.path("ok").asBoolean());assertTrue(b.path("ok").asBoolean());
            assertEquals(a.path("artifactId"),b.path("artifactId"));assertEquals(1,count());
            verify(events,times(1)).publish(eq(run),eq("session-a"),eq("artifact_created"),any());
        }finally{pool.shutdownNow();}
    }

    @Test public void persistenceFailureIsReadableAndNeverExposesSql()throws Exception {
        var failed=mock(WorkspaceOutcomeService.class);
        when(failed.createFromTool(anyString(),anyString(),anyString(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL password=private"));
        var service=new WorkspaceRunArtifactService(failed,events,json);
        String result=service.create("call-a",body().toString(),scope);
        assertEquals("ARTIFACT_SAVE_FAILED",json.readTree(result).path("error").asText());assertFalse(result.contains("private"));
        verifyNoInteractions(events);assertEquals(RunArtifactService.TOOL_NAME,service.definition().name());
    }

    @Test public void cancellationAndFinishedRunNeverCreateOutputs() {
        RunBudgetRegistry.find(run).cancel();
        assertThrows(CancellationException.class,()->tool.create("cancelled",body().toString(),scope));
        assertEquals(0,count());verifyNoInteractions(events);
    }

    @Test public void providerSchemaUsesPortableObjectFieldsAndRejectsFinishedHistory()throws Exception {
        JsonNode schema=json.readTree(tool.definition().inputSchema());
        assertEquals("object",schema.at("/properties/content/type").asText());
        assertFalse(schema.at("/properties/content").has("oneOf"));
        assertFalse(schema.path("properties").has("runId"));assertFalse(schema.path("properties").has("userId"));
        assertTrue(schema.at("/properties/format/enum").toString().contains("CODE"));
        for(String field:List.of("filename","language","code"))assertEquals("string",schema.at("/properties/content/properties/"+field+"/type").asText());
        assertTrue(tool.definition().description().contains("\"content\":{\"markdown\":"));
        assertTrue(tool.definition().description().contains("\"filename\":\"hello.py\""));
        when(history.findOwned("alice",run)).thenReturn(Optional.of(Map.of("runId",run,"status","COMPLETED")));
        assertEquals("ARTIFACT_CONFLICT",call("late",body()).path("error").asText());assertEquals(0,count());
    }
}
