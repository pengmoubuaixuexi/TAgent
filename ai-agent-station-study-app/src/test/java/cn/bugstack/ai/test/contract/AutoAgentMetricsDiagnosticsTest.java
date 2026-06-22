package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** P0-B2b-O1：finish-reason 只允许 Step1/Step3 固定 stage，避免原始 stepName 扩散 label 基数。 */
public class AutoAgentMetricsDiagnosticsTest {

    @Test
    public void finishReasonNormalizesStageAndReason_andIgnoresOtherSteps() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoAgentMetrics metrics = new AutoAgentMetrics(registry);

        metrics.recordFinishReason("step1_analyzer", "MAX_TOKENS");
        metrics.recordFinishReason("step3_quality_supervisor", "STOP");
        metrics.recordFinishReason("step2_precision_executor", "stop");
        metrics.recordFinishReason("step4_summary", "stop");

        assertEquals(1.0, registry.get("agent.auto.step.finish")
                .tags("stage", "step1", "reason", "length").counter().count(), 0.0);
        assertEquals(1.0, registry.get("agent.auto.step.finish")
                .tags("stage", "step3", "reason", "stop").counter().count(), 0.0);
        assertEquals(2, registry.find("agent.auto.step.finish").counters().size());
    }
}
