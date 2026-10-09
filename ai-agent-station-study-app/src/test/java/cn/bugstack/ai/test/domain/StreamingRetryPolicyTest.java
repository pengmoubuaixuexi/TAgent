package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.StreamingRetryPolicy;
import cn.bugstack.ai.types.exception.BizException;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class StreamingRetryPolicyTest {
    @Test
    public void onlyUnstartedTransientRequestsCanBeReplayed() {
        Throwable timeout = new RuntimeException(new TimeoutException("idle"));
        assertTrue(StreamingRetryPolicy.canRetry(timeout, false, 0, 1, 3));
        assertTrue(StreamingRetryPolicy.canRetry(new IOException("reset"), false, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(timeout, true, 0, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(timeout, false, 1, 1, 3));
        assertFalse(StreamingRetryPolicy.canRetry(timeout, false, 0, 3, 3));
        assertFalse(StreamingRetryPolicy.canRetry(new IllegalArgumentException("invalid input"), false, 0, 1, 3));
    }

    @Test
    public void cancellationIsPreservedAndNeverRetried() {
        CancellationException cancellation = new CancellationException("user cancelled");
        RuntimeException wrapped = new RuntimeException(cancellation);
        assertSame(cancellation, StreamingRetryPolicy.cancellation(wrapped));
        assertFalse(StreamingRetryPolicy.canRetry(wrapped, false, 0, 1, 3));
    }

    @Test
    public void businessErrorKeepsMessageAndCauseForStepCards() {
        TimeoutException cause = new TimeoutException("No model activity for 120s");
        BizException messageOnly = new BizException("FLOW_FAILED", "model timed out");
        assertEquals("model timed out", messageOnly.getMessage());
        assertEquals("model timed out", messageOnly.getInfo());
        BizException error = new BizException("FLOW_FAILED", "model timed out", cause);
        assertEquals("model timed out", error.getMessage());
        assertSame(cause, error.getCause());
        assertEquals(cause.getMessage(), new BizException("FLOW_FAILED", cause).getMessage());
        assertEquals("FLOW_FAILED", new BizException("FLOW_FAILED").getMessage());
        RuntimeException streamFailure = StreamingRetryPolicy.failed("step4", error);
        assertSame(error, streamFailure.getCause());
        assertTrue(streamFailure.getMessage().contains("model timed out"));
    }
}
