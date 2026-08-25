package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCaseResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeJob;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                AiEvalRun.builder().evalRunId("run-1").status("RULE_COMPLETED").build());
        when(dao.listJudgeJobs("run-1")).thenReturn(List.of());
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createJudgeJob("run-1", new EvalJudgeService.JudgeCommand());

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
        AiEvalRun cancelled = AiEvalRun.builder().evalRunId("run-1").status("CANCELLED")
                .totalCases(80).completedCases(80).build();
        AiEvalRun restored = AiEvalRun.builder().evalRunId("run-1").status("RULE_COMPLETED")
                .totalCases(80).completedCases(80).build();
        when(dao.findRun("run-1")).thenReturn(cancelled, restored);
        when(dao.listJudgeJobs("run-1")).thenReturn(List.of());
        EvalJudgeService service = new EvalJudgeService(dao, mock(IAgentRepository.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createJudgeJob("run-1", new EvalJudgeService.JudgeCommand());

            verify(dao).restoreRuleCompletedForJudgeRetry("run-1");
            verify(dao, never()).findJudgeJob(any(String.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            service.shutdown();
        }
    }

    private EvalRunService runService(IAiEvalOpsDao dao, IAgentDispatchService dispatch) {
        return new EvalRunService(dao, mock(EvalDatasetService.class), mock(EvalRuleEngine.class), dispatch,
                mock(RunEventPublisher.class), mock(RunSnapshotService.class), mock(EvalCodeVersionService.class));
    }
}
