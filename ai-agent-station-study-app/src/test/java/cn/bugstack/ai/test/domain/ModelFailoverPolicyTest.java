package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingRetryPolicy;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelCall;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelRetriesExhaustedException;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelsUnavailableException;
import cn.bugstack.ai.domain.agent.service.model.health.ModelFallbackRunReporter;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRegistry;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One retry layer, and one run notice per substitution. */
public class ModelFailoverPolicyTest {
    private static final ModelTarget V25 = new ModelTarget("api", "mimo-v2.5");
    private static final ModelTarget FLASH = new ModelTarget("api", "mimo-v2.6-flash");

    @Test public void theStreamingStepNeverReplaysWhatTheRequestLayerAlreadyDecided() {
        var exhausted = new ModelRetriesExhaustedException(new ModelFailure(V25, ModelFailure.Kind.TRANSIENT, 503, "overloaded"), 3);
        var unavailable = new ModelsUnavailableException(V25, List.of(new ModelFailure(V25, ModelFailure.Kind.MODEL_UNAVAILABLE, 400, "x")));
        assertFalse(StreamingRetryPolicy.canRetry(new RuntimeException(exhausted), false, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(new IllegalStateException(unavailable), false, 0, 1, 3));
        assertTrue("a broken stream before any output may still be replayed", StreamingRetryPolicy.canRetry(new IOException("reset"), false, 0, 1, 3));
        assertEquals(exhausted.getMessage(), StreamingRetryPolicy.failed("step", exhausted).getMessage());
        assertEquals("所有可用模型都不可用：mimo-v2.5（模型不存在或已下线，HTTP 400）", StreamingRetryPolicy.failed("step", unavailable).getMessage());
    }

    @Test public void eachRunGetsOneNoticePerSubstitutionWithTheSafeReason() throws Exception {
        String run = "run-" + UUID.randomUUID();
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 5, 5, 1000));
        try {
            RunEventPublisher events = mock(RunEventPublisher.class);
            var reporter = new ModelFallbackRunReporter(events, new ModelHealthRegistry(null, System::currentTimeMillis));
            var failure = new ModelFailure(V25, ModelFailure.Kind.MODEL_UNAVAILABLE, 400, "Unsupported model mimo-v2.5");
            for (int i = 0; i < 3; i++) reporter.substituted(new ModelCall(run, "session"), V25, FLASH, List.of(failure));
            reporter.substituted(new ModelCall("not-a-run", "session"), V25, FLASH, List.of(failure));

            @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(events, timeout(2000).times(1)).publishToRun(eq(run), eq("session"), eq("model_fallback"), payload.capture());
            Thread.sleep(100);
            verify(events, times(1)).publishToRun(anyString(), any(), anyString(), any());
            assertEquals("本次由 mimo-v2.6-flash 代答（mimo-v2.5 暂不可用：模型不存在或已下线）", payload.getValue().get("notice"));
            assertFalse("provider text is not shown to users", payload.getValue().toString().contains("Unsupported model"));
        } finally {
            RunBudgetRegistry.finish(run);
        }
    }
}
