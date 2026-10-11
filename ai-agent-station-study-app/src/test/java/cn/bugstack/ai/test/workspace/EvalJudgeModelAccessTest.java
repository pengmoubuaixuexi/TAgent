package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.*;
import cn.bugstack.ai.trigger.eval.EvalJudgeService;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** In-memory metadata and mocked providers only; these tests never send a model request. */
public class EvalJudgeModelAccessTest {
    private JdbcTemplate db;
    private EvalJudgeService service;
    private ModelProviderAccess access;
    private IAiEvalOpsDao dao;
    private IAgentRepository repository;

    @Before public void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE ai_client_model(api_id VARCHAR,model_name VARCHAR,status INT,platform_enabled INT,owner_user_id VARCHAR)");
        db.execute("CREATE TABLE ai_client_api(api_id VARCHAR,secret_ref VARCHAR,status INT,owner_user_id VARCHAR,base_url VARCHAR,api_key VARCHAR)");
        db.execute("CREATE TABLE ai_model_secret(secret_ref VARCHAR,api_id VARCHAR,owner_user_id VARCHAR,status INT,display_name VARCHAR,ciphertext VARCHAR)");
        db.update("""
                INSERT INTO ai_client_api VALUES
                ('platform',NULL,1,'admin','https://platform.example/v1','SYNTHETIC_PLATFORM_KEY'),
                ('legacy',NULL,1,'alice','https://url-user:URL_PASSWORD@deepseek.example/private-path?token=URL_TOKEN','SYNTHETIC_LEGACY_KEY'),
                ('alice-api','alice-secret',1,'alice','https://byok.example/v1','managed'),
                ('bob-api','bob-secret',1,'bob','https://byok.example/v1','managed')
                """);
        db.update("""
                INSERT INTO ai_model_secret VALUES
                ('alice-secret','alice-api','alice',1,'Alice DeepSeek','SYNTHETIC_CIPHERTEXT'),
                ('bob-secret','bob-api','bob',1,'Bob DeepSeek','SYNTHETIC_BOB_CIPHERTEXT')
                """);
        db.update("""
                INSERT INTO ai_client_model VALUES
                ('platform','public',1,1,'admin'),('platform','private',1,0,'admin'),
                ('legacy','deepseek-v4-flash',1,0,'alice'),('legacy','deepseek-v4-flash',1,0,'alice'),
                ('legacy','deepseek-v4-flash',1,0,'alice'),('legacy','foreign-model',1,0,'bob'),
                ('alice-api','personal',1,0,'alice'),('bob-api','personal',1,0,'bob')
                """);
        dao = mock(IAiEvalOpsDao.class);
        repository = mock(IAgentRepository.class);
        access = mock(ModelProviderAccess.class);
        service = new EvalJudgeService(dao, repository);
        ReflectionTestUtils.setField(service, "modelDb", db);
        ReflectionTestUtils.setField(service, "modelAccess", access);
        when(dao.findRun("run-1")).thenReturn(AiEvalRun.builder().evalRunId("run-1")
                .datasetId("dataset-1").userId("eval-isolated-user").status("RULE_COMPLETED").build());
        when(dao.findDataset("dataset-1")).thenReturn(AiEvalDataset.builder()
                .datasetId("dataset-1").ownerUserId("alice").build());
        when(dao.listJudgeJobs("run-1")).thenReturn(List.of());
    }

    @After public void cleanup() { service.shutdown(); }

    private void check(String user, String api, String model) {
        ReflectionTestUtils.invokeMethod(service, "requireModelAccess", user, api, model);
    }

    @Test public void personalJudgeCannotUseAnotherOwnersConnectionOrHiddenPlatformModel() {
        assertThrows(SecurityException.class, () -> check("alice", "bob-api", "personal"));
        assertThrows(SecurityException.class, () -> check("alice", "platform", "private"));
        assertThrows(SecurityException.class, () -> check("alice", "legacy", "foreign-model"));
        verifyNoInteractions(access);
        check("alice", "alice-api", "personal");
        verify(access).apiKeyForRequest("alice-api", "alice");
        verify(access, never()).requirePlatformAuxiliary(anyString(), anyString());
    }

    @Test public void legacyOwnerCanUsePrivateModelWithoutPublishingIt() {
        check("alice", "legacy", "deepseek-v4-flash");
        verify(access).apiKeyForRequest("legacy", "alice");
        verify(access).requirePlatformAuxiliary("alice", "EVAL_JUDGE");
        assertEquals(Integer.valueOf(0), db.queryForObject(
                "SELECT MAX(platform_enabled) FROM ai_client_model WHERE api_id='legacy'", Integer.class));
        assertThrows(SecurityException.class, () -> check("bob", "legacy", "deepseek-v4-flash"));
        assertThrows(SecurityException.class, () -> check("admin", "legacy", "deepseek-v4-flash"));
    }

    @Test public void legacyAndPlatformModelsKeepAuxiliaryConsentPolicy() {
        doThrow(new SecurityException("consent required")).when(access).requirePlatformAuxiliary("alice", "EVAL_JUDGE");
        assertThrows(SecurityException.class, () -> check("alice", "platform", "public"));
        assertThrows(SecurityException.class, () -> check("alice", "legacy", "deepseek-v4-flash"));
        assertEquals(List.of("BYOK"), service.listJudgeModels("run-1").stream().map(m -> m.get("source")).toList());
        verify(access, never()).apiKeyForRequest(anyString(), anyString());
    }

    @Test public void modelAndConnectionRevocationApplyToBothSelectionAndCreation() {
        check("alice", "legacy", "deepseek-v4-flash");
        db.update("UPDATE ai_client_model SET status=0 WHERE api_id='legacy'");
        assertThrows(SecurityException.class, () -> check("alice", "legacy", "deepseek-v4-flash"));
        assertFalse(hasApi("legacy"));
        db.update("UPDATE ai_client_model SET status=1 WHERE api_id='legacy'");
        db.update("UPDATE ai_client_api SET status=0 WHERE api_id='legacy'");
        assertThrows(SecurityException.class, () -> check("alice", "legacy", "deepseek-v4-flash"));
        assertFalse(hasApi("legacy"));
        db.update("UPDATE ai_client_model SET platform_enabled=0 WHERE model_name='public'");
        assertThrows(SecurityException.class, () -> check("alice", "platform", "public"));
        assertFalse(hasApi("platform"));
    }

    @Test public void revokedOrForeignByokSecretIsExcluded() {
        db.update("UPDATE ai_model_secret SET status=0 WHERE secret_ref='alice-secret'");
        assertThrows(SecurityException.class, () -> check("alice", "alice-api", "personal"));
        assertFalse(hasApi("alice-api"));
        db.update("UPDATE ai_model_secret SET status=1,owner_user_id='bob' WHERE secret_ref='alice-secret'");
        assertThrows(SecurityException.class, () -> check("alice", "alice-api", "personal"));
        assertFalse(hasApi("alice-api"));
    }

    @Test public void catalogUsesDatasetOwnerDeduplicatesAndReturnsOnlySafeMetadata() {
        List<Map<String, Object>> models = service.listJudgeModels("run-1");
        assertEquals(3, models.size());
        assertEquals(1, models.stream().filter(m -> "legacy".equals(m.get("apiId"))).count());
        for (Map<String, Object> model : models) {
            assertEquals(Set.of("modelName", "apiId", "apiName", "source"), model.keySet());
        }
        Map<String, Object> legacy = models.stream().filter(m -> "legacy".equals(m.get("apiId"))).findFirst().orElseThrow();
        assertEquals("deepseek-v4-flash", legacy.get("modelName"));
        assertEquals("OWNED", legacy.get("source"));
        assertEquals("自有连接 · deepseek.example", legacy.get("apiName"));
        assertEquals("Alice DeepSeek", models.stream().filter(m -> "BYOK".equals(m.get("source")))
                .findFirst().orElseThrow().get("apiName"));
        String output = models.toString();
        for (String forbidden : List.of("SYNTHETIC_", "URL_PASSWORD", "URL_TOKEN", "url-user", "private-path", "alice-secret", "base_url")) {
            assertFalse(output, output.contains(forbidden));
        }
        verify(access, never()).apiKeyForRequest(anyString(), anyString());
        verify(access, never()).requirePlatformAuxiliary("eval-isolated-user", "EVAL_JUDGE");
    }

    @Test public void identicalNamesOnDifferentConnectionsRemainSelectable() {
        db.update("INSERT INTO ai_client_model VALUES('legacy','personal',1,0,'alice')");
        assertEquals(2, service.listJudgeModels("run-1").stream()
                .filter(m -> "personal".equals(m.get("modelName"))).count());
        assertThrows(SecurityException.class, () -> check("alice", "platform", "personal"));
    }

    @Test public void creationUsesDatasetOwnerInsteadOfIsolatedMemoryUser() {
        Map<String, Object> job = createWithoutDispatch(command("legacy", "deepseek-v4-flash"));
        assertEquals("legacy", job.get("apiId"));
        assertEquals("deepseek-v4-flash", job.get("modelId"));
        verify(access).apiKeyForRequest("legacy", "alice");
        verify(access, never()).apiKeyForRequest(anyString(), eq("eval-isolated-user"));
        verify(dao).insertJudgeJob(any(AiEvalJudgeJob.class));
    }

    @Test public void missingSelectionAndMissingDatasetOwnerFailBeforeQueueing() {
        assertEquals("请选择 Judge 模型", assertThrows(IllegalArgumentException.class,
                () -> service.createJudgeJob("run-1", null)).getMessage());
        assertEquals("请选择 Judge 模型连接", assertThrows(IllegalArgumentException.class,
                () -> service.createJudgeJob("run-1", command(" ", "deepseek-v4-flash"))).getMessage());
        when(dao.findDataset("dataset-1")).thenReturn(AiEvalDataset.builder().ownerUserId(" ").build());
        assertThrows(IllegalArgumentException.class, () -> service.listJudgeModels("run-1"));
        assertThrows(IllegalArgumentException.class, () -> service.createJudgeJob("run-1", command("legacy", "deepseek-v4-flash")));
        verify(dao, never()).insertJudgeJob(any(AiEvalJudgeJob.class));
    }

    @Test public void workerRechecksAccessWithDatasetOwnerAndUsesThatBudgetIdentity() {
        RunBudgetRegistry budgets = mock(RunBudgetRegistry.class);
        ReflectionTestUtils.setField(service, "runBudgets", budgets);
        AiEvalJudgeJob job = AiEvalJudgeJob.builder().judgeJobId("job-1").evalRunId("run-1")
                .scopeType("ALL").apiId("legacy").modelId("deepseek-v4-flash").configJson("{\"concurrency\":1}").build();
        when(dao.findJudgeJob("job-1")).thenReturn(job);
        when(dao.listCaseResults("run-1")).thenReturn(List.of(AiEvalCaseResult.builder()
                .resultId("result-1").status("PASS").finalAnswer("synthetic answer").build()));
        when(repository.queryAiClientApiVOListByApiIds(List.of("legacy")))
                .thenReturn(List.of(AiClientApiVO.builder().apiId("legacy").build()));
        AtomicReference<String> invocationOwner = new AtomicReference<>();
        doAnswer(invocation -> {
            invocationOwner.set(ModelInvocationScope.currentUser());
            throw new SecurityException("synthetic revocation before network");
        }).when(access).apiKeyForRequest("legacy", "alice");

        ReflectionTestUtils.invokeMethod(service, "executeJob", "job-1");

        assertEquals("alice", invocationOwner.get());
        verify(budgets).start(startsWith("model:EVAL_JUDGE:"), eq("alice"));
        verify(access).apiKeyForRequest("legacy", "alice");
        ArgumentCaptor<AiEvalJudgeResult> failure = ArgumentCaptor.forClass(AiEvalJudgeResult.class);
        verify(dao).insertJudgeResult(failure.capture());
        assertEquals("FAILED", failure.getValue().getStatus());
        assertEquals("synthetic revocation before network", failure.getValue().getErrorMessage());
        verify(dao, never()).findCase(anyString());
    }

    private boolean hasApi(String apiId) {
        return service.listJudgeModels("run-1").stream().anyMatch(model -> apiId.equals(model.get("apiId")));
    }

    private EvalJudgeService.JudgeCommand command(String apiId, String model) {
        EvalJudgeService.JudgeCommand command = new EvalJudgeService.JudgeCommand();
        command.setApiId(apiId);
        command.setModelId(model);
        return command;
    }

    private Map<String, Object> createWithoutDispatch(EvalJudgeService.JudgeCommand command) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            return service.createJudgeJob("run-1", command);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
