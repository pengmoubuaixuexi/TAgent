package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import org.junit.Test;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Contract tests for cancellation propagation across parallel Flow DAG streams. */
public class FlowCancellationBroadcastContractTest {

    @Test
    public void cancellationSignalMustReachEveryInflightDagBranch() {
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        List<Sinks.One<Object>> triggers = new ArrayList<>();
        AtomicInteger interrupted = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            Sinks.One<Object> trigger = Sinks.one();
            trigger.asMono().subscribe(ignored -> interrupted.incrementAndGet());
            triggers.add(trigger);
            context.registerCancelTrigger(trigger);
        }

        context.cancel();
        context.fireCancelTrigger();

        assertEquals(3, interrupted.get());
        triggers.forEach(context::unregisterCancelTrigger);
    }

    @Test
    public void triggerRegisteredAfterCancellationMustBeInterruptedImmediately() {
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        context.cancel();

        Sinks.One<Object> lateTrigger = Sinks.one();
        AtomicInteger interrupted = new AtomicInteger();
        lateTrigger.asMono().subscribe(ignored -> interrupted.incrementAndGet());
        context.registerCancelTrigger(lateTrigger);

        assertEquals(1, interrupted.get());
        context.unregisterCancelTrigger(lateTrigger);
    }
}
