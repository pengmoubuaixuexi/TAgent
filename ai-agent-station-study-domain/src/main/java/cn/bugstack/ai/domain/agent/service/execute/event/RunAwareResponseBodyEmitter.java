package cn.bugstack.ai.domain.agent.service.execute.event;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compatibility boundary for existing execution code that still calls
 * emitter.send(...). Every outgoing SSE frame is captured as a durable run
 * event; RunEventPublisher then fans it out to the current subscribers.
 */
public class RunAwareResponseBodyEmitter extends ResponseBodyEmitter {

    private final String runId;
    private final String sessionId;
    private final RunEventPublisher publisher;
    private final AtomicBoolean completionStarted = new AtomicBoolean();

    public RunAwareResponseBodyEmitter(Long timeout, String runId, String sessionId, RunEventPublisher publisher) {
        super(timeout);
        this.runId = runId;
        this.sessionId = sessionId;
        this.publisher = publisher;
    }

    @Override
    public void send(Object object) throws IOException {
        capture(object);
    }

    @Override
    public void send(Object object, MediaType mediaType) throws IOException {
        capture(object);
    }

    void sendRaw(Object object) throws IOException {
        super.send(object, null);
    }

    @Override
    public void complete() {
        if (completionStarted.compareAndSet(false, true)) {
            publisher.finishRun(runId);
        }
        completeRaw();
    }

    @Override
    public void completeWithError(Throwable failure) {
        if (completionStarted.compareAndSet(false, true)) {
            publisher.finishRun(runId);
        }
        super.completeWithError(failure);
    }

    void completeRaw() {
        super.complete();
    }

    private void capture(Object object) {
        if (object == null) return;
        String frame = String.valueOf(object);
        ParsedFrame parsed = parse(frame);
        publisher.publish(runId, sessionId, parsed.eventType(), parsed.payload());
    }

    private ParsedFrame parse(String frame) {
        if (frame == null) return new ParsedFrame("message", "");
        String normalized = frame.replace("\r\n", "\n");
        String eventType = "message";
        StringBuilder data = new StringBuilder();
        boolean sseFrame = false;
        for (String line : normalized.split("\n", -1)) {
            if (line.startsWith("event:")) {
                eventType = line.substring("event:".length()).trim();
                sseFrame = true;
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring("data:".length()).stripLeading());
                sseFrame = true;
            }
        }
        return new ParsedFrame(eventType, sseFrame ? data.toString() : frame);
    }

    private record ParsedFrame(String eventType, String payload) {
    }
}
