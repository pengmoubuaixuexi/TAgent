package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeJob;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transactions and database locks, without starting workers or calling a provider. */
public class EvalJudgeRetryConcurrencyTest {
    @Test(timeout = 15000)
    public void concurrentRetriesSeeThePreviousLockOwnersCommittedJob() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "") {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                // Match MySQL's default so removal of the explicit override fails this test.
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            }
        };
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE ai_eval_run(eval_run_id VARCHAR PRIMARY KEY, status VARCHAR)");
        db.execute("CREATE TABLE ai_eval_judge_job(id BIGINT AUTO_INCREMENT PRIMARY KEY, judge_job_id VARCHAR,"
                + "eval_run_id VARCHAR, status VARCHAR, scope_type VARCHAR, model_id VARCHAR, api_id VARCHAR,"
                + "rubric_version VARCHAR, config_json VARCHAR)");
        db.update("INSERT INTO ai_eval_run VALUES ('run','COMPLETED')");
        db.update("INSERT INTO ai_eval_judge_job(judge_job_id,eval_run_id,status,scope_type,model_id,api_id,"
                + "rubric_version,config_json) VALUES ('base','run','PARTIAL','ALL','deepseek','api',"
                + "'semantic-v2-full-trace','{}')");

        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        var bothReadParent = new CountDownLatch(2);
        when(dao.findJudgeJob("base")).thenAnswer(invocation -> {
            var parent = db.queryForObject("SELECT * FROM ai_eval_judge_job WHERE judge_job_id='base'",
                    EvalJudgeRetryConcurrencyTest::readJob);
            // Both requests must read their parent before competing for the run lock. This is
            // the ordering that made a repeatable-read transaction retain the obsolete snapshot.
            bothReadParent.countDown();
            assertTrue("Both requests must reach the initial parent read", bothReadParent.await(5, TimeUnit.SECONDS));
            return parent;
        });
        when(dao.lockRunForJudge("run")).thenAnswer(invocation -> {
            // Verify the actual connection isolation, not only the presence of an annotation.
            db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
                return null;
            });
            return db.queryForObject("SELECT eval_run_id FROM ai_eval_run WHERE eval_run_id='run' FOR UPDATE", String.class);
        });
        when(dao.findRun("run")).thenAnswer(invocation -> AiEvalRun.builder().evalRunId("run")
                .datasetId("dataset").status(db.queryForObject(
                        "SELECT status FROM ai_eval_run WHERE eval_run_id='run'", String.class)).build());
        when(dao.listJudgeJobs("run")).thenAnswer(invocation -> db.query(
                "SELECT * FROM ai_eval_judge_job WHERE eval_run_id='run' ORDER BY id DESC",
                EvalJudgeRetryConcurrencyTest::readJob));
        when(dao.findDataset("dataset")).thenReturn(AiEvalDataset.builder().ownerUserId("alice").build());
        when(dao.listJudgeResults("base")).thenReturn(List.of(AiEvalJudgeResult.builder()
                .judgeJobId("base").resultId("r46").status("FAILED").build()));
        when(dao.insertJudgeJob(any())).thenAnswer(invocation -> {
            AiEvalJudgeJob job = invocation.getArgument(0);
            return db.update("INSERT INTO ai_eval_judge_job(judge_job_id,eval_run_id,status,scope_type,model_id,"
                            + "api_id,rubric_version,config_json) VALUES (?,?,?,?,?,?,?,?)",
                    job.getJudgeJobId(), job.getEvalRunId(), job.getStatus(), job.getScopeType(),
                    job.getModelId(), job.getApiId(), job.getRubricVersion(), job.getConfigJson());
        });
        when(dao.updateRunStatus(eq("run"), anyString(), isNull())).thenAnswer(invocation ->
                db.update("UPDATE ai_eval_run SET status=? WHERE eval_run_id='run'", invocation.<String>getArgument(1)));

        var target = new EvalJudgeService(dao, mock(IAgentRepository.class));
        var coordinator = mock(ExecutorService.class);
        ReflectionTestUtils.setField(target, "coordinators", coordinator);
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new DataSourceTransactionManager(source));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactions);
        var service = (EvalJudgeService) proxyFactory.getProxy();
        var requests = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = requests.submit(() -> tryRetry(service));
            Future<Boolean> second = requests.submit(() -> tryRetry(service));
            int succeeded = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals("Exactly one request may create the retry", 1, succeeded);
            assertEquals(Integer.valueOf(1), db.queryForObject(
                    "SELECT COUNT(*) FROM ai_eval_judge_job WHERE status='QUEUED'", Integer.class));
            assertEquals(Integer.valueOf(2), db.queryForObject(
                    "SELECT COUNT(*) FROM ai_eval_judge_job", Integer.class));
            verify(dao, times(1)).insertJudgeJob(any());
            verify(coordinator, times(1)).execute(any(Runnable.class));
        } finally {
            requests.shutdownNow();
            target.shutdown();
            db.execute("DROP ALL OBJECTS");
        }
    }

    private static boolean tryRetry(EvalJudgeService service) {
        try {
            service.retryFailed("base");
            return true;
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("规则评测完成")
                    || expected.getMessage().contains("正在执行") || expected.getMessage().contains("更新"));
            return false;
        }
    }

    private static AiEvalJudgeJob readJob(ResultSet row, int index) throws SQLException {
        return AiEvalJudgeJob.builder().judgeJobId(row.getString("judge_job_id"))
                .evalRunId(row.getString("eval_run_id")).status(row.getString("status"))
                .scopeType(row.getString("scope_type")).modelId(row.getString("model_id"))
                .apiId(row.getString("api_id")).rubricVersion(row.getString("rubric_version"))
                .configJson(row.getString("config_json")).build();
    }
}
