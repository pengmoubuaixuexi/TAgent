package cn.bugstack.ai.domain.agent.service.model.failover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Holds an SSE body back until its first meaningful delta (content, reasoning or a tool call).
 * An error before that point has produced nothing the caller has seen, so the request layer may
 * still retry or switch; after it, errors pass through and the step fails as before.
 */
final class FirstOutputGuard {
    /** A body that keeps sending only metadata is released anyway, so nothing waits forever. */
    private static final int MAX_HELD_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private FirstOutputGuard() { }

    /**
     * @param onOutput     called once, when the first meaningful delta (or the end of a clean stream) is released
     * @param onEarlyError a replacement body for an error before any output, or null to propagate the error
     */
    static Flux<DataBuffer> apply(Flux<DataBuffer> body, Runnable onOutput, Function<Throwable, Flux<DataBuffer>> onEarlyError) {
        return Flux.defer(() -> {
            State state = new State(onOutput);
            return body.concatMap(state::accept)
                    .concatWith(Flux.defer(state::release))
                    .onErrorResume(error -> {
                        if (state.released) return Flux.error(error);
                        state.discard();
                        Flux<DataBuffer> replacement = onEarlyError.apply(error);
                        return replacement == null ? Flux.error(error) : replacement;
                    })
                    .doOnCancel(state::discard);
        });
    }

    private static final class State {
        private final Runnable onOutput;
        private final List<DataBuffer> held = new ArrayList<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private int heldBytes;
        private volatile boolean released;

        State(Runnable onOutput) { this.onOutput = onOutput; }

        synchronized Flux<DataBuffer> accept(DataBuffer buffer) {
            if (released) return Flux.just(buffer);
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.toByteBuffer(java.nio.ByteBuffer.wrap(bytes)); // copies without moving the read position
            held.add(buffer);
            heldBytes += bytes.length;
            pending.writeBytes(bytes);
            return meaningful() || heldBytes > MAX_HELD_BYTES ? release() : Flux.empty();
        }

        synchronized Flux<DataBuffer> release() {
            if (released) return Flux.empty();
            released = true;
            onOutput.run();
            List<DataBuffer> out = new ArrayList<>(held);
            held.clear();
            return Flux.fromIterable(out);
        }

        synchronized void discard() {
            held.forEach(DataBufferUtils::release);
            held.clear();
        }

        /** Parses only complete frames; a frame split across buffers waits for the rest. */
        private boolean meaningful() {
            String text = pending.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = text.lastIndexOf("\n\n");
            if (end < 0) return false;
            for (String event : text.substring(0, end).split("\n\n")) {
                for (String line : event.split("\n")) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) return true;
                    if (hasDelta(data)) return true;
                }
            }
            return false;
        }

        private static boolean hasDelta(String data) {
            try {
                JsonNode delta = JSON.readTree(data).path("choices").path(0).path("delta");
                return !delta.path("content").asText("").isEmpty() || !delta.path("reasoning_content").asText("").isEmpty()
                        || delta.path("tool_calls").size() > 0;
            } catch (Exception notJson) {
                return false;
            }
        }
    }
}
