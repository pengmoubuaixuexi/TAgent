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
