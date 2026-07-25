package cn.bugstack.ai.domain.agent.service.execute.event;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run output boundary. Execution publishes events here instead of owning a
 * browser connection. A failed SSE write only detaches that subscriber.
 */
@Slf4j
@Component
public class RunEventPublisher {

    @Autowired(required = false)
    private IRunEventStore eventStore;

    @Autowired(required = false)
    private RunSnapshotService runSnapshotService;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimelineAccumulator> timelines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionRunIds = new ConcurrentHashMap<>();
    private final AtomicLong localEventSequence = new AtomicLong();

    public void registerRun(String runId, String sessionId) {
        if (!blank(runId) && !blank(sessionId)) sessionRunIds.putIfAbsent(sessionId, runId);
    }

    public String currentRunId(String sessionId) {
        return blank(sessionId) ? null : sessionRunIds.get(sessionId);
    }

    public void attach(String runId, String sessionId, ResponseBodyEmitter emitter, String afterEventId) {
        if (blank(runId) || emitter == null) return;
        registerRun(runId, sessionId);
        Subscriber subscriber = new Subscriber(runId, emitter);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        emitter.onCompletion(() -> detach(runId, subscriber));
        emitter.onTimeout(() -> detach(runId, subscriber));
        emitter.onError(error -> detach(runId, subscriber));

        List<RunEventRecord> replay = List.of();
        if (eventStore != null) {
            try {
                replay = eventStore.readAfter(runId, afterEventId);
            } catch (Exception error) {
                log.warn("[RunEvent] replay failed runId={} after={}: {}",
                        runId, afterEventId, error.getMessage());
            }
        }
        subscriber.finishReplay(replay);
    }

    public String publish(String runId, String sessionId, String eventType, Object payload) {
        if (blank(runId) || blank(eventType) || payload == null) return null;
        String payloadJson = payload instanceof String value ? value : JSON.toJSONString(payload);
        RunEventRecord record = null;
        if (eventStore != null) {
            try {
                record = eventStore.append(runId, sessionId, eventType, payloadJson);
            } catch (Exception error) {
                log.warn("[RunEvent] append failed runId={} type={}: {}", runId, eventType, error.getMessage());
            }
        }
        if (record == null) {
            record = RunEventRecord.builder()
                    .eventId("local-" + localEventSequence.incrementAndGet())
                    .runId(runId)
                    .sessionId(sessionId)
                    .eventType(eventType)
                    .payloadJson(payloadJson)
                    .createdAt(System.currentTimeMillis())
                    .build();
        }

        timeline(runId).append(record);
        List<Subscriber> current = subscribers.get(runId);
        if (current != null) {
            for (Subscriber subscriber : current) {
                if (!subscriber.accept(record)) detach(runId, subscriber);
            }
        }
        return record.getEventId();
    }

    public String publishCurrent(String sessionId, String eventType, Object payload) {
        String runId = currentRunId(sessionId);
        return runId == null ? null : publish(runId, sessionId, eventType, payload);
    }

    public void checkpoint(String runId) {
        TimelineAccumulator timeline = timelines.get(runId);
        if (timeline != null) timeline.checkpoint(true);
    }

    public void finishRun(String runId) {
        TimelineAccumulator timeline = timelines.remove(runId);
        if (timeline != null) timeline.checkpoint(true);
        CopyOnWriteArrayList<Subscriber> current = subscribers.remove(runId);
        if (current != null) {
            for (Subscriber subscriber : current) subscriber.complete();
        }
        sessionRunIds.entrySet().removeIf(entry -> runId.equals(entry.getValue()));
    }

    private TimelineAccumulator timeline(String runId) {
        return timelines.computeIfAbsent(runId, id -> {
            List<RunEventRecord> seed = List.of();
            String lastEventId = null;
            if (runSnapshotService != null) {
                try {
                    RunSnapshot snapshot = runSnapshotService.find(id).orElse(null);
                    if (snapshot != null) {
                        seed = snapshot.getTimelineEvents() == null ? List.of() : snapshot.getTimelineEvents();
                        lastEventId = snapshot.getLastEventId();
                    }
                } catch (Exception error) {
                    log.debug("[RunEvent] timeline seed failed runId={}: {}", id, error.getMessage());
                }
            }
            return new TimelineAccumulator(id, seed, lastEventId);
        });
    }

    private void detach(String runId, Subscriber subscriber) {
        CopyOnWriteArrayList<Subscriber> current = subscribers.get(runId);
        if (current == null) return;
        current.remove(subscriber);
        if (current.isEmpty()) subscribers.remove(runId, current);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private final class TimelineAccumulator {
        private final String runId;
        private final List<RunEventRecord> compactEvents = new ArrayList<>();
        private String lastEventId;
        private long lastCheckpointAt;

        private TimelineAccumulator(String runId, List<RunEventRecord> seed, String lastEventId) {
            this.runId = runId;
            if (seed != null) compactEvents.addAll(seed);
            this.lastEventId = lastEventId;
        }

        private synchronized void append(RunEventRecord record) {
            if (!mergeToken(record)) compactEvents.add(record);
            lastEventId = record.getEventId();
            boolean milestone = !"token".equals(record.getEventType())
                    || System.currentTimeMillis() - lastCheckpointAt >= 1_000L;
            if (milestone) checkpoint(false);
        }

        /** Compact adjacent token events for the snapshot while Stream keeps exact chunks. */
        private boolean mergeToken(RunEventRecord incoming) {
            if (!"token".equals(incoming.getEventType()) || compactEvents.isEmpty()) return false;
            int previousIndex = compactEvents.size() - 1;
            RunEventRecord previous = compactEvents.get(previousIndex);
            if (!"token".equals(previous.getEventType())) return false;
            try {
                JSONObject left = JSON.parseObject(previous.getPayloadJson());
                JSONObject right = JSON.parseObject(incoming.getPayloadJson());
                if (!java.util.Objects.equals(left.getString("stepId"), right.getString("stepId"))) return false;
                String token = right.getString("token");
                if (!"[DONE]".equals(token)) {
                    left.put("token", string(left.getString("token")) + string(token));
                }
                // A published record may simultaneously be referenced by a
                // reconnect subscriber's replay buffer. Never mutate it in
                // place: doing so changes that buffer's payload/cursor and can
                // make the same token appear twice after replay.
                RunEventRecord merged = RunEventRecord.builder()
                        .eventId(incoming.getEventId())
                        .runId(previous.getRunId())
                        .sessionId(previous.getSessionId())
                        .eventType(previous.getEventType())
                        .payloadJson(left.toJSONString())
                        .createdAt(incoming.getCreatedAt())
                        .build();
                compactEvents.set(previousIndex, merged);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        private synchronized void checkpoint(boolean force) {
            if (runSnapshotService == null || compactEvents.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (!force && now - lastCheckpointAt < 250L) return;
            runSnapshotService.recordTimeline(runId, new ArrayList<>(compactEvents), lastEventId);
            lastCheckpointAt = now;
        }

        private String string(String value) {
            return value == null ? "" : value;
        }
    }

    private static final class Subscriber {
        private final String runId;
        private final ResponseBodyEmitter emitter;
        private final ConcurrentLinkedQueue<RunEventRecord> buffered = new ConcurrentLinkedQueue<>();
        private boolean replaying = true;
        private boolean active = true;

        private Subscriber(String runId, ResponseBodyEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }

        private synchronized boolean accept(RunEventRecord record) {
            if (!active) return false;
            if (replaying) {
                buffered.add(record);
                return true;
            }
            return send(record);
        }

        private void finishReplay(List<RunEventRecord> replay) {
            Set<String> replayedIds = new HashSet<>();
            if (replay != null) {
                for (RunEventRecord record : replay) {
                    if (record == null) continue;
                    if (record.getEventId() != null) replayedIds.add(record.getEventId());
                    if (!send(record)) break;
                }
            }
            synchronized (this) {
                RunEventRecord queued;
                while (active && (queued = buffered.poll()) != null) {
                    if (queued.getEventId() != null && replayedIds.contains(queued.getEventId())) continue;
                    if (!send(queued)) break;
                }
                replaying = false;
            }
        }

        private boolean send(RunEventRecord record) {
            if (!active || record == null) return false;
            StringBuilder frame = new StringBuilder();
            if (!blank(record.getEventId())) frame.append("id: ").append(record.getEventId()).append('\n');
            frame.append("event: ").append(record.getEventType()).append('\n');
            frame.append("data: ").append(record.getPayloadJson()).append("\n\n");
            try {
                synchronized (emitter) {
                    if (emitter instanceof RunAwareResponseBodyEmitter runEmitter) {
                        runEmitter.sendRaw(frame.toString());
                    } else {
                        emitter.send(frame.toString());
                    }
                }
                return true;
            } catch (Exception error) {
                active = false;
                log.debug("[RunEvent] subscriber detached runId={}: {}", runId, error.getMessage());
                return false;
            }
        }

        private synchronized void complete() {
            if (!active) return;
            active = false;
            try {
                if (emitter instanceof RunAwareResponseBodyEmitter runEmitter) {
                    runEmitter.completeRaw();
                } else {
                    emitter.complete();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
