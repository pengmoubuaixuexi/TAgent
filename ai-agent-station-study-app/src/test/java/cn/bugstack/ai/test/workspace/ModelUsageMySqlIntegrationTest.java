package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.model.*;
import cn.bugstack.ai.infrastructure.security.JdbcModelUsageAccounting;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

/** Opt-in isolated MySQL fixture only; no migration, truncation, provider or shared database access. */
public class ModelUsageMySqlIntegrationTest {
    private JdbcTemplate db;private DriverManagerDataSource source;private JdbcModelUsageAccounting ledger;
    private String user,api,model,run;
    @Before public void setup()throws Exception{
        String url=System.getenv("TAGENT_TEST_DB_URL");assumeTrue(url!=null&&!url.isBlank());
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/tagent_workspace_isolation_test(?:\\?.*)?"));
        source=new DriverManagerDataSource(url,Objects.requireNonNullElse(System.getenv("TAGENT_TEST_DB_USER"),"root"),Objects.requireNonNullElse(System.getenv("TAGENT_TEST_DB_PASSWORD"),""));
        try(var connection=source.getConnection()){assertEquals("tagent_workspace_isolation_test",connection.getCatalog());}
        db=new JdbcTemplate(source);user="usage_test_"+UUID.randomUUID().toString().substring(0,8);api=user+"_api";model=user+"_model";run=user+"_run";
        db.update("INSERT INTO ai_client_api(api_id,owner_user_id,base_url,api_key,completions_path,embeddings_path,status) VALUES(?,?,'https://fixture.example.test','synthetic-test-only','/v1/chat/completions','/v1/embeddings',1)",api,user);
        db.update("INSERT INTO ai_client_model(model_id,api_id,owner_user_id,model_name,model_type,model_usage,tier,status,platform_enabled) VALUES(?,?,?,'fixture-chat','openai','chat','medium',1,1)",model,api,user);
        ledger=fresh();ledger.openRun(run,user,System.currentTimeMillis()+60000,false);
    }
    private JdbcModelUsageAccounting fresh(){return new JdbcModelUsageAccounting(db,new DataSourceTransactionManager(source),mock(ModelProviderAccess.class),1000,20,1);}
    private ModelUsageAccounting.Attempt request(long tokens){return new ModelUsageAccounting.Attempt(user,run,api,"fixture-chat","MAIN","fixture.example.test",tokens,0);}
    @After public void cleanup(){
        if(db==null||user==null)return;
        for(String table:List.of("ai_model_usage_attempt","ai_model_usage_run","ai_model_usage_day","ai_model_usage_account"))db.update("DELETE FROM "+table+" WHERE user_id=?",user);
        db.update("DELETE FROM ai_client_model WHERE model_id=? AND owner_user_id=?",model,user);db.update("DELETE FROM ai_client_api WHERE api_id=? AND owner_user_id=?",api,user);
    }
    @Test public void mysqlRowLockSerializesAttemptsAcrossServiceInstances()throws Exception{
        var other=fresh();var workers=Executors.newFixedThreadPool(8);AtomicInteger admitted=new AtomicInteger();
        try{
            List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<24;i++){var service=i%2==0?ledger:other;jobs.add(workers.submit(()->{
                try{service.begin(request(100));admitted.incrementAndGet();}catch(RunBudgetExceededException expected){}
            }));}
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
            assertEquals(10,admitted.get());assertEquals(Long.valueOf(1000),db.queryForObject("SELECT platform_reserved_tokens FROM ai_model_usage_day WHERE user_id=?",Long.class,user));
        }finally{workers.shutdownNow();}
    }
    @Test public void mysqlUnknownAndReviewQuotaSurviveServiceRestart(){
        ledger.begin(request(1000)).settle(null,"ERROR");ledger.changeRunState(run,user,"WAITING_REVIEW");ledger=fresh();
        assertThrows(RunBudgetExceededException.class,()->ledger.begin(request(1)));
        assertThrows(RunBudgetExceededException.class,()->ledger.openRun(run+"2",user,System.currentTimeMillis()+60000,false));
        var row=db.queryForMap("SELECT actual_tokens,cost_amount,usage_status FROM ai_model_usage_attempt WHERE user_id=?",user);
        assertNull(row.get("actual_tokens"));assertNull(row.get("cost_amount"));assertEquals("UNKNOWN",row.get("usage_status"));
    }
    @Test public void mysqlExpirationPreservesUnknownReservation(){
        ledger.begin(request(200));db.update("UPDATE ai_model_usage_run SET deadline_at=? WHERE run_id=? AND user_id=?",new java.sql.Timestamp(System.currentTimeMillis()-1000),run,user);
        ledger.overview(user);assertEquals("ABANDONED",db.queryForObject("SELECT outcome FROM ai_model_usage_attempt WHERE user_id=?",String.class,user));
        assertEquals(Long.valueOf(200),db.queryForObject("SELECT platform_reserved_tokens FROM ai_model_usage_day WHERE user_id=?",Long.class,user));
        ledger.openRun(run+"2",user,System.currentTimeMillis()+60000,false);
    }
}
