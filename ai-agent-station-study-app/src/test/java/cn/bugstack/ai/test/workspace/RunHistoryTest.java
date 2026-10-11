package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.infrastructure.adapter.repository.JdbcRunHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DuplicateKeyException;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.Assert.*;

public class RunHistoryTest {
    JdbcTemplate db;JdbcRunHistoryRepository history;ObjectMapper json=new ObjectMapper();
    @Before public void setup(){
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;VARIABLE_BINARY=TRUE;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE ai_run_history(run_id VARCHAR PRIMARY KEY,user_id VARCHAR,session_id VARCHAR,agent_id VARCHAR,agent_version INT,strategy VARCHAR,status VARCHAR,question CLOB,answer CLOB,events_json CLOB,events_truncated INT DEFAULT 0,last_error VARCHAR,instance_id VARCHAR,heartbeat_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        history=new JdbcRunHistoryRepository(db,json);
    }
    ExecuteCommandEntity request(){return ExecuteCommandEntity.builder().runId("r1").userId("alice").sessionId("s1").message("原始问题").aiAgentId("agent").agentVersion(3).build();}
    @Test public void duplicateRunAndForeignOwnerAreRejectedAcrossInstances(){
        history.start(request());history.bindAgent(request(),"flow");
        var restarted=new JdbcRunHistoryRepository(db,json);
        assertThrows(DuplicateKeyException.class,()->restarted.start(request()));
        assertTrue(restarted.findOwned("bob","r1").isEmpty());assertTrue(restarted.listOwned("bob",0,20).isEmpty());
        assertEquals(3,restarted.findOwned("alice","r1").orElseThrow().get("agentVersion"));
        history.answer("bob","r1","evil");assertNull(history.findOwned("alice","r1").orElseThrow().get("answer"));
        history.answer("alice","r1","final");history.answer("alice","r1","duplicate");
        assertEquals("final",db.queryForObject("SELECT answer FROM ai_run_history",String.class));
    }
    @Test public void staleWorkersBecomeInterruptedWithoutReexecutingAndReviewIsDistinct(){
        history.start(request());history.status("r1","BLOCKED",null);
        db.update("UPDATE ai_run_history SET heartbeat_at=?",new Timestamp(0));
        history.heartbeat();assertEquals("BLOCKED",history.findOwned("alice","r1").orElseThrow().get("status"));
        history.status("r1","RUNNING",null);history.status("r1","COMPLETED",null);history.status("r1","FAILED","late");
        assertEquals("COMPLETED",history.findOwned("alice","r1").orElseThrow().get("status"));
        ExecuteCommandEntity next=request();next.setRunId("r2");history.start(next);
        db.update("UPDATE ai_run_history SET heartbeat_at=? WHERE run_id='r2'",new Timestamp(0));
        new JdbcRunHistoryRepository(db,json).heartbeat();
        assertEquals("INTERRUPTED",history.findOwned("alice","r2").orElseThrow().get("status"));
    }
    @Test public void oversizedDiagnosticsRemainValidJsonWithExplicitTruncation() throws Exception {
        history.start(request());List<RunEventRecord> events=new ArrayList<>();
        for(int i=0;i<80;i++)events.add(RunEventRecord.builder().eventId("e"+i).eventType("token").payloadJson("{\"token\":\""+"x".repeat(50000)+"\"}").createdAt(1L).build());
        history.checkpoint("r1",events);
        String value=db.queryForObject("SELECT events_json FROM ai_run_history",String.class);
        assertTrue(value.length()<1050000);assertTrue(json.readTree(value).isArray());
        assertEquals(1,(int)db.queryForObject("SELECT events_truncated FROM ai_run_history",Integer.class));
        assertTrue(value.contains("truncated"));
    }
    @Test public void durableEvalReplayRetainsTheSessionAndOwnerAfterRedisExpiry() {
        ExecuteCommandEntity eval = request();
        eval.setSessionId("eval-b8b7c137-e2e100-main");
        history.start(eval);
        history.checkpoint("r1", List.of(RunEventRecord.builder().eventId("123-0").eventType("step_start")
                .payloadJson("{\"stepId\":\"analysis\",\"displayName\":\"分析\"}").createdAt(123L).build()));
        history.status("r1", "COMPLETED", null);
        var restored = new JdbcRunHistoryRepository(db, json).findOwned("alice", "r1").orElseThrow();
        assertEquals("eval-b8b7c137-e2e100-main", restored.get("sessionId"));
        assertEquals("alice", restored.get("userId"));
        assertTrue(restored.get("events").toString().contains("step_start"));
        assertTrue(history.findOwned("bob", "r1").isEmpty());
    }
    @Test public void expiredReviewCannotRemainBlockedIndefinitelyAfterRestart() {
        history.start(request());history.status("r1","BLOCKED",null);
        db.update("UPDATE ai_run_history SET created_at=?",new Timestamp(System.currentTimeMillis()-1_801_000));
        new JdbcRunHistoryRepository(db,json).heartbeat();
        var row=history.findOwned("alice","r1").orElseThrow();
        assertEquals("INTERRUPTED",row.get("status"));assertTrue(row.get("error").toString().contains("deadline"));
    }
    @Test public void sessionDiscoverySurvivesRestartWithoutAnswersAndKeepsDiagnosticsOutOfTheList() {
        ExecuteCommandEntity failed=request();failed.setMessage("问".repeat(500));
        history.start(failed);history.bindAgent(failed,"flow");
        history.checkpoint("r1",List.of(RunEventRecord.builder().eventId("event-1").eventType("error")
                .payloadJson("{\"message\":\"provider failed\"}").createdAt(123L).build()));
        history.status("r1","FAILED","provider failed");
        ExecuteCommandEntity cancelled=request();cancelled.setRunId("r2");history.start(cancelled);
        history.status("r2","CANCELLED","user cancelled");
        // No answer/chat-memory/Redis rows are needed to discover either terminal run.
        db.update("UPDATE ai_run_history SET created_at=?",new Timestamp(123));
        var restarted=new JdbcRunHistoryRepository(db,json);
        var rows=restarted.listOwnedSession("alice","s1",0,50);
        assertEquals(List.of("r2","r1"),rows.stream().map(row->row.get("runId")).toList());
        assertEquals("CANCELLED",rows.get(0).get("status"));
        var row=rows.get(1);
        assertEquals("FAILED",row.get("status"));assertEquals("provider failed",row.get("error"));
        assertEquals("alice",row.get("userId"));assertEquals("s1",row.get("sessionId"));
        assertEquals("agent",row.get("agentId"));assertEquals(3,row.get("agentVersion"));assertEquals("flow",row.get("strategy"));
        assertEquals(300,row.get("question").toString().length());
        assertNotNull(row.get("createdAt"));assertNotNull(row.get("updatedAt"));
        assertFalse(row.containsKey("events"));assertFalse(row.containsKey("answer"));assertFalse(row.containsKey("instance_id"));
        assertTrue(restarted.findOwned("alice","r1").orElseThrow().get("events").toString().contains("provider failed"));
    }
    @Test public void sessionDiscoveryPaginatesDeterministicallyWithinOwnerAndSession() {
        for(int i=0;i<105;i++) {
            ExecuteCommandEntity r=request();r.setRunId(String.format(Locale.ROOT,"r%03d",i));history.start(r);
        }
        ExecuteCommandEntity otherSession=request();otherSession.setRunId("other-session");otherSession.setSessionId("s2");history.start(otherSession);
        ExecuteCommandEntity otherOwner=request();otherOwner.setRunId("other-owner");otherOwner.setUserId("bob");history.start(otherOwner);
        db.update("UPDATE ai_run_history SET created_at=?",new Timestamp(1000));
        db.update("UPDATE ai_run_history SET created_at=? WHERE run_id='r000'",new Timestamp(2000));
        assertEquals(List.of("r000","r104"),history.listOwnedSession("alice","s1",0,2).stream().map(row->row.get("runId")).toList());
        assertEquals(List.of("r103","r102"),history.listOwnedSession("alice","s1",2,2).stream().map(row->row.get("runId")).toList());
        assertEquals(100,history.listOwnedSession("alice","s1",-1,Integer.MAX_VALUE).size());
        assertEquals("r000",history.listOwnedSession("alice","s1",-1,0).get(0).get("runId"));
        assertEquals(1,history.listOwnedSession("alice","s1",104,50).size());
        assertTrue(history.listOwnedSession("alice","s1",Integer.MAX_VALUE,50).isEmpty());
        assertEquals(List.of("other-session"),history.listOwnedSession("alice","s2",0,50).stream().map(row->row.get("runId")).toList());
        assertEquals(List.of("other-owner"),history.listOwnedSession("bob","s1",0,50).stream().map(row->row.get("runId")).toList());
    }
    @Test public void durableReadsRequireExactIdentifiersEvenWithCaseInsensitiveColumns() {
        db.execute("ALTER TABLE ai_run_history ALTER COLUMN user_id VARCHAR_IGNORECASE");
        db.execute("ALTER TABLE ai_run_history ALTER COLUMN session_id VARCHAR_IGNORECASE");
        db.execute("ALTER TABLE ai_run_history ALTER COLUMN run_id VARCHAR_IGNORECASE");
        ExecuteCommandEntity base=request();base.setRunId("run-one");base.setSessionId("session-one");history.start(base);
        ExecuteCommandEntity variant=request();variant.setSessionId("session-one");variant.setRunId("variant");variant.setUserId("alIce");history.start(variant);
        variant=request();variant.setRunId("session-variant");variant.setSessionId("session-One");history.start(variant);
        variant=request();variant.setRunId("padded-session");variant.setSessionId("session-one ");history.start(variant);
        variant=request();variant.setSessionId("session-one");variant.setRunId("padded-owner");variant.setUserId("alice ");history.start(variant);
        assertEquals(List.of("run-one"),history.listOwnedSession("alice","session-one",0,50).stream().map(row->row.get("runId")).toList());
        assertEquals(List.of("variant"),history.listOwnedSession("alIce","session-one",0,50).stream().map(row->row.get("runId")).toList());
        assertTrue(history.findOwned("alIce","run-one").isEmpty());assertTrue(history.findOwned("alice","run-One").isEmpty());
        assertTrue(history.findOwned("alice ","run-one").isEmpty());assertTrue(history.findOwned("alice","run-one ").isEmpty());
        assertTrue(history.listOwned("ALICE",0,50).isEmpty());
    }
}
