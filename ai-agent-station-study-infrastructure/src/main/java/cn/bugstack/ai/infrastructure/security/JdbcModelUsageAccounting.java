package cn.bugstack.ai.infrastructure.security;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Authoritative per-user row lock serializes quota admission across processes and restarts. */
@Service
public class JdbcModelUsageAccounting implements ModelUsageAccounting {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final ModelProviderAccess access;
    private final long dailyTokens,dailyCalls;
    private final int concurrentRuns;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcModelUsageAccounting(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate db,
            PlatformTransactionManager manager,ModelProviderAccess access,
            @Value("${agent.usage.platform-daily-tokens:500000}") long dailyTokens,
            @Value("${agent.usage.platform-daily-calls:200}") long dailyCalls,
            @Value("${agent.usage.max-concurrent-runs:3}") int concurrentRuns) {
        this(db,manager,access,dailyTokens,dailyCalls,concurrentRuns,Clock.systemUTC());
    }
    public JdbcModelUsageAccounting(JdbcTemplate db,PlatformTransactionManager manager,ModelProviderAccess access,
            long dailyTokens,long dailyCalls,int concurrentRuns,Clock clock) {
        if(dailyTokens<1||dailyCalls<1||concurrentRuns<1)throw new IllegalArgumentException("用量配额必须大于零");
        this.db=db;this.access=access;this.dailyTokens=dailyTokens;this.dailyCalls=dailyCalls;this.concurrentRuns=concurrentRuns;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @jakarta.annotation.PostConstruct public void install(){ModelUsageLedger.install(this);}
    @jakarta.annotation.PreDestroy public void uninstall(){ModelUsageLedger.uninstall(this);}
    @Override public void openRun(String run,String user,long deadline,boolean system) {
        identity(run,user);
        if(deadline<=clock.millis())throw new RunBudgetExceededException("运行时限已过");
        transaction.executeWithoutResult(status->{
            lockUser(user);expireRuns(user);
            var existing=db.queryForList("SELECT user_id,state,deadline_at FROM ai_model_usage_run WHERE run_id=?",run);
            if(!existing.isEmpty()) {
                var row=existing.get(0);
                if(!user.equals(row.get("user_id")))throw new SecurityException("运行用量归属不匹配");
                if(!Set.of("ACTIVE","WAITING_REVIEW").contains(row.get("state")))throw new RunBudgetExceededException("运行已结束，不能重新使用该运行额度");
                return;
            }
            if(!system && db.queryForObject("SELECT COUNT(*) FROM ai_model_usage_run WHERE user_id=? AND state IN ('ACTIVE','WAITING_REVIEW')",Long.class,user)>=concurrentRuns)
                throw new RunBudgetExceededException("同时运行数量已达到账号上限，请结束现有任务或等待其时限到期");
            db.update("INSERT INTO ai_model_usage_run(run_id,user_id,billing_source,state,deadline_at) VALUES(?,?,?,'ACTIVE',?)",
                    run,user,system?"SYSTEM":"USER",Timestamp.from(Instant.ofEpochMilli(deadline)));
        });
    }
    @Override public void changeRunState(String run,String user,String state) {
        identity(run,user);
        if(!Set.of("ACTIVE","WAITING_REVIEW","COMPLETED","CANCELLED","EXHAUSTED").contains(state))throw new IllegalArgumentException("运行状态无效");
        transaction.executeWithoutResult(status->{
            lockUser(user);
            // Terminal rows cannot be reopened; repeated finalization is harmless.
            db.update("UPDATE ai_model_usage_run SET state=?,update_time=NOW() WHERE run_id=? AND user_id=? AND state IN ('ACTIVE','WAITING_REVIEW')",state,run,user);
        });
    }
    @Override public Ticket begin(Attempt attempt) {
        identity(attempt.runId(),attempt.userId());
        if(attempt.model()==null||attempt.model().isBlank()||attempt.providerHost()==null||attempt.providerHost().isBlank())
            throw new SecurityException("模型请求缺少可核验的提供商或模型信息");
        long requested=Math.addExact(Math.max(0,attempt.inputEstimate()),Math.max(0,attempt.outputLimit()));
        String id=UUID.randomUUID().toString();
        transaction.executeWithoutResult(status->{
            String user=attempt.userId();lockUser(user);expireRuns(user);
            var runs=db.queryForList("SELECT user_id,billing_source,state,deadline_at FROM ai_model_usage_run WHERE run_id=?",attempt.runId());
            if(runs.size()!=1||!user.equals(runs.get(0).get("user_id")))throw new SecurityException("模型请求没有已登记的运行归属");
            var run=runs.get(0);
            if(Set.of("CANCELLED","EXHAUSTED","EXPIRED").contains(run.get("state")))throw new RunBudgetExceededException("运行已停止，未发送模型请求");
            // COMPLETED permits only the in-memory registry's captured maintenance scope; its guard already checked the permit.
            String source=source(attempt,"SYSTEM".equals(run.get("billing_source")));
            LocalDate day=LocalDate.now(clock);
            if("PLATFORM".equals(source)) {
                db.update("INSERT INTO ai_model_usage_day(user_id,usage_date) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)",user,day);
                var usage=db.queryForMap("SELECT platform_calls,platform_reserved_tokens FROM ai_model_usage_day WHERE user_id=? AND usage_date=? FOR UPDATE",user,day);
                if(((Number)usage.get("platform_calls")).longValue()>=dailyCalls||requested>dailyTokens-((Number)usage.get("platform_reserved_tokens")).longValue())
                    throw new RunBudgetExceededException("今日平台试用额度不足，未发送模型请求；可在模型连接页查看用量或使用个人模型");
                db.update("UPDATE ai_model_usage_day SET platform_calls=platform_calls+1,platform_reserved_tokens=platform_reserved_tokens+? WHERE user_id=? AND usage_date=?",requested,user,day);
            }
            db.update("""
                INSERT INTO ai_model_usage_attempt(attempt_id,run_id,user_id,api_id,provider_host,model_name,purpose,billing_source,
                    usage_date,input_estimate,output_limit,reserved_tokens,usage_status,outcome,pricing_status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'PENDING','IN_FLIGHT','UNKNOWN')
                """,id,attempt.runId(),user,attempt.apiId(),attempt.providerHost(),attempt.model(),purpose(attempt.purpose()),source,
                    day,Math.max(0,attempt.inputEstimate()),Math.max(0,attempt.outputLimit()),requested);
        });
        return new Ticket() {
            private boolean settled;
            @Override public synchronized void settle(Long actual,String outcome) {
                if(settled)return;
                JdbcModelUsageAccounting.this.settle(id,attempt.userId(),actual,outcome);
                settled=true;
            }
        };
    }
    private String source(Attempt attempt,boolean system) {
        if(attempt.apiId()!=null&&!attempt.apiId().isBlank()) {
            var rows=db.queryForList("SELECT owner_user_id,secret_ref,status,base_url FROM ai_client_api WHERE api_id=?",attempt.apiId());
            if(rows.size()!=1||((Number)rows.get(0).get("status")).intValue()!=1)throw new SecurityException("模型连接已撤销或不存在");
            var row=rows.get(0);String secret=(String)row.get("secret_ref");
            if(!attempt.providerHost().equalsIgnoreCase(java.net.URI.create((String)row.get("base_url")).getHost()))throw new SecurityException("请求提供商与连接归属不一致");
            if(secret!=null&&!secret.isBlank()) {
                if(system||!attempt.userId().equals(row.get("owner_user_id")))throw new SecurityException("无权使用个人模型额度");
                var count=db.queryForObject("SELECT COUNT(*) FROM ai_model_secret WHERE secret_ref=? AND owner_user_id=? AND status=1",Long.class,secret,attempt.userId());
                if(count==null||count!=1)throw new SecurityException("个人模型密钥已撤销");
                return "PERSONAL";
            }
            if(!system && !attempt.userId().equals(row.get("owner_user_id"))
                    && db.queryForObject("SELECT COUNT(*) FROM ai_client_model WHERE api_id=? AND model_name=? AND platform_enabled=1 AND status=1",Long.class,attempt.apiId(),attempt.model())==0)
                throw new SecurityException("模型未开放给当前账号");
        } else if(!Set.of("EMBEDDING","RERANK").contains(purpose(attempt.purpose())))
            throw new SecurityException("模型请求缺少已配置的API归属");
        if(!system && Set.of("CHAT_HELPER","EMBEDDING","RERANK","EVAL_JUDGE").contains(purpose(attempt.purpose())))
            access.requirePlatformAuxiliary(attempt.userId(),attempt.purpose());
        return system?"SYSTEM":"PLATFORM";
    }
    private void settle(String id,String user,Long actual,String outcome) {
        transaction.executeWithoutResult(status->{
            lockUser(user);
            var rows=db.queryForList("SELECT * FROM ai_model_usage_attempt WHERE attempt_id=? AND user_id=? FOR UPDATE",id,user);
            if(rows.size()!=1)throw new IllegalStateException("用量记录不存在");
            var row=rows.get(0);if(!"PENDING".equals(row.get("usage_status")))return;
            Long known=actual!=null&&actual>=0?actual:null;
            String result=outcome!=null&&Set.of("SUCCESS","ERROR","CANCELLED","UNKNOWN").contains(outcome)?outcome:"UNKNOWN";
            db.update("UPDATE ai_model_usage_attempt SET actual_tokens=?,usage_status=?,outcome=?,settled_at=NOW() WHERE attempt_id=?",
                    known,known==null?"UNKNOWN":"KNOWN",result,id);
            if("PLATFORM".equals(row.get("billing_source"))) {
                long reserved=((Number)row.get("reserved_tokens")).longValue();
                db.update("UPDATE ai_model_usage_day SET platform_reserved_tokens=platform_reserved_tokens+?,confirmed_tokens=confirmed_tokens+?,unknown_calls=unknown_calls+? WHERE user_id=? AND usage_date=?",
                        known==null?0:known-reserved,known==null?0:known,known==null?1:0,user,row.get("usage_date"));
            }
        });
    }
    public Map<String,Object> overview(String user) {
        return overview(user,null);
    }
    public Map<String,Object> overview(String user,String requestedRun) {
        if(user==null||user.isBlank())throw new SecurityException("缺少用量查看身份");
        String run=requestedRun==null?null:requestedRun.trim();
        if(run!=null&&run.isEmpty())run=null;
        if(run!=null&&run.length()>128)throw new IllegalArgumentException("运行标识过长");
        final String filteredRun=run;
        return transaction.execute(status->{
            lockUser(user);expireRuns(user);LocalDate day=LocalDate.now(clock);
            var usage=db.queryForList("SELECT platform_calls AS platformCalls,platform_reserved_tokens AS platformReservedTokens,confirmed_tokens AS confirmedPlatformTokens FROM ai_model_usage_day WHERE user_id=? AND usage_date=?",user,day);
            var today=usage.isEmpty()?Map.<String,Object>of("platformCalls",0,"platformReservedTokens",0,"confirmedPlatformTokens",0):usage.get(0);
            String predicate=" WHERE user_id=?"+(filteredRun==null?"":" AND run_id=?");
            Object[] arguments=filteredRun==null?new Object[]{user}:new Object[]{user,filteredRun};
            var attempts=db.queryForList("""
                SELECT attempt_id AS attemptId,run_id AS runId,api_id AS apiId,provider_host AS provider,model_name AS model,
                    purpose,billing_source AS billingSource,reserved_tokens AS reservedTokens,actual_tokens AS actualTokens,
                    usage_status AS usageStatus,outcome,pricing_status AS pricingStatus,cost_amount AS costAmount,cost_currency AS currency,
                    create_time AS createdAt,settled_at AS settledAt FROM ai_model_usage_attempt
                """+predicate+" ORDER BY create_time DESC,attempt_id DESC LIMIT 100",arguments);
            Long running=db.queryForObject("SELECT COUNT(*) FROM ai_model_usage_run WHERE user_id=? AND state IN ('ACTIVE','WAITING_REVIEW')",Long.class,user);
            var result=new LinkedHashMap<String,Object>();
            result.put("day",day.toString());result.put("dayTimeZone","UTC");result.put("today",today);result.put("activeRuns",running);
            result.put("limits",Map.of("platformDailyTokens",dailyTokens,"platformDailyCalls",dailyCalls,"concurrentRuns",concurrentRuns));
            result.put("attempts",attempts);result.put("filteredRunId",filteredRun);
            if(filteredRun!=null){
                var aggregate=db.queryForMap("""
                    SELECT COUNT(*) AS calls,COALESCE(SUM(actual_tokens),0) AS confirmedTokens,
                      COALESCE(SUM(CASE WHEN usage_status='UNKNOWN' THEN 1 ELSE 0 END),0) AS unknownCalls,
                      COALESCE(SUM(CASE WHEN usage_status='PENDING' THEN 1 ELSE 0 END),0) AS pendingCalls
                    FROM ai_model_usage_attempt
                    """+predicate,arguments);
                var summary=new LinkedHashMap<String,Object>();
                for(String key:List.of("calls","confirmedTokens","unknownCalls","pendingCalls"))summary.put(key,aggregate.get(key));
                summary.put("billingSources",db.queryForList("SELECT DISTINCT billing_source FROM ai_model_usage_attempt"+predicate,String.class,arguments));
                summary.put("pricingStatus","UNKNOWN");summary.put("costAmount",null);
                result.put("runSummary",summary);
            }
            return result;
        });
    }
    private void lockUser(String user) {
        db.update("INSERT INTO ai_model_usage_account(user_id) VALUES(?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)",user);
        db.queryForObject("SELECT user_id FROM ai_model_usage_account WHERE user_id=? FOR UPDATE",String.class,user);
    }
    private void expireRuns(String user) {
        db.update("UPDATE ai_model_usage_run SET state='EXPIRED',update_time=NOW() WHERE user_id=? AND deadline_at<=? AND state IN ('ACTIVE','WAITING_REVIEW')",user,Timestamp.from(clock.instant()));
        var abandoned=db.queryForList("""
            SELECT a.usage_date,COUNT(*) AS calls FROM ai_model_usage_attempt a JOIN ai_model_usage_run r ON r.run_id=a.run_id
            WHERE a.user_id=? AND a.billing_source='PLATFORM' AND a.usage_status='PENDING' AND r.deadline_at<=? GROUP BY a.usage_date
            """,user,Timestamp.from(clock.instant()));
        for(var day:abandoned)db.update("UPDATE ai_model_usage_day SET unknown_calls=unknown_calls+? WHERE user_id=? AND usage_date=?",day.get("calls"),user,day.get("usage_date"));
        db.update("""
            UPDATE ai_model_usage_attempt SET usage_status='UNKNOWN',outcome='ABANDONED',settled_at=NOW()
            WHERE user_id=? AND usage_status='PENDING' AND EXISTS
              (SELECT 1 FROM ai_model_usage_run r WHERE r.run_id=ai_model_usage_attempt.run_id AND r.deadline_at<=?)
            """,user,Timestamp.from(clock.instant()));
    }
    private static String purpose(String value){return value==null||value.isBlank()?"MAIN":value;}
    private static void identity(String run,String user){if(run==null||run.isBlank()||run.length()>128||user==null||user.isBlank()||user.length()>64)throw new SecurityException("运行用量身份无效");}
}
