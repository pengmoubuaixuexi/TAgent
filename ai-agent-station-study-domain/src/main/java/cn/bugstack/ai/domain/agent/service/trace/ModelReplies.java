package cn.bugstack.ai.domain.agent.service.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reassembles the assistant reply of an OpenAI-compatible chat completion, plain or streamed. */
public final class ModelReplies {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    /** An error body or a non-SSE answer is kept up to this size. */
    static final int MAX_RAW_CHARS = 64 * 1024;

    private ModelReplies() { }

    /** A non-streaming response body; null when it is not a chat completion. */
    @SuppressWarnings("unchecked")
    public static RunTrace.ModelReply fromJson(byte[] body) {
        try {
            Map<String, Object> root = JSON.readValue(body, MAP);
            if (!(root.get("choices") instanceof List<?> choices) || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?, ?> choice)) return null;
            Map<String, Object> message = choice.get("message") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            List<Map<String, Object>> calls = new ArrayList<>();
            if (message.get("tool_calls") instanceof List<?> list) {
                for (Object value : list) if (value instanceof Map<?, ?> call) calls.add((Map<String, Object>) call);
            }
            Map<?, ?> usage = root.get("usage") instanceof Map<?, ?> u ? u : Map.of();
            return new RunTrace.ModelReply(text(root.get("model")), text(message.get("content")),
                    text(message.get("reasoning_content")), calls, text(choice.get("finish_reason")),
                    number(usage.get("prompt_tokens")), number(usage.get("completion_tokens")));
        } catch (Exception notJson) {
            return null;
        }
    }

    /** Text of an error body, bounded. */
    public static String errorText(byte[] body) {
        if (body == null || body.length == 0) return null;
        String text = new String(body, 0, Math.min(body.length, MAX_RAW_CHARS * 3), StandardCharsets.UTF_8);
        return text.length() > MAX_RAW_CHARS ? text.substring(0, MAX_RAW_CHARS) : text;
    }

    /** Accumulates server-sent events; UTF-8 characters and frames may span network buffers. */
    public static final class Stream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder raw = new StringBuilder();
        private final Map<Integer, Map<String, Object>> calls = new TreeMap<>();
        private final Map<Integer, StringBuilder> arguments = new TreeMap<>();
        private String model;
        private String finishReason;
        private String error;
        private Long inputTokens;
        private Long outputTokens;
        private boolean sse;
        private boolean done;
        private int trailing;

        public synchronized void accept(byte[] bytes) {
            for (byte value : bytes) {
                pending.write(value);
                trailing = (trailing << 8) | (value & 255);
                if ((trailing & 65535) == 0x0a0a || trailing == 0x0d0a0d0a) {
                    frame(pending.toString(StandardCharsets.UTF_8));
                    pending.reset();
                    trailing = 0;
                }
                // A provider that never sends a frame separator (an error page) is kept as raw text.
                if (pending.size() > MAX_RAW_CHARS * 4) {
                    frame(pending.toString(StandardCharsets.UTF_8));
                    pending.reset();
                }
            }
        }

        public synchronized void finish() {
            if (pending.size() > 0) frame(pending.toString(StandardCharsets.UTF_8));
            pending.reset();
        }

        public synchronized boolean done() { return done; }

        /** Raw text when the body was not a stream (an error page or JSON error). */
        public synchronized String raw() { return raw.isEmpty() ? null : raw.toString(); }

        public synchronized String error() { return error; }

        public synchronized RunTrace.ModelReply reply() {
            if (!sse) return null;
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, Object>> entry : calls.entrySet()) {
                Map<String, Object> call = new LinkedHashMap<>(entry.getValue());
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.remove("name"));
                StringBuilder args = arguments.get(entry.getKey());
                function.put("arguments", args == null ? "" : args.toString());
                call.put("type", "function");
                call.put("function", function);
                toolCalls.add(call);
            }
            return new RunTrace.ModelReply(model, content.isEmpty() ? null : content.toString(),
                    reasoning.isEmpty() ? null : reasoning.toString(), toolCalls, finishReason, inputTokens, outputTokens);
        }

        @SuppressWarnings("unchecked")
        private void frame(String event) {
            boolean data = false;
            for (String line : event.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("data:")) continue;
                data = true;
                sse = true;
                String json = trimmed.substring(5).trim();
                if ("[DONE]".equals(json)) { done = true; continue; }
                if (json.isEmpty()) continue;
                try {
                    Map<String, Object> chunk = JSON.readValue(json, MAP);
                    if (chunk.get("error") != null) error = JSON.writeValueAsString(chunk.get("error"));
                    if (chunk.get("model") instanceof String name && !name.isBlank()) model = name;
                    if (chunk.get("usage") instanceof Map<?, ?> usage) {
                        Long in = number(usage.get("prompt_tokens")), out = number(usage.get("completion_tokens"));
                        if (in != null) inputTokens = in;
                        if (out != null) outputTokens = out;
                    }
                    if (!(chunk.get("choices") instanceof List<?> choices) || choices.isEmpty()
                            || !(choices.get(0) instanceof Map<?, ?> choice)) continue;
                    if (choice.get("finish_reason") instanceof String reason && !reason.isBlank()) finishReason = reason;
                    if (!(choice.get("delta") instanceof Map<?, ?> delta)) continue;
                    if (delta.get("content") instanceof String text) content.append(text);
                    if (delta.get("reasoning_content") instanceof String text) reasoning.append(text);
                    if (delta.get("tool_calls") instanceof List<?> parts) {
                        for (Object value : parts) {
                            if (!(value instanceof Map<?, ?> part)) continue;
                            int index = part.get("index") instanceof Number n ? n.intValue() : 0;
                            Map<String, Object> call = calls.computeIfAbsent(index, i -> new LinkedHashMap<>());
                            if (part.get("id") instanceof String id && !id.isBlank() && !call.containsKey("id")) call.put("id", id);
                            if (part.get("function") instanceof Map<?, ?> function) {
                                if (function.get("name") instanceof String name && !name.isEmpty()) {
                                    String previous = (String) call.get("name");
                                    call.put("name", previous == null || previous.equals(name) || previous.endsWith(name) ? (previous == null ? name : previous)
                                            : name.startsWith(previous) ? name : previous + name);
                                }
                                if (function.get("arguments") instanceof String fragment) {
                                    arguments.computeIfAbsent(index, i -> new StringBuilder()).append(fragment);
                                }
                            }
                        }
                    }
                } catch (Exception malformed) {
                    // Keep going: a single malformed frame must not lose the rest of the reply.
                }
            }
            if (!data && !sse && raw.length() < MAX_RAW_CHARS) {
                raw.append(event, 0, Math.min(event.length(), MAX_RAW_CHARS - raw.length()));
            }
        }
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
