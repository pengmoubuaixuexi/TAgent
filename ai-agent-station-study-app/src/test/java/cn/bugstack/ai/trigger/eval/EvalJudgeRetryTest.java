package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.*;
import com.alibaba.fastjson.JSON;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Uses synthetic persisted results, without any provider request. */
public class EvalJudgeRetryTest {
    private IAiEvalOpsDao dao;
    private EvalJudgeService service;
    private AiEvalRun run;

    @Before public void setup() {
        dao = mock(IAiEvalOpsDao.class);
        service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        run = AiEvalRun.builder().evalRunId("run").datasetId("dataset").status("COMPLETED").build();
        when(dao.findRun("run")).thenReturn(run);
        when(dao.findDataset("dataset")).thenReturn(AiEvalDataset.builder().ownerUserId("alice").build());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @After public void cleanup() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        service.shutdown();
    }

    private AiEvalJudgeJob job(String id, String scope, String base) {
        AiEvalJudgeJob job = AiEvalJudgeJob.builder().judgeJobId(id).evalRunId("run")
                .modelId("deepseek").apiId("api").rubricVersion("semantic-v2-full-trace")
                .scopeType(scope).status("PARTIAL").configJson(base == null ? "{}"
                        : JSON.toJSONString(Map.of("baseJudgeJobId", base))).build();
        when(dao.findJudgeJob(id)).thenReturn(job);
        return job;
    }

    private AiEvalJudgeResult result(String job, String id, Integer score) {
        return AiEvalJudgeResult.builder().judgeJobId(job).resultId(id)
                .status(score == null ? "FAILED" : "COMPLETED").overall(score).build();
    }

    @Test public void retryKeepsNinetyNineScoresAndQueuesOnlyTheFailedQuestion() {
        var base = job("base", "ALL", null);
        List<AiEvalJudgeResult> prior = new ArrayList<>();
        IntStream.rangeClosed(1, 100).forEach(i -> prior.add(result("base", "r" + i, i == 46 ? null : 80)));
        when(dao.listJudgeResults("base")).thenReturn(prior);
        when(dao.listJudgeJobs("run")).thenReturn(List.of(base));

        service.retryFailed("base");
        ArgumentCaptor<AiEvalJudgeJob> captor = ArgumentCaptor.forClass(AiEvalJudgeJob.class);
        verify(dao).insertJudgeJob(captor.capture());
        var retry = captor.getValue();
        var config = JSON.parseObject(retry.getConfigJson());
        assertEquals(List.of("r46"), config.getJSONArray("selectedResultIds").toJavaList(String.class));
        assertEquals("base", config.getString("baseJudgeJobId"));
        assertEquals("base", config.getString("retryOfJudgeJobId"));
        assertEquals("deepseek", retry.getModelId());
        assertEquals("api", retry.getApiId());
        assertEquals(base.getRubricVersion(), retry.getRubricVersion());

        when(dao.listJudgeResults(retry.getJudgeJobId())).thenReturn(List.of(result(retry.getJudgeJobId(), "r46", 100)));
        assertEquals(100, service.effectiveResults(retry).size());
        service.updateRunSummary(retry, run);
        verify(dao).updateRunJudgeScore("run", new BigDecimal("80.20"), "COMPLETED");
        assertNull(prior.get(45).getOverall()); // immutable history remains failed
    }

    @Test public void failedRetryPreservesBaselineAndCanBeRetriedAgain() {
        var base = job("base", "ALL", null);
        var retry = job("retry", "SELECTED", "base");
        when(dao.listJudgeResults("base")).thenReturn(List.of(result("base", "a", 90), result("base", "b", null)));
        when(dao.listJudgeResults("retry")).thenReturn(List.of(result("retry", "b", null)));
        when(dao.listJudgeJobs("run")).thenReturn(List.of(retry, base));
        service.retryFailed("retry");
        ArgumentCaptor<AiEvalJudgeJob> captor = ArgumentCaptor.forClass(AiEvalJudgeJob.class);
        verify(dao).insertJudgeJob(captor.capture());
        assertEquals(List.of("b"), JSON.parseObject(captor.getValue().getConfigJson())
                .getJSONArray("selectedResultIds").toJavaList(String.class));
        service.updateRunSummary(retry, run);
        verify(dao).updateRunJudgeScore("run", new BigDecimal("90.00"), "COMPLETED");
    }

    @Test public void selectedRejudgeDoesNotErasePreviousSuccessOnFailure() {
        job("base", "ALL", null);
        var retry = job("retry", "SELECTED", "base");
        when(dao.listJudgeResults("base")).thenReturn(List.of(result("base", "a", 90)));
        when(dao.listJudgeResults("retry")).thenReturn(List.of(result("retry", "a", null)));
        assertEquals(Integer.valueOf(90), service.effectiveResults(retry).get(0).getOverall());
    }

    @Test public void standaloneSubsetNeverOverwritesRunAverage() {
        var subset = job("subset", "SELECTED", null);
        when(dao.listJudgeResults("subset")).thenReturn(List.of(result("subset", "r46", 100)));
        service.updateRunSummary(subset, run);
        verify(dao, never()).updateRunJudgeScore(anyString(), any(), anyString());
        verify(dao).updateRunStatus("run", "COMPLETED", null);
    }

    @Test public void selectedJobAutomaticallyUsesSameModelBaselineOnly() {
        var foreign = job("other-model", "ALL", null); foreign.setModelId("another-model");
        var base = job("base", "ALL", null);
        when(dao.listJudgeJobs("run")).thenReturn(List.of(foreign, base));
        var command = new EvalJudgeService.JudgeCommand();
        command.setScopeType("SELECTED"); command.setModelId("deepseek"); command.setApiId("api");
        command.setSelectedResultIds(List.of("r46"));
        service.createJudgeJob("run", command);
        ArgumentCaptor<AiEvalJudgeJob> captor = ArgumentCaptor.forClass(AiEvalJudgeJob.class);
        verify(dao).insertJudgeJob(captor.capture());
        assertEquals("base", JSON.parseObject(captor.getValue().getConfigJson()).getString("baseJudgeJobId"));
    }

    @Test public void rejectsCrossRunModelApiAndRubricLineage() {
        for (String difference : List.of("run", "api", "model", "rubric")) {
            var base = job("base", "ALL", null);
            switch (difference) {
                case "run" -> base.setEvalRunId("other-run");
                case "api" -> base.setApiId("other-api");
                case "model" -> base.setModelId("other-model");
                case "rubric" -> base.setRubricVersion("other-rubric");
            }
            var retry = job("retry", "SELECTED", "base");
            assertThrows(IllegalArgumentException.class, () -> service.effectiveResults(retry));
        }
    }

    @Test public void rejectsCyclesAndObsoleteRetryBranches() {
        var base = job("base", "ALL", "retry");
        var retry = job("retry", "SELECTED", "base");
        assertThrows(IllegalArgumentException.class, () -> service.effectiveResults(retry));
        base.setConfigJson("{}");
        when(dao.listJudgeJobs("run")).thenReturn(List.of(retry, base));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.retryFailed("base"))
                .getMessage().contains("更新"));
        verify(dao, never()).insertJudgeJob(any());
    }

    @Test public void refusesEmptyOrRunningRetry() {
        var base = job("base", "ALL", null);
        when(dao.listJudgeJobs("run")).thenReturn(List.of(base));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.retryFailed("base"))
                .getMessage().contains("没有"));
        base.setStatus("RUNNING");
        assertThrows(IllegalArgumentException.class, () -> service.retryFailed("base"));
        verify(dao, never()).insertJudgeJob(any());
    }

    @Test public void activeJobBlocksAnotherRetryBeforeInsertion() {
        var base = job("base", "ALL", null);
        var active = job("active", "ALL", null); active.setStatus("RUNNING");
        when(dao.listJudgeResults("base")).thenReturn(List.of(result("base", "b", null)));
        when(dao.listJudgeJobs("run")).thenReturn(List.of(active, base));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.retryFailed("base"))
                .getMessage().contains("正在执行"));
        verify(dao, never()).insertJudgeJob(any());
        verify(dao, atLeastOnce()).lockRunForJudge("run");
    }

    @Test public void detailKeepsMergedScoresAndSeparateFailedAttemptDiagnostics() {
        job("base", "ALL", null);
        var retry = job("retry", "SELECTED", "base");
        retry.setTotalCases(1);
        when(dao.listJudgeResults("base")).thenReturn(List.of(result("base", "a", 80), result("base", "b", 90)));
        var failedAttempt = result("retry", "a", null);
        failedAttempt.setRawResponse("{\"type\":\"JUDGE_DIAGNOSTICS\",\"code\":\"EMPTY_CONTENT\"}");
        when(dao.listJudgeResults("retry")).thenReturn(List.of(failedAttempt));
        var detail = service.getJob("retry");
        assertEquals(2, detail.get("effectiveTotalCases"));
        assertEquals(2L, detail.get("effectiveCompletedCases"));
        assertEquals(new BigDecimal("85.00"), detail.get("effectiveAverageScore"));
        assertEquals(2, ((List<?>) detail.get("results")).size());
        assertTrue(JSON.toJSONString(detail.get("attemptResults")).contains("EMPTY_CONTENT"));
    }

    @Test public void workerPersistsSafeFailureDiagnostics() throws Exception {
        var target = spy(service);
        var base = job("base", "ALL", null);
        when(dao.listCaseResults("run")).thenReturn(List.of(AiEvalCaseResult.builder()
                .resultId("r46").caseId("case").status("PASS").finalAnswer("answer").traceJson("{}").build()));
        when(dao.findCase("case")).thenReturn(AiEvalCase.builder().question("question").configJson("{}").build());
        var repo = mock(IAgentRepository.class);
        ReflectionTestUtils.setField(target, "agentRepository", repo);
        when(repo.queryAiClientApiVOListByApiIds(List.of("api")))
                .thenReturn(List.of(AiClientApiVO.builder().apiId("api").build()));
        var failure = new EvalJudgeResponseSupport.JudgeFailure("EMPTY_CONTENT", "empty scoring content")
                .withDiagnostics("run-fixture", "base", "r46", List.of(Map.of("contentLength", 0)));
        doThrow(failure).when(target).call(any(), eq("deepseek"), anyString(), eq("base"), eq("r46"));
        ReflectionTestUtils.invokeMethod(target, "executeJob", "base");
        ArgumentCaptor<AiEvalJudgeResult> captor = ArgumentCaptor.forClass(AiEvalJudgeResult.class);
        verify(dao).insertJudgeResult(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getRawResponse().contains("EMPTY_CONTENT"));
        assertTrue(captor.getValue().getRawResponse().contains("r46"));
    }
}
