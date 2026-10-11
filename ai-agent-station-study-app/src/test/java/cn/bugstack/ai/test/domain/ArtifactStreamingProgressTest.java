package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingProgressEmitter;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ArtifactStreamingProgressTest {
    @Test public void artifactProgressKeepsElapsedTimeAndCannotRenewWatchdog() throws Exception {
        var activity = StreamingActivityTracker.start("artifact-progress");
        var other = StreamingActivityTracker.start("other-step");
        var updates = new ArrayList<StreamingActivityTracker.Progress>();
        activity.onProgress(updates::add);
        activity.markArtifactPhase("artifact_repairing");
        activity.beginModelRequest();
        assertEquals("repair survives the next recursive HTTP exchange", "artifact_repairing", progress(activity).phase());
        activity.markArtifactPhase("artifact_generating");
        var phaseField = activity.getClass().getDeclaredField("artifactPhase");
        phaseField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var phase = (AtomicReference<Object>) phaseField.get(activity);
        var constructor = phase.get().getClass().getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        var aged = constructor.newInstance("artifact_generating", System.nanoTime() - Duration.ofSeconds(17).toNanos());
        phase.set(aged);
        activity.markDecodedResponse();
        activity.markArtifactPhase("artifact_generating");
        assertSame("argument fragments must not reset generation age", aged, phase.get());
        assertTrue(progress(activity).waitSeconds() >= 17);
        var activityField = activity.getClass().getDeclaredField("lastActivityNanos");
        activityField.setAccessible(true);
        ((AtomicLong)activityField.get(activity)).set(System.nanoTime() - Duration.ofSeconds(3).toNanos());
        activity.markArtifactPhase("artifact_saving");
        activity.markArtifactPhase("artifact_fallback");
        assertTrue("UI progress cannot renew the idle deadline", activity.isIdle(Duration.ofSeconds(1)));
        assertNull("artifact phases are isolated between parallel steps", ((AtomicReference<?>)phaseField.get(other)).get());
        activity.clearArtifactPhase();
        assertEquals("model_streaming", updates.get(updates.size()-1).phase());
        assertThrows(IllegalArgumentException.class, () -> activity.markArtifactPhase("secret-provider-response"));
        int count = updates.size();
        activity.cancel();
        activity.markArtifactPhase("artifact_saving");
        assertEquals(count, updates.size());
    }

    @Test public void rawArtifactNamesAreRecognizedAcrossFragmentsWithoutPublishingArguments() throws Exception {
        var activity = StreamingActivityTracker.start("artifact-sse");
        var updates = new ArrayList<StreamingActivityTracker.Progress>();
        activity.onProgress(updates::add);
        activity.markArtifactPhase("artifact_repairing");
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"other","function":{"name":"business_tool","arguments":"{}"}},{"index":1,"id":"artifact","function":{"name":"create_","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"artifact","arguments":"private-document"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"artifact","arguments":"secret-api-key"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"create_artifact","arguments":"tail"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"without-name"}}]}}]}

                """;
        var filter = new ReasoningContentFilter();
        var request = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/chat")).build();
        try (var ignored = StreamingActivityTracker.scope(activity)) {
            var response = filter.filter(request, req -> Mono.just(ClientResponse.create(HttpStatus.OK).body(sse).build())).block();
            assertNotNull(response);
            assertEquals("SSE bytes reach the provider client unchanged", sse, response.bodyToMono(String.class).block());
            assertEquals(2, updates.size());
            assertEquals("artifact_generating", updates.get(1).phase());
            assertFalse(updates.toString().contains("private-document"));
            assertFalse(updates.toString().contains("secret-api-key"));
            activity.markArtifactPhase("artifact_repairing");
            var next = filter.filter(request, req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                    .body("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"{}\"}}]}}]}\n\n").build())).block();
            assertNotNull(next);
            next.bodyToMono(String.class).block();
            assertEquals("previous exchange names cannot label this new exchange", "artifact_repairing", updates.get(updates.size()-1).phase());
        }
    }

    @Test public void artifactPhasesUseSafeLabelsAndRetainTheirElapsedSeconds() throws Exception {
        var activity = mock(StreamingActivityTracker.Activity.class);
        var emitter = mock(ResponseBodyEmitter.class);
        var listener = new AtomicReference<Consumer<StreamingActivityTracker.Progress>>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(activity).onProgress(any());
        StreamingProgressEmitter.attach(activity, emitter, "final", "session", "run");
        for (String phase : List.of("artifact_generating", "artifact_repairing", "artifact_saving", "artifact_fallback"))
            listener.get().accept(new StreamingActivityTracker.Progress(phase, "secret-file-contents", 18));
        var frames = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(4)).send(frames.capture());
        for (Object frame : frames.getAllValues()) {
            String text = frame.toString();
            assertFalse(text.contains("secret-file-contents"));
            var payload = JSON.parseObject(text.substring(text.indexOf("data: ") + 6).trim());
            assertEquals("final", payload.getString("stepId"));
            assertEquals(18, payload.getIntValue("waitSeconds"));
            assertFalse(payload.getString("message").isBlank());
        }
        verify(activity).onProgress(any());
        verifyNoMoreInteractions(activity);
    }

    private static StreamingActivityTracker.Progress progress(StreamingActivityTracker.Activity activity) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(activity, "progress", System.nanoTime());
    }
}
