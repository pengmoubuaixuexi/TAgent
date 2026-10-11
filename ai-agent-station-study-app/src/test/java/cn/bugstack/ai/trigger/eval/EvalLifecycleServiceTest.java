package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunStepSnapshot;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCaseResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeJob;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

public class EvalLifecycleServiceTest {

    @Test
    public void shouldCancelRunningAgentAndPreserveRunRecord() throws Exception {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        AiEvalRun run = AiEvalRun.builder().evalRunId("run-1").status("RUNNING").build();
        AiEvalCaseResult result = AiEvalCaseResult.builder().status("RUNNING")
                .sessionId("session-1").agentRunId("agent-run-1").build();
        when(dao.findRun("run-1")).thenReturn(run);
        when(dao.listCaseResults("run-1")).thenReturn(List.of(result));

        EvalRunService service = runService(dao, dispatch);
        try {
            service.cancelRun("run-1");
        } finally {
            service.shutdown();
        }

        verify(dispatch).cancelExecute("session-1", "agent-run-1");
        verify(dao).cancelPendingCaseResults(eq("run-1"), any(LocalDateTime.class), eq("用户中断评测"));
        verify(dao).finishRuleRun(eq("run-1"), eq("CANCELLED"), any(LocalDateTime.class), eq("用户中断评测"));
    }

    @Test
    public void shouldCancelActiveJudgeJob() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        when(dao.findRun("run-1")).thenReturn(AiEvalRun.builder().evalRunId("run-1").status("JUDGING").build());
        when(dao.listJudgeJobs("run-1")).thenReturn(List.of(
                AiEvalJudgeJob.builder().judgeJobId("judge-1").evalRunId("run-1").status("RUNNING").build()));
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        try {
            service.cancelRun("run-1");
        } finally {
            service.shutdown();
        }

        verify(dao).cancelJudgeJobsByRun(eq("run-1"), any(LocalDateTime.class), eq("用户中断评测"));
    }

    @Test
    public void shouldRestoreRuleCompletedWhenCancellingJudge() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        when(dao.findRun("run-1")).thenReturn(
                AiEvalRun.builder().evalRunId("run-1").status("JUDGING").build(),
                AiEvalRun.builder().evalRunId("run-1").status("RULE_COMPLETED").build());
        EvalRunService service = runService(dao, mock(IAgentDispatchService.class));
        try {
            service.cancelRun("run-1");
        } finally {
            service.shutdown();
        }

        verify(dao).updateRunStatus("run-1", "RULE_COMPLETED", "LLM Judge 已中断，可重新发起");
        verify(dao, never()).cancelPendingCaseResults(eq("run-1"), any(LocalDateTime.class), any(String.class));
        verify(dao, never()).finishRuleRun(eq("run-1"), eq("CANCELLED"), any(LocalDateTime.class), any(String.class));
    }

    @Test
    public void shouldCascadeCompletedRunsBeforeDeletingDataset() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        when(dao.findDataset("dataset-1")).thenReturn(
                AiEvalDataset.builder().datasetId("dataset-1").name("dataset").build());
        when(dao.deleteDataset("dataset-1")).thenReturn(1);
        EvalDatasetService service = new EvalDatasetService(dao);

        service.deleteDataset("dataset-1");

        verify(dao).deleteRunsByDataset("dataset-1");
        verify(dao).deleteDataset("dataset-1");
    }

    @Test
    public void shouldIncludeReferenceAnswerInJudgePrompt() {
        EvalJudgeService service = new EvalJudgeService(mock(IAiEvalOpsDao.class), mock(IAgentRepository.class));
        try {
            AiEvalCase evalCase = AiEvalCase.builder().question("240元打85折是多少？")
                    .referenceAnswer("240×0.85=204元").configJson("{}").build();
            AiEvalCaseResult result = AiEvalCaseResult.builder().finalAnswer("答案是204元").build();

            String prompt = service.buildPrompt(evalCase, result, "", "");

            org.junit.Assert.assertTrue(prompt.contains("【参考答案/判定要点】"));
            org.junit.Assert.assertTrue(prompt.contains("240×0.85=204元"));
            org.junit.Assert.assertTrue(prompt.contains("不要求逐字匹配"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shouldRestoreFullTraceEvidenceInJudgePrompt() {
        EvalJudgeService service = new EvalJudgeService(mock(IAiEvalOpsDao.class), mock(IAgentRepository.class));
        try {
            AiEvalCase evalCase = AiEvalCase.builder().question("根据此前信息给我建议")
                    .referenceAnswer("结合用户背景和知识库证据").configJson("{}").build();
            AiEvalCaseResult result = AiEvalCaseResult.builder().finalAnswer("结合召回信息给出的答案").build();

            String prompt = service.buildPrompt(evalCase, result,
                    "- 画像:职业：Java工程师", "- 工具[weather] 返回=晴",
                    "[对话历史摘要] 用户正在学习Python", "- RAG[1] 来源=学习资料 片段=学习路线",
                    "[需求分析]\n识别为学习规划", "2026-08-24 20:00:00.000");

            org.junit.Assert.assertTrue(prompt.contains("系统已注入的用户背景/记忆"));
            org.junit.Assert.assertTrue(prompt.contains("Java工程师"));
            org.junit.Assert.assertTrue(prompt.contains("对话历史摘要"));
            org.junit.Assert.assertTrue(prompt.contains("RAG[1]"));
            org.junit.Assert.assertTrue(prompt.contains("工具[weather]"));
            org.junit.Assert.assertTrue(prompt.contains("Agent 可观察步骤输出"));
            org.junit.Assert.assertTrue(prompt.contains("2026-08-24 20:00:00.000"));
            org.junit.Assert.assertTrue(prompt.contains("全部【同时成立】才算【编造/幻觉】"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shouldReadOldQuestionTraceEvidenceContract() {
        JSONObject trace = EvalJudgeService.parseTrace("""
                {
                  "startTime":"2026-07-02 20:00:00.000",
                  "stmSnapshot":{"available":true,"stmSummary":"用户正在学Python","conversationWindow":[{"role":"USER","content":"继续上次计划"}]},
                  "events":[
                    {"event":"memory_evidence","data":{"memoryType":"long_term","items":[{"topic":"画像:职业","content":"Java工程师"}]}},
                    {"event":"rag_evidence","data":{"items":[{"ref":1,"source":"学习资料","snippet":"Python学习路线"}]}},
                    {"event":"tool_call_end","data":{"toolName":"search","status":"success","resultPreview":"检索结果"}},
                    {"event":"thinking","data":{"title":"需求分析","content":"识别为学习规划"}}
                  ]
                }
                """);

        org.junit.Assert.assertTrue(EvalJudgeService.extractStmContext(trace).contains("用户正在学Python"));
        org.junit.Assert.assertTrue(EvalJudgeService.extractMemoryContext(trace).contains("Java工程师"));
        org.junit.Assert.assertTrue(EvalJudgeService.extractRagContext(trace).contains("Python学习路线"));
        org.junit.Assert.assertTrue(EvalJudgeService.extractToolContext(trace).contains("检索结果"));
        org.junit.Assert.assertTrue(EvalJudgeService.extractStepContext(trace).contains("识别为学习规划"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldExposeQuestionRuleAndRawJsonInJudgeResult() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        AiEvalJudgeJob job = AiEvalJudgeJob.builder().judgeJobId("judge-1").evalRunId("run-1")
                .status("COMPLETED").build();
        AiEvalCaseResult caseResult = AiEvalCaseResult.builder().resultId("result-1").caseId("case-1")
                .status("PASS").finalAnswer("答案是204元").agentId("8011").strategy("auto")
                .overallScore(new BigDecimal("0.95")).warningsJson("[\"观察\"]").signalsJson("{\"toolCallCount\":0}")
                .traceJson("{\"traceSchemaVersion\":\"eval-trace-v2-old-compatible\",\"events\":[]}").build();
        AiEvalCase evalCase = AiEvalCase.builder().caseId("case-1").stableKey("QB-01").sequenceNo(1)
                .question("240元打85折是多少？").referenceAnswer("204元").build();
        AiEvalJudgeResult verdict = AiEvalJudgeResult.builder().judgeResultId("verdict-1").judgeJobId("judge-1")
                .resultId("result-1").status("COMPLETED").overall(95).rawResponse("{\"overall\":95}").build();
        when(dao.findJudgeJob("judge-1")).thenReturn(job);
        when(dao.listCaseResults("run-1")).thenReturn(List.of(caseResult));
        when(dao.findRun("run-1")).thenReturn(AiEvalRun.builder().evalRunId("run-1").versionId("version-1").build());
        when(dao.listCases("version-1", false)).thenReturn(List.of(evalCase));
        when(dao.listJudgeResults("judge-1")).thenReturn(List.of(verdict));
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        try {
            Map<String, Object> view = service.getJob("judge-1");
            Map<String, Object> result = ((List<Map<String, Object>>) view.get("results")).get(0);

            org.junit.Assert.assertEquals("QB-01", result.get("stableKey"));
            org.junit.Assert.assertEquals("240元打85折是多少？", result.get("question"));
            org.junit.Assert.assertEquals("答案是204元", result.get("finalAnswer"));
            org.junit.Assert.assertTrue(result.get("ruleScores") instanceof Map);
            org.junit.Assert.assertTrue(result.get("rawResponse") instanceof Map);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shouldDispatchJudgeOnlyAfterTransactionCommit() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        when(dao.findRun("run-1")).thenReturn(
                AiEvalRun.builder().evalRunId("run-1").datasetId("dataset-1").status("RULE_COMPLETED").build());
        when(dao.findDataset("dataset-1")).thenReturn(
                AiEvalDataset.builder().datasetId("dataset-1").ownerUserId("owner-1").build());
        when(dao.listJudgeJobs("run-1")).thenReturn(List.of());
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createJudgeJob("run-1", selectedJudgeModel());

            org.junit.Assert.assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            verify(dao, never()).findJudgeJob(any(String.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            service.shutdown();
        }
    }

    @Test
    public void shouldRecoverLegacyCancelledJudgeRunWhenRulesAreComplete() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        AiEvalRun cancelled = AiEvalRun.builder().evalRunId("run-1").datasetId("dataset-1").status("CANCELLED")
                .totalCases(80).completedCases(80).build();
        AiEvalRun restored = AiEvalRun.builder().evalRunId("run-1").datasetId("dataset-1").status("RULE_COMPLETED")
                .totalCases(80).completedCases(80).build();
        when(dao.findRun("run-1")).thenReturn(cancelled, restored);
        when(dao.findDataset("dataset-1")).thenReturn(
                AiEvalDataset.builder().datasetId("dataset-1").ownerUserId("owner-1").build());
        when(dao.listJudgeJobs("run-1")).thenReturn(List.of());
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createJudgeJob("run-1", selectedJudgeModel());

            verify(dao).restoreRuleCompletedForJudgeRetry("run-1");
            verify(dao, never()).findJudgeJob(any(String.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            service.shutdown();
        }
    }

    private static EvalJudgeService.JudgeCommand selectedJudgeModel() {
        var command = new EvalJudgeService.JudgeCommand();
        command.setModelId("fixture-judge");
        command.setApiId("fixture-api");
        return command;
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void shouldPersistIncompleteEvidenceAndContinueFollowingScenarioCases() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        AiEvalRun run = diagnosticRun();
        AiEvalCase first = diagnosticCase("case-1", 1);
        AiEvalCase second = diagnosticCase("case-2", 2);
        when(dao.findRun("run-1")).thenReturn(run);
        when(dao.listCases("version-1", true)).thenReturn(List.of(first, second));
        when(dao.listCaseResults("run-1")).thenReturn(List.of(diagnosticResult("case-1"), diagnosticResult("case-2")));
        doAnswer(call -> { run.setCompletedCases(run.getCompletedCases() + 1); return 1; })
                .when(dao).finishCaseResult(any());
        RunSnapshot bad = completedSnapshot();
        Map<Object, Object> backing = new LinkedHashMap<>();
        backing.put(1, Set.of());
        backing.put("1", Set.of(2));
        JSONObject collision = new JSONObject((Map) backing);
        bad.setFlowPlanDependencies((Map) collision);
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find(any(String.class))).thenReturn(Optional.of(bad), Optional.of(completedSnapshot()));
        EvalRunService service = diagnosticService(dao, snapshots, new EvalRuleEngine());
        try {
            ReflectionTestUtils.invokeMethod(service, "executeRun", "run-1");
            ArgumentCaptor<AiEvalCaseResult> results = ArgumentCaptor.forClass(AiEvalCaseResult.class);
            verify(dao, times(2)).finishCaseResult(results.capture());
            AiEvalCaseResult failed = results.getAllValues().get(0);
            assertEquals("ERROR", failed.getStatus());
            assertNull(failed.getOverallScore());
            assertNull(failed.getAnswerScore());
            assertNull(failed.getGrade());
            assertEquals("已完成的答案", failed.getFinalAnswer());
            assertTrue(failed.getErrorMessage().contains("Trace 不完整"));
            JSONObject fallback = EvalTraceCodec.read(failed.getTraceJson());
            assertEquals("INCOMPLETE", fallback.getString("traceCompleteness"));
            assertEquals("PASS", fallback.getString("executionStatus"));
            assertFalse(fallback.containsKey("runSnapshot"));
            assertTrue(fallback.getJSONArray("omittedFields").contains("runSnapshot"));
            assertFalse(fallback.getJSONObject("traceError").getBooleanValue("originalTracePersisted"));
            assertThrows(IllegalArgumentException.class, () -> EvalJudgeService.parseTrace(failed.getTraceJson()));
            failed.setEvalRunId("run-1");
            when(dao.findCaseResult(failed.getResultId())).thenReturn(failed);
            Map<String, Object> detail = service.getResult("run-1", failed.getResultId());
            assertEquals(failed.getErrorMessage(), detail.get("traceError"));
            assertTrue(detail.get("trace") instanceof JSONObject);
            AiEvalCaseResult passed = results.getAllValues().get(1);
            assertEquals("PASS", passed.getStatus());
            assertNotNull(passed.getOverallScore());
            assertEquals("COMPLETE", EvalJudgeService.parseTrace(passed.getTraceJson()).getString("traceCompleteness"));
            verify(dao).finishRuleRun(eq("run-1"), eq("RULE_COMPLETED"), any(LocalDateTime.class), eq(null));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shouldPropagateDatabaseFailureInsteadOfRetryingAsIncompleteEvidence() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("database unavailable");
        doThrow(databaseFailure).when(dao).finishCaseResult(any());
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find(any(String.class))).thenReturn(Optional.of(completedSnapshot()));
        EvalRunService service = diagnosticService(dao, snapshots, new EvalRuleEngine());
        try {
            assertSame(databaseFailure, assertThrows(DataAccessResourceFailureException.class,
                    () -> ReflectionTestUtils.invokeMethod(service, "executeCase", diagnosticRun(),
                            diagnosticCase("case-1", 1), diagnosticResult("case-1"), "session-1")));
            verify(dao, times(1)).finishCaseResult(any());
            verify(dao, never()).refreshRunProgress(any(String.class));
        } finally {
            service.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldPreserveCancellationWhenDiagnosticCaptureFails() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find(any(String.class))).thenReturn(Optional.of(completedSnapshot()));
        EvalRuleEngine rules = mock(EvalRuleEngine.class);
        EvalRunService service = diagnosticService(dao, snapshots, rules);
        when(rules.evaluate(any())).thenAnswer(call -> {
            ((Set<String>) ReflectionTestUtils.getField(service, "cancelledRuns")).add("run-1");
            throw new IllegalArgumentException("diagnostic failure after cancellation");
        });
        try {
            ReflectionTestUtils.invokeMethod(service, "executeCase", diagnosticRun(),
                    diagnosticCase("case-1", 1), diagnosticResult("case-1"), "session-1");
            verify(dao, never()).finishCaseResult(any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shouldRefuseIncompleteEvidenceBeforeCallingJudgeProvider() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        when(dao.findCase("case-1")).thenReturn(diagnosticCase("case-1", 1));
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        AiEvalCaseResult result = diagnosticResult("case-1");
        result.setTraceJson("{\"traceSchemaVersion\":\"eval-trace-v3-json-values\",\"traceCompleteness\":\"INCOMPLETE\"}");
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ReflectionTestUtils.invokeMethod(service, "judgeOne",
                            AiEvalJudgeJob.builder().judgeJobId("job-1").build(), null, result, List.of(result)));
            assertTrue(failure.getMessage().contains("拒绝使用缺失证据"));
        } finally {
            service.shutdown();
        }
    }

    private static AiEvalRun diagnosticRun() {
        return AiEvalRun.builder().evalRunId("run-1").versionId("version-1").executionMode("SCENARIO")
                .configSnapshotJson("{}").concurrency(1).totalCases(2).completedCases(0)
                .userId("owner").tenantId("default").build();
    }

    private static AiEvalCase diagnosticCase(String caseId, int sequenceNo) {
        return AiEvalCase.builder().caseId(caseId).question("测试问题").sequenceNo(sequenceNo).configJson("{}").build();
    }

    private static AiEvalCaseResult diagnosticResult(String caseId) {
        return AiEvalCaseResult.builder().resultId("result-" + caseId).caseId(caseId).build();
    }

    private static RunSnapshot completedSnapshot() {
        RunSnapshot snapshot = RunSnapshot.builder().status(RunSnapshotService.STATUS_COMPLETED)
                .agentId("8013").agentType("FLOW").build();
        snapshot.getSteps().add(RunStepSnapshot.builder().content("已完成的答案").build());
        return snapshot;
    }

    private static EvalRunService diagnosticService(IAiEvalOpsDao dao, RunSnapshotService snapshots, EvalRuleEngine rules) {
        return new EvalRunService(dao, new EvalDatasetService(dao), rules, mock(IAgentDispatchService.class),
                mock(RunEventPublisher.class), snapshots, mock(EvalCodeVersionService.class));
    }

    private EvalRunService runService(IAiEvalOpsDao dao, IAgentDispatchService dispatch) {
        return new EvalRunService(dao, mock(EvalDatasetService.class), mock(EvalRuleEngine.class), dispatch,
                mock(RunEventPublisher.class), mock(RunSnapshotService.class), mock(EvalCodeVersionService.class));
    }
}
