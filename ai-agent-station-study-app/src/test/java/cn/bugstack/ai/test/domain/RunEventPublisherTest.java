package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.event.IRunEventStore;
import cn.bugstack.ai.domain.agent.service.execute.event.RunAwareResponseBodyEmitter;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RunEventPublisherTest {

    @Test
    public void preparationProgressIsTransientAndNeverBecomesReplayedHistory() {
        MemoryStore store = new MemoryStore();
        RunEventPublisher publisher = new RunEventPublisher();
        ReflectionTestUtils.setField(publisher, "eventStore", store);
        CapturingEmitter live = new CapturingEmitter();
        publisher.attach("preparing-run", "session", live, null);
        publisher.publish("preparing-run", "session", "run_progress",
                java.util.Map.of("runId", "preparing-run", "phase", "mcp_connecting", "resourceName", "天气服务"));
        assertEquals(1, live.frames.size());
        assertTrue(live.frames.get(0).startsWith("event: run_progress\n"));
        assertTrue(store.records.isEmpty());
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(publisher, "timelines")).isEmpty());
        CapturingEmitter reconnect = new CapturingEmitter();
        publisher.attach("preparing-run", "session", reconnect, null);
        assertTrue(reconnect.frames.isEmpty());
        publisher.finishRun("preparing-run");
    }

    @Test
    public void stepProgressIsLiveOnlyAndDoesNotAdvanceReplayCursorOrPersistHistory() throws Exception {
        MemoryStore store = new MemoryStore();
        RunEventPublisher publisher = new RunEventPublisher();
        ReflectionTestUtils.setField(publisher, "eventStore", store);
        var snapshots = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService.class);
        var notifications = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.notification.RunNotificationProjector.class);
        ReflectionTestUtils.setField(publisher, "runSnapshotService", snapshots);
        ReflectionTestUtils.setField(publisher, "notificationProjector", notifications);
        CapturingEmitter live = new CapturingEmitter();
        publisher.attach("progress-run", "session", live, null);
        var bridge = new RunAwareResponseBodyEmitter(Long.MAX_VALUE, "progress-run", "session", publisher);
        bridge.send("event: step_progress\ndata: {\"stepId\":\"step-1\",\"phase\":\"awaiting_model\",\"waitSeconds\":10}\n\n");
        assertEquals(1, live.frames.size());
        assertTrue(live.frames.get(0).startsWith("event: step_progress\n"));
        assertTrue(store.records.isEmpty());
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(publisher, "timelines")).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(snapshots, notifications);
        CapturingEmitter reconnect = new CapturingEmitter();
        publisher.attach("progress-run", "session", reconnect, null);
        assertTrue(reconnect.frames.isEmpty());
        publisher.finishRun("progress-run");
        bridge.send("event: step_progress\ndata: {\"stepId\":\"step-1\"}\n\n");
        assertEquals(1, live.frames.size());
        assertTrue(store.records.isEmpty());
    }

    @Test
    public void failedTerminalDiagnosticsStillCloseSubscribersAndBudget() {
        String run = java.util.UUID.randomUUID().toString();
        var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.start(run, "owner",
                new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.Limits(
                        java.time.Duration.ofSeconds(30), 5, 5, 1000));
        RunEventPublisher publisher = new RunEventPublisher();
        CapturingEmitter emitter = new CapturingEmitter();
        publisher.attach(run, "diagnostic-session", emitter, null);
        var snapshots = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("storage down")).when(snapshots)
                .recordTimeline(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
        ReflectionTestUtils.setField(publisher, "runSnapshotService", snapshots);
        publisher.publish(run, "diagnostic-session", "token", java.util.Map.of("stepId", "s1", "token", "answer"));
        assertEquals(1, emitter.frames.size());
        var projector = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.notification.RunNotificationProjector.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("projector down")).when(projector)
                .project(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        ReflectionTestUtils.setField(publisher, "notificationProjector", projector);
        publisher.finishRun(run);
        assertTrue(emitter.completed);
        assertNull(publisher.currentRunId("diagnostic-session"));
        org.junit.Assert.assertThrows(cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException.class,
                budget::reserveTool);
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(publisher, "subscribers")).isEmpty());
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(publisher, "timelines")).isEmpty());
    }

    @Test
    public void shouldPersistBeforeFanoutAndReplayAfterCursor() {
        MemoryStore store = new MemoryStore();
        RunEventPublisher publisher = new RunEventPublisher();
        ReflectionTestUtils.setField(publisher, "eventStore", store);

        CapturingEmitter first = new CapturingEmitter();
        publisher.attach("run-1", "session-1", first, null);
        publisher.publish("run-1", "session-1", "step_start", java.util.Map.of("stepId", "s1"));
        publisher.publish("run-1", "session-1", "token", java.util.Map.of("stepId", "s1", "token", "hello"));

        assertEquals(2, store.records.size());
        assertEquals(2, first.frames.size());
        assertTrue(first.frames.get(0).contains("id: 1-0"));
        assertTrue(first.frames.get(0).contains("event: step_start"));

        CapturingEmitter replay = new CapturingEmitter();
        publisher.attach("run-1", "session-1", replay, "1-0");
        assertEquals(1, replay.frames.size());
        assertTrue(replay.frames.get(0).contains("id: 2-0"));
        assertTrue(replay.frames.get(0).contains("event: token"));
    }

    @Test
    public void runAwareEmitterShouldCaptureLegacySseSendExactlyOnce() throws Exception {
        MemoryStore store = new MemoryStore();
        RunEventPublisher publisher = new RunEventPublisher();
        ReflectionTestUtils.setField(publisher, "eventStore", store);

        RunAwareResponseBodyEmitter emitter =
                new RunAwareResponseBodyEmitter(Long.MAX_VALUE, "run-2", "session-2", publisher);
        publisher.attach("run-2", "session-2", emitter, null);
        emitter.send("event: tool_call_start\ndata: {\"toolName\":\"write_file\"}\n\n");

        assertEquals(1, store.records.size());
        assertEquals("tool_call_start", store.records.get(0).getEventType());
        assertEquals("{\"toolName\":\"write_file\"}", store.records.get(0).getPayloadJson());
        emitter.complete();
    }

    @Test
    public void timelineTokenCompactionMustNotMutateReplayBuffer() throws Exception {
        MemoryStore store = new MemoryStore();
        RunEventPublisher publisher = new RunEventPublisher();
        ReflectionTestUtils.setField(publisher, "eventStore", store);

        // Make the first token after the replay boundary become the timeline
        // tail. The following token will then exercise token compaction.
        publisher.publish("run-race", "session-race", "step_start",
                java.util.Map.of("stepId", "s1"));

        BlockingEmitter reconnect = new BlockingEmitter();
        AtomicReference<Throwable> attachFailure = new AtomicReference<>();
        Thread attachThread = new Thread(() -> {
            try {
                publisher.attach("run-race", "session-race", reconnect, null);
            } catch (Throwable error) {
                attachFailure.set(error);
            }
        });
        attachThread.start();

        assertTrue("replay did not start", reconnect.replayStarted.await(2, TimeUnit.SECONDS));
        publisher.publish("run-race", "session-race", "token",
                java.util.Map.of("stepId", "s1", "token", "你"));
        publisher.publish("run-race", "session-race", "token",
                java.util.Map.of("stepId", "s1", "token", "好"));
        reconnect.allowReplayToFinish.countDown();
        attachThread.join(2_000);

        assertFalse("attach thread did not finish", attachThread.isAlive());
        assertNull(attachFailure.get());
        assertEquals(3, reconnect.frames.size());
        assertTrue(reconnect.frames.get(1).contains("\"token\":\"你\""));
        assertFalse(reconnect.frames.get(1).contains("你好"));
        assertTrue(reconnect.frames.get(2).contains("\"token\":\"好\""));
    }

    @Test
    public void concurrentLegacyCaptureAndDirectPublishMustNotDeadlock() throws Exception {
        MemoryStore store = new MemoryStore();
        RunEventPublisher publisher = new RunEventPublisher();
        ReflectionTestUtils.setField(publisher, "eventStore", store);

        RunAwareResponseBodyEmitter emitter =
                new RunAwareResponseBodyEmitter(Long.MAX_VALUE, "run-deadlock", "session-deadlock", publisher);
        publisher.attach("run-deadlock", "session-deadlock", emitter, null);

        CountDownLatch emitterLocked = new CountDownLatch(1);
        CountDownLatch allowLegacyCapture = new CountDownLatch(1);
        AtomicReference<Throwable> legacyFailure = new AtomicReference<>();
        AtomicReference<Throwable> directFailure = new AtomicReference<>();

        Thread legacy = new Thread(() -> {
            try {
                synchronized (emitter) {
                    emitterLocked.countDown();
                    assertTrue(allowLegacyCapture.await(2, TimeUnit.SECONDS));
                    emitter.send("event: step_start\ndata: {\"stepId\":\"legacy\"}\n\n");
                }
            } catch (Throwable error) {
                legacyFailure.set(error);
            }
        }, "legacy-emitter-capture");
        legacy.setDaemon(true);

        Thread direct = new Thread(() -> {
            try {
                assertTrue(emitterLocked.await(2, TimeUnit.SECONDS));
                publisher.publish("run-deadlock", "session-deadlock", "memory_evidence",
                        java.util.Map.of("type", "long_term"));
            } catch (Throwable error) {
                directFailure.set(error);
            }
        }, "direct-run-event-publish");
        direct.setDaemon(true);

        legacy.start();
        direct.start();
        assertTrue(emitterLocked.await(2, TimeUnit.SECONDS));
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (direct.getState() != Thread.State.BLOCKED && System.nanoTime() < waitUntil) {
            Thread.onSpinWait();
        }
        allowLegacyCapture.countDown();

        legacy.join(2_000);
        direct.join(2_000);
        assertFalse("legacy capture deadlocked", legacy.isAlive());
        assertFalse("direct publish deadlocked", direct.isAlive());
        assertNull(legacyFailure.get());
        assertNull(directFailure.get());
        assertEquals(2, store.records.size());
        emitter.complete();
    }

    private static class CapturingEmitter extends ResponseBodyEmitter {
        protected final List<String> frames = new ArrayList<>();
        protected boolean completed;

        @Override public synchronized void complete() { completed = true; super.complete(); }

        @Override
        public synchronized void send(Object object) throws IOException {
            frames.add(String.valueOf(object));
        }
    }

    private static final class BlockingEmitter extends CapturingEmitter {
        private final CountDownLatch replayStarted = new CountDownLatch(1);
        private final CountDownLatch allowReplayToFinish = new CountDownLatch(1);

        @Override
        public synchronized void send(Object object) throws IOException {
            frames.add(String.valueOf(object));
            if (frames.size() == 1) {
                replayStarted.countDown();
                try {
                    if (!allowReplayToFinish.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting to finish replay");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
        }
    }

    private static final class MemoryStore implements IRunEventStore {
        private final AtomicLong ids = new AtomicLong();
        private final List<RunEventRecord> records = new ArrayList<>();

        @Override
        public synchronized RunEventRecord append(String runId, String sessionId, String eventType, String payloadJson) {
            RunEventRecord record = RunEventRecord.builder()
                    .eventId(ids.incrementAndGet() + "-0")
                    .runId(runId)
                    .sessionId(sessionId)
                    .eventType(eventType)
                    .payloadJson(payloadJson)
                    .createdAt(System.currentTimeMillis())
                    .build();
            records.add(record);
            return record;
        }

        @Override
        public synchronized List<RunEventRecord> readAfter(String runId, String afterEventId) {
            if (afterEventId == null || afterEventId.isBlank()) return new ArrayList<>(records);
            long cursor = Long.parseLong(afterEventId.substring(0, afterEventId.indexOf('-')));
            return records.stream()
                    .filter(record -> Long.parseLong(record.getEventId().substring(0, record.getEventId().indexOf('-'))) > cursor)
                    .toList();
        }
    }
}
