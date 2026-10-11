package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.model.*;
import cn.bugstack.ai.infrastructure.security.JdbcModelUsageAccounting;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ModelUsageAccountingTest {
    private JdbcTemplate db;
    private DriverManagerDataSource dataSource;
    private JdbcModelUsageAccounting ledger;
    private ModelProviderAccess access;
    private MutableClock clock;
    private static final class MutableClock extends Clock {
        final AtomicLong millis=new AtomicLong(Instant.parse("2026-10-09T00:00:00Z").toEpochMilli());
        @Override public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(java.time.ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(millis.get());}
    }
    @Before public void setup(){
        clock=new MutableClock();dataSource=new DriverManagerDataSource("jdbc:h2:mem:usage_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        db=new JdbcTemplate(dataSource);access=mock(ModelProviderAccess.class);
        db.execute("CREATE TABLE ai_model_usage_account(user_id VARCHAR(64) PRIMARY KEY)");
        db.execute("CREATE TABLE ai_model_usage_day(user_id VARCHAR(64),usage_date DATE,platform_calls BIGINT DEFAULT 0,platform_reserved_tokens BIGINT DEFAULT 0,confirmed_tokens BIGINT DEFAULT 0,unknown_calls BIGINT DEFAULT 0,PRIMARY KEY(user_id,usage_date))");
        db.execute("CREATE TABLE ai_model_usage_run(run_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(64),billing_source VARCHAR(16),state VARCHAR(24),deadline_at TIMESTAMP,create_time TIMESTAMP DEFAULT NOW(),update_time TIMESTAMP DEFAULT NOW())");
        db.execute("CREATE TABLE ai_model_usage_attempt(attempt_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(128),user_id VARCHAR(64),api_id VARCHAR(64),provider_host VARCHAR(255),model_name VARCHAR(255),purpose VARCHAR(64),billing_source VARCHAR(16),usage_date DATE,input_estimate BIGINT,output_limit BIGINT,reserved_tokens BIGINT,actual_tokens BIGINT,usage_status VARCHAR(16),outcome VARCHAR(32),cost_amount DECIMAL(20,10),cost_currency VARCHAR(8),pricing_status VARCHAR(16),create_time TIMESTAMP DEFAULT NOW(),settled_at TIMESTAMP)");
        db.execute("CREATE TABLE ai_client_api(api_id VARCHAR(64) PRIMARY KEY,owner_user_id VARCHAR(64),secret_ref VARCHAR(64),status INT,base_url VARCHAR(255))");
        db.execute("CREATE TABLE ai_client_model(api_id VARCHAR(64),model_name VARCHAR(255),platform_enabled INT,status INT)");
        db.execute("CREATE TABLE ai_model_secret(secret_ref VARCHAR(64),owner_user_id VARCHAR(64),status INT)");
        db.update("INSERT INTO ai_client_api VALUES('platform','admin',NULL,1,'https://provider.test')");
        db.update("INSERT INTO ai_client_model VALUES('platform','chat',1,1)");
        ledger=newLedger(1000,20,2);
    }
    @After public void cleanup(){ModelUsageLedger.uninstall(ledger);org.slf4j.MDC.clear();}
    private JdbcModelUsageAccounting newLedger(long tokens,long calls,int runs){return new JdbcModelUsageAccounting(db,new DataSourceTransactionManager(dataSource),access,tokens,calls,runs,clock);}
    private void run(String id,String owner){ledger.openRun(id,owner,clock.millis()+60000,false);}
    private ModelUsageAccounting.Attempt attempt(String owner,String run,String api,long amount){return new ModelUsageAccounting.Attempt(owner,run,api,"chat","MAIN","provider.test",amount,0);}
    @Test public void actualSettlementAndRepeatedCallbackAreExactlyOnce(){
        run("r","alice");var ticket=ledger.begin(attempt("alice","r","platform",100));ticket.settle(60L,"SUCCESS");ticket.settle(1L,"SUCCESS");
        assertEquals(Long.valueOf(60),db.queryForObject("SELECT platform_reserved_tokens FROM ai_model_usage_day",Long.class));
        assertEquals(Long.valueOf(60),db.queryForObject("SELECT actual_tokens FROM ai_model_usage_attempt",Long.class));
        assertEquals(Long.valueOf(1),db.queryForObject("SELECT platform_calls FROM ai_model_usage_day",Long.class));
    }
    @Test public void unknownUsageAndPricingRemainNullAndSurviveRestart(){
        run("r","alice");ledger.begin(attempt("alice","r","platform",1000)).settle(null,"ERROR");
        ledger=newLedger(1000,20,2);
        assertThrows(RunBudgetExceededException.class,()->ledger.begin(attempt("alice","r","platform",1)));
        var row=db.queryForMap("SELECT actual_tokens,cost_amount,usage_status,pricing_status FROM ai_model_usage_attempt");
        assertNull(row.get("actual_tokens"));assertNull(row.get("cost_amount"));assertEquals("UNKNOWN",row.get("usage_status"));assertEquals("UNKNOWN",row.get("pricing_status"));
        assertEquals(Long.valueOf(1000),db.queryForObject("SELECT platform_reserved_tokens FROM ai_model_usage_day",Long.class));
    }
    @Test public void concurrentAttemptsCannotSpendTheSameQuotaAcrossInstances() throws Exception {
        run("r","alice");var other=newLedger(1000,20,2);var workers=Executors.newFixedThreadPool(6);AtomicInteger admitted=new AtomicInteger();
        try {
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<25;i++){var service=i%2==0?ledger:other;jobs.add(workers.submit(()->{
                try{service.begin(attempt("alice","r","platform",100));admitted.incrementAndGet();}catch(RunBudgetExceededException expected){}
            }));}
            for(var job:jobs)job.get(20,TimeUnit.SECONDS);
            assertEquals(10,admitted.get());assertEquals(Long.valueOf(1000),db.queryForObject("SELECT platform_reserved_tokens FROM ai_model_usage_day",Long.class));
        } finally {workers.shutdownNow();}
    }
    @Test public void personalKeyDoesNotConsumePlatformQuotaAndCannotCrossOwners(){
        db.update("INSERT INTO ai_client_api VALUES('personal','alice','secret',1,'https://provider.test')");db.update("INSERT INTO ai_model_secret VALUES('secret','alice',1)");
        run("alice-run","alice");run("bob-run","bob");
        ledger.begin(attempt("alice","alice-run","personal",99999)).settle(500L,"SUCCESS");
        assertEquals(Integer.valueOf(0),db.queryForObject("SELECT COUNT(*) FROM ai_model_usage_day",Integer.class));
        assertEquals("PERSONAL",db.queryForObject("SELECT billing_source FROM ai_model_usage_attempt",String.class));
        assertThrows(SecurityException.class,()->ledger.begin(attempt("bob","bob-run","personal",1)));
        assertThrows(SecurityException.class,()->ledger.begin(attempt("bob","alice-run","platform",1)));
    }
    @Test public void reviewHoldsConcurrencyAcrossRestartAndCannotExtendDeadline(){
        run("a","alice");run("b","alice");ledger.changeRunState("a","alice","WAITING_REVIEW");
        ledger=newLedger(1000,20,2);assertThrows(RunBudgetExceededException.class,()->run("c","alice"));
        ledger.openRun("a","alice",clock.millis()+9999999,false);
        assertEquals(clock.millis()+60000,db.queryForObject("SELECT deadline_at FROM ai_model_usage_run WHERE run_id='a'",java.sql.Timestamp.class).getTime());
        ledger.changeRunState("b","alice","COMPLETED");run("c","alice");
        clock.millis.addAndGet(60001);run("d","alice");
        assertEquals("EXPIRED",db.queryForObject("SELECT state FROM ai_model_usage_run WHERE run_id='a'",String.class));
    }
    @Test public void abandonedReservationIsUnknownAndNeverRefundedAsFree(){
        run("r","alice");ledger.begin(attempt("alice","r","platform",200));clock.millis.addAndGet(60001);ledger.overview("alice");
        var row=db.queryForMap("SELECT usage_status,outcome,actual_tokens FROM ai_model_usage_attempt");
        assertEquals("UNKNOWN",row.get("usage_status"));assertEquals("ABANDONED",row.get("outcome"));assertNull(row.get("actual_tokens"));
        assertEquals(Long.valueOf(200),db.queryForObject("SELECT platform_reserved_tokens FROM ai_model_usage_day",Long.class));
        assertEquals(Long.valueOf(1),db.queryForObject("SELECT unknown_calls FROM ai_model_usage_day",Long.class));
    }
    @Test public void auxiliaryConsentRejectsBeforeReservation(){
        run("r","alice");doThrow(new SecurityException("denied")).when(access).requirePlatformAuxiliary("alice","EMBEDDING");
        assertThrows(SecurityException.class,()->ledger.begin(new ModelUsageAccounting.Attempt("alice","r",null,"embed","EMBEDDING","provider.test",20,0)));
        assertEquals(Integer.valueOf(0),db.queryForObject("SELECT COUNT(*) FROM ai_model_usage_attempt",Integer.class));
        assertEquals(Integer.valueOf(0),db.queryForObject("SELECT COUNT(*) FROM ai_model_usage_day",Integer.class));
    }
    @Test public void systemSourceIsAuditedWithoutPersonalQuota(){
        ledger.openRun("sys","system-owner",clock.millis()+60000,true);
        ledger.begin(new ModelUsageAccounting.Attempt("system-owner","sys",null,"embed","EMBEDDING","provider.test",99999,0)).settle(null,"SUCCESS");
        assertEquals("SYSTEM",db.queryForObject("SELECT billing_source FROM ai_model_usage_attempt",String.class));
        assertEquals(Integer.valueOf(0),db.queryForObject("SELECT COUNT(*) FROM ai_model_usage_day",Integer.class));
        verifyNoInteractions(access);
    }
    @Test public void usageViewIsAlwaysOwnerScoped(){
        run("a","alice");run("b","bob");ledger.begin(attempt("alice","a","platform",20));ledger.begin(attempt("bob","b","platform",30));
        var rows=(List<?>)ledger.overview("alice").get("attempts");assertEquals(1,rows.size());assertEquals("a",((Map<?,?>)rows.get(0)).get("runId"));
    }
    @Test public void runFilterKeepsOwnerBoundaryAndAccountAllowanceWithUnknownMoney(){
        run("a","alice");run("a-other","alice");run("b","bob");
        ledger.begin(attempt("alice","a","platform",20)).settle(10L,"SUCCESS");
        ledger.begin(attempt("alice","a","platform",30)).settle(null,"ERROR");
        ledger.begin(attempt("alice","a-other","platform",50));ledger.begin(attempt("bob","b","platform",100));
        var view=ledger.overview("alice","a");assertEquals("a",view.get("filteredRunId"));
        assertEquals(2,((List<?>)view.get("attempts")).size());
        var stats=(Map<?,?>)view.get("runSummary");
        assertEquals(2,((Number)stats.get("calls")).intValue());assertEquals(10,((Number)stats.get("confirmedTokens")).intValue());
        assertEquals(1,((Number)stats.get("unknownCalls")).intValue());assertEquals("UNKNOWN",stats.get("pricingStatus"));assertNull(stats.get("costAmount"));
        assertEquals(90,((Number)((Map<?,?>)view.get("today")).get("platformReservedTokens")).intValue());
        var hidden=ledger.overview("alice","b");assertTrue(((List<?>)hidden.get("attempts")).isEmpty());
        assertEquals(0,((Number)((Map<?,?>)hidden.get("runSummary")).get("calls")).intValue());
    }
    @Test public void malformedSynchronousPostCannotReachNetwork() throws Exception {
        var request=new org.springframework.mock.http.client.MockClientHttpRequest();request.setMethod(org.springframework.http.HttpMethod.POST);
        AtomicBoolean sent=new AtomicBoolean();
        assertThrows(IllegalArgumentException.class,()->ModelRequestGuard.synchronous().intercept(request,"not-json".getBytes(),(r,b)->{sent.set(true);return null;}));
        assertFalse(sent.get());
    }
    @Test public void registryReviewAndFinishUpdatePersistentConcurrencyLifecycle(){
        ModelUsageLedger.install(ledger);String id=UUID.randomUUID().toString();
        var state=new RunBudgetRegistry().start(id,"alice");state.awaitReview();RunBudgetRegistry.finish(id);
        assertEquals("WAITING_REVIEW",db.queryForObject("SELECT state FROM ai_model_usage_run WHERE run_id=?",String.class,id));
        state.resumeReview();RunBudgetRegistry.finish(id);
        assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ai_model_usage_run WHERE run_id=?",String.class,id));
    }
    @Test public void capturedMaintenanceAfterFinishStillHasDurableUsage(){
        ModelUsageLedger.install(ledger);String id=UUID.randomUUID().toString();new RunBudgetRegistry().start(id,"alice");
        org.slf4j.MDC.put("runId",id);
        Runnable work=RunBudgetRegistry.captureMaintenance(()->{
            try {
                var request=new LinkedHashMap<String,Object>();request.put("model","chat");request.put("messages",List.of(Map.of("role","user","content","summary")));request.put("max_tokens",20);
                var prepared=ModelRequestGuard.prepare(request,RunBudgetRegistry.find(Map.of()),ModelUsageContext.api("platform","CHAT_HELPER","https://provider.test"));
                prepared.reservation().settle(30L);prepared.usageTicket().settle(30L,"SUCCESS");
            } catch(java.io.IOException failure){throw new AssertionError(failure);}
        });
        RunBudgetRegistry.finish(id);work.run();
        assertEquals(Long.valueOf(30),db.queryForObject("SELECT actual_tokens FROM ai_model_usage_attempt WHERE run_id=?",Long.class,id));
        assertEquals("COMPLETED",db.queryForObject("SELECT state FROM ai_model_usage_run WHERE run_id=?",String.class,id));
    }
}
