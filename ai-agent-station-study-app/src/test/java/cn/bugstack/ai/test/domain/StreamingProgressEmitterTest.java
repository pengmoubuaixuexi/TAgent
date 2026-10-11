package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingProgressEmitter;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StreamingProgressEmitterTest {
    @Test public void progressUsesStableStepIdentityAndFixedSafeLabelsOnly() throws Exception {
        var activity = mock(StreamingActivityTracker.Activity.class);
        var emitter = mock(ResponseBodyEmitter.class);
        var listener = new AtomicReference<Consumer<StreamingActivityTracker.Progress>>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(activity).onProgress(any());
        StreamingProgressEmitter.attach(activity, emitter, "flow_step4_execute_step_3", "session", "run");
        listener.get().accept(new StreamingActivityTracker.Progress("tool_running", "credential=do-not-display", 20));
        var frame = ArgumentCaptor.forClass(Object.class);
        verify(emitter).send(frame.capture());
        String text = frame.getValue().toString();
        assertTrue(text.startsWith("event: step_progress\ndata: "));
        var payload = JSON.parseObject(text.substring(text.indexOf("data: ") + 6).trim());
        assertEquals("flow_step4_execute_step_3", payload.getString("stepId"));
        assertEquals("session", payload.getString("sessionId"));
        assertEquals("run", payload.getString("runId"));
        assertEquals("正在等待工具结果", payload.getString("message"));
        assertEquals(20, payload.getIntValue("waitSeconds"));
        assertFalse(text.contains("credential"));
        verify(activity).onProgress(any());
        verifyNoMoreInteractions(activity); // Listener delivery never marks activity to renew a timeout.
    }

    @Test public void missingEmitterAndClosedBrowserCannotAffectModelExecution() throws Exception {
        var activity = mock(StreamingActivityTracker.Activity.class);
        StreamingProgressEmitter.attach(activity, null, "step", "session", "run");
        verifyNoInteractions(activity);
        var emitter = mock(ResponseBodyEmitter.class);
        doThrow(new IOException("disconnected")).when(emitter).send(anyString());
        var listener = new AtomicReference<Consumer<StreamingActivityTracker.Progress>>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(activity).onProgress(any());
        StreamingProgressEmitter.attach(activity, emitter, "step", "session", "run");
        listener.get().accept(new StreamingActivityTracker.Progress("awaiting_model", "", 12));
        listener.get().accept(new StreamingActivityTracker.Progress("untrusted_phase", "", 12));
        verify(emitter).send(anyString());
    }

    @Test public void advisorPhasesOnlyExposeFixedLabelsAndRetainTheirStepIdentity() throws Exception {
        var activity = mock(StreamingActivityTracker.Activity.class);
        var emitter = mock(ResponseBodyEmitter.class);
        var listener = new AtomicReference<Consumer<StreamingActivityTracker.Progress>>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(activity).onProgress(any());
        StreamingProgressEmitter.attach(activity, emitter, "flow_step1_mcp_tools_analysis", "session", "run");
        for (var phase : StreamingActivityTracker.AdvisorPhase.values()) {
            listener.get().accept(new StreamingActivityTracker.Progress(phase.phase(), "private-query-and-api-key", 17));
        }
        listener.get().accept(new StreamingActivityTracker.Progress("preparing_model", "private-prompt", 0));
        var frames = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(5)).send(frames.capture());
        for (int i = 0; i < frames.getAllValues().size(); i++) {
            String frame = frames.getAllValues().get(i).toString();
            var payload = JSON.parseObject(frame.substring(frame.indexOf("data: ") + 6).trim());
            assertEquals("flow_step1_mcp_tools_analysis", payload.getString("stepId"));
            assertEquals("session", payload.getString("sessionId"));
            assertEquals("run", payload.getString("runId"));
            assertFalse(frame.contains("private-"));
            if (i < 4) {
                assertEquals(StreamingActivityTracker.AdvisorPhase.values()[i].phase(), payload.getString("phase"));
                assertEquals(StreamingActivityTracker.AdvisorPhase.values()[i].message(), payload.getString("message"));
                assertEquals(17, payload.getIntValue("waitSeconds"));
            } else {
                assertEquals("preparing_model", payload.getString("phase"));
                assertEquals("正在准备模型请求", payload.getString("message"));
            }
        }
        verify(activity).onProgress(any());
        verifyNoMoreInteractions(activity);
    }
}
