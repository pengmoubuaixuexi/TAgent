package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.trigger.eval.EvalDatasetService;
import cn.bugstack.ai.trigger.workspace.WorkspaceOutcomeService;
import cn.bugstack.ai.trigger.workspace.WorkspaceOutcomeController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class WorkspaceOutcomeTest {
    final ObjectMapper json=new ObjectMapper(); JdbcTemplate db;WorkspaceOutcomeService service;
    IRunHistoryRepository history;EvalDatasetService datasets;TransactionTemplate tx;
    @Before public void setup()throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        db.execute("CREATE TABLE admin_user(user_id VARCHAR PRIMARY KEY,status INT)");db.update("INSERT INTO admin_user VALUES('alice',1),('bob',1)");
        ArtifactTestSchema.apply(db);
        history=mock(IRunHistoryRepository.class);datasets=mock(EvalDatasetService.class);
        when(history.findOwned(anyString(),anyString())).thenReturn(Optional.empty());
        when(history.findOwned("alice","run-a")).thenReturn(Optional.of(Map.of("runId","run-a","userId","alice","agentId","agent-a","agentVersion",7,"question","真正的问题","answer","原答不能自动成为金标")));
        when(datasets.createDataset(anyString(),anyString(),eq("INDEPENDENT"),eq("alice"))).thenReturn(Map.of("datasetId","personal-draft"));
        when(datasets.saveCase(eq("personal-draft"),isNull(),any())).thenReturn(Map.of("caseId","case-a"));
        service=new WorkspaceOutcomeService(db,history,datasets,json);
    }
    <T>T transact(Supplier<T> body){return tx.execute(status->body.get());}
    ObjectNode markdown(String value){return json.createObjectNode().put("runId","run-a").put("title","我的产物").put("format","MARKDOWN").set("content",json.createObjectNode().put("markdown",value));}
    ObjectNode code(String filename,String source){return json.createObjectNode().put("runId","run-a").put("title","代码产物").put("format","CODE")
            .set("content",json.createObjectNode().put("filename",filename).put("language","python").put("code",source));}
    ObjectNode feedback(int revision,boolean promote){return json.createObjectNode().put("revision",revision).put("rating","NOT_HELPFUL").put("comment","需要更多证据").put("createEvalDraft",promote);}

    @Test public void immutableVersionsOwnerChecksAndServerProvenance() {
        Map<String,Object> first=transact(()->service.save("alice",null,markdown("original")));String id=first.get("artifactId").toString();
        assertEquals(7,first.get("agentVersion"));assertEquals("agent-a",first.get("agentId"));
        Map<String,Object> second=transact(()->service.save("alice",id,markdown("changed").put("revision",1)));
        assertEquals(2,second.get("revision"));assertEquals("original",((JsonNode)service.artifact("alice",id,1).get("content")).path("markdown").asText());
        assertEquals(2,service.revisions("alice",id).size());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",id,markdown("stale").put("revision",1)))).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->service.artifact("bob",id,0));assertThrows(ResponseStatusException.class,()->service.export("bob",id,1));
        assertThrows(ResponseStatusException.class,()->transact(()->service.save("bob",null,markdown("steal"))));
        assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,markdown("forge").put("agentVersion",999))));
        assertTrue(service.list("bob",null).isEmpty());assertEquals(1,service.list("alice","run-a").size());
        // The full list: every artifact of the owner, searchable by title, with the question of its run.
        db.update("INSERT INTO ai_run_history VALUES('run-a','帮我规划北京三日游')");
        List<Map<String,Object>> all=service.list("alice",null,"产物",0);
        assertEquals(1,all.size());assertEquals("帮我规划北京三日游",all.get(0).get("runQuestion"));assertEquals("run-a",all.get(0).get("runId"));
        assertTrue(service.list("alice",null,"周报",0).isEmpty());
        assertTrue("LIKE wildcards are literal",service.list("alice",null,"%",0).isEmpty());
        assertTrue(service.list("alice",null,null,100).isEmpty());
    }
    @Test public void csvEscapesFormulasQuotesAndNewlinesWithoutChangingStoredCells()throws Exception {
        JsonNode content=json.readTree("{\"columns\":[\"name\",\"value\"],\"rows\":[[\"=1+1\",\"  @cmd\"],[\"a,b\",\"quote\\\"\\nline\"]]}");
        ObjectNode body=json.createObjectNode().put("runId","run-a").put("title","表格").put("format","TABLE").set("content",content);
        var stored=transact(()->service.save("alice",null,body));String id=stored.get("artifactId").toString();
        String csv=new String(service.export("alice",id,1).bytes(),StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=1+1\",\"'  @cmd\""));assertTrue(csv.contains("\"a,b\",\"quote\"\"\nline\""));
        assertEquals("=1+1",((JsonNode)service.artifact("alice",id,1).get("content")).path("rows").get(0).get(0).asText());
    }
    @Test public void codePreservesSourceAndVersionsAndDownloadsAnInertUtf8Attachment() {
        String original="\n  # 中文，保留空白\r\n\tprint(\"C:\\\\tmp\\n\")  \r\n\n";
        var saved=transact(()->service.save("alice",null,code("示例.py",original)));String id=saved.get("artifactId").toString();
        assertEquals("CODE",saved.get("format"));
        assertEquals(original,((JsonNode)saved.get("content")).path("code").asText());
        var exported=service.export("alice",id,1);
        assertEquals("示例.py",exported.filename());assertEquals("application/octet-stream",exported.contentType());
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),exported.bytes());
        transact(()->service.save("alice",id,code("updated.py","print('second')\n").put("revision",1)));
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),service.export("alice",id,1).bytes());
        assertEquals("updated.py",service.export("alice",id,0).filename());
        assertThrows(ResponseStatusException.class,()->service.export("bob",id,1));
        var auth=new UsernamePasswordAuthenticationToken("alice","unused",List.of());
        @SuppressWarnings("unchecked") var noSandbox=(org.springframework.beans.factory.ObjectProvider<cn.bugstack.ai.trigger.sandbox.SandboxService>)org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var response=new WorkspaceOutcomeController(service,noSandbox).export(auth,id,1,null);
        assertEquals("示例.py",response.getHeaders().getContentDisposition().getFilename());
        assertEquals("attachment",response.getHeaders().getContentDisposition().getType());
        assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),response.getBody());
    }
    @Test public void codeRejectsPathsReservedNamesInvalidMetadataAndMixedPayloadsWithoutWrites() {
        for(String filename:List.of("../out.py","sub/out.py","sub\\out.py","C:out.py","bad\r\n.py","bad\u0000.py","bad\".py","CON.py","LPT1.java","out.py ","out.py.","tool.exe","README")) {
            var failure=assertThrows(filename,ResponseStatusException.class,()->transact(()->service.save("alice",null,code(filename,"print(1)"))));
            assertTrue(failure.getReason().contains("content.filename"));
        }
        var invalidLanguage=code("out.py","print(1)");((ObjectNode)invalidLanguage.get("content")).put("language","<script>");
        assertTrue(assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,invalidLanguage))).getReason().contains("content.language"));
        var mixed=code("out.py","print(1)");((ObjectNode)mixed.get("content")).put("markdown","# unexpected");
        assertTrue(assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,mixed))).getReason().contains("content.markdown"));
        assertTrue(assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,code("out.py"," \n\t")))).getReason().contains("content.code"));
        assertEquals(0,(int)db.queryForObject("SELECT COUNT(*) FROM ai_run_artifact",Integer.class));
    }
    @Test public void htmlSourceAndCommonConfigFilenamesAreSavedWithoutExecutionOrTransformation() {
        for(String filename:List.of("index.html","Main.java","query.sql","hello.js","config.yaml","Dockerfile","Makefile",".gitignore")) {
            String source="<script>window.__mustNotExecute = true</script>\n";
            var saved=transact(()->service.save("alice",null,code(filename,source)));
            var exported=service.export("alice",saved.get("artifactId").toString(),1);
            assertEquals(filename,exported.filename());assertEquals("application/octet-stream",exported.contentType());
            assertArrayEquals(source.getBytes(StandardCharsets.UTF_8),exported.bytes());
        }
    }
    @Test public void rejectsMalformedOversizedAndUnownedOutcomesWithoutWrites()throws Exception {
        ObjectNode wrong=json.createObjectNode().put("runId","run-a").put("title","bad").put("format","TABLE")
                .set("content",json.readTree("{\"columns\":[\"a\",\"b\"],\"rows\":[[\"only one\"]]}"));
        assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,wrong)));
        assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,markdown("x".repeat(200001)))));
        assertThrows(ResponseStatusException.class,()->transact(()->service.save("alice",null,markdown("bad").put("runId","foreign"))));
        assertEquals(0,(int)db.queryForObject("SELECT COUNT(*) FROM ai_run_artifact",Integer.class));
    }
    @Test public void feedbackOptInIsPrivateDisabledAndNeverTurnsOriginalAnswerIntoGold() {
        transact(()->service.saveFeedback("alice","run-a",feedback(0,false)));verifyNoInteractions(datasets);
        var saved=transact(()->service.saveFeedback("alice","run-a",feedback(1,true)));
        assertEquals("personal-draft",saved.get("evalDatasetId"));assertEquals(7,saved.get("agentVersion"));
        ArgumentCaptor<EvalDatasetService.CaseCommand> command=ArgumentCaptor.forClass(EvalDatasetService.CaseCommand.class);
        verify(datasets).saveCase(eq("personal-draft"),isNull(),command.capture());
        assertEquals("真正的问题",command.getValue().getQuestion());assertNull(command.getValue().getReferenceAnswer());assertEquals(false,command.getValue().getEnabled());
        transact(()->service.saveFeedback("alice","run-a",feedback(2,true).put("correctedAnswer","later correction")));
        verify(datasets,times(1)).saveCase(anyString(),isNull(),any());verify(datasets,never()).publish(anyString(),anyString());
        assertThrows(ResponseStatusException.class,()->transact(()->service.saveFeedback("bob","run-a",feedback(0,true))));
        assertThrows(ResponseStatusException.class,()->transact(()->service.saveFeedback("alice","run-a",feedback(1,false))));
    }
    @Test public void failedEvalCreationRollsBackFeedbackAndNewDatasetTogether() {
        db.execute("CREATE TABLE test_draft(id VARCHAR PRIMARY KEY)");
        when(datasets.createDataset(anyString(),anyString(),anyString(),eq("alice"))).thenAnswer(call->{db.update("INSERT INTO test_draft VALUES('new')");return Map.of("datasetId","personal-draft");});
        when(datasets.saveCase(anyString(),isNull(),any())).thenThrow(new IllegalArgumentException("case invalid"));
        assertThrows(IllegalArgumentException.class,()->transact(()->service.saveFeedback("alice","run-a",feedback(0,true))));
        assertTrue(service.feedback("alice","run-a").isEmpty());assertEquals(0,(int)db.queryForObject("SELECT COUNT(*) FROM test_draft",Integer.class));
    }
}
