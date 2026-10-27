package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.Step3QualitySupervisorNode;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Auto finishes a round (execution and quality check), then starts no new one once the budget is used up. */
public class AutoBudgetRoundBoundaryTest {

    @Test
    public void afterTheQualityCheckAUsedUpBudgetGoesToTheSummary() throws Exception {
        String runId = "run-auto-budget";
        RunBudgetRegistry.State budget = RunBudgetRegistry.start(runId, "owner",
                new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 10, 10, 1000));
        try {
            Object summary = mock(cn.bugstack.wrench.design.framework.tree.StrategyHandler.class),
                    analyzer = mock(cn.bugstack.wrench.design.framework.tree.StrategyHandler.class);
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBean("step4LogExecutionSummaryNode")).thenReturn(summary);
            when(context.getBean("step1AnalyzerNode")).thenReturn(analyzer);
            Step3QualitySupervisorNode node = new Step3QualitySupervisorNode();
            ReflectionTestUtils.setField(node, "applicationContext", context);
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext round = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
            round.setStep(2);
            round.setMaxStep(5);
            ExecuteCommandEntity request = ExecuteCommandEntity.builder().runId(runId).build();

            assertSame("rounds continue while the budget lasts", analyzer, node.get(request, round));
            assertNull(round.getValue("budgetStopReason"));

            budget.reach("本次任务的 Token 预算（1000）已用完");
            assertSame(summary, node.get(request, round));
            assertEquals("本次任务的 Token 预算（1000）已用完", round.getValue("budgetStopReason"));
        } finally {
            RunBudgetRegistry.finish(runId);
        }
    }
}
