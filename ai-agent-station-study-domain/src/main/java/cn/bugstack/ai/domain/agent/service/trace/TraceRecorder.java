package cn.bugstack.ai.domain.agent.service.trace;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Turns exchanges into trace nodes. A model request is stored as the messages it adds to the
 * longest matching earlier request of the same run (a tool loop resends the whole conversation);
 * system prompts, tool definitions and long texts are stored once per run as blobs.
 */
@Slf4j
public class TraceRecorder {

    /** Longer strings are moved to the blob table. */
    static final int INLINE_CHARS = 16 * 1024;
    /** System prompts repeat across a run's chains, so even short ones are shared. */
    static final int SYSTEM_BLOB_CHARS = 512;
    static final int RECENT_REQUESTS = 12;
    static final int MAX_TOOL_PARENTS = 512;
    private static final int MAX_RUNS = 20_000;
    private static final long STALE_MILLIS = 6 * 60 * 60 * 1000L;
    private static final Pattern ATTEMPT = Pattern.compile("#attempt-\\d+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Run events worth keeping next to the model and tool nodes; streaming deltas are not. */
    static final Set<String> EVENTS = Set.of("agent_routed", "step_start", "step_end", "model_fallback",
            "attachment_notice", "memory_evidence", "rag_evidence", "user_input_required", "user_input_result",
            "human_approval_required", "human_approval_result", "artifact_created", "run_budget", "run_budget_notice", "message");

    private static final int MAX_FINISHED = 10_000;

    private final TraceSink sink;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    /** Last sequence of recently finished runs (insertion ordered, bounded). */
    private final Map<String, Integer> finishedSeq = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    public TraceRecorder(TraceSink sink) {
        this.sink = sink;
    }

    private static final class RunState {
        final String owner;
        final AtomicInteger seq = new AtomicInteger();
        final Deque<Request> recent = new ArrayDeque<>();
        final Map<String, Integer> toolParents = new LinkedHashMap<>();
        final Set<String> blobs = new HashSet<>();
        volatile long touched = System.currentTimeMillis();

        RunState(String owner) { this.owner = owner; }
    }

    private record Request(int seq, List<String> hashes) { }

    /** Agent runs only: standalone helper scopes ("model:…") and system work have no user-visible run. */
    private RunState state(String runId, String owner) {
        if (runId == null || runId.isBlank() || owner == null || owner.isBlank() || runId.startsWith("model:")
                || ModelInvocationScope.isSystemOwner(owner)) return null;
        if (runs.size() > MAX_RUNS) evictStale();
        RunState state = runs.computeIfAbsent(runId, id -> {
            // A plan review pauses a run and a later request resumes it under the same id.
            RunState created = new RunState(owner);
            Integer finished = finishedSeq.get(id);
            created.seq.set(Math.max(finished == null ? 0 : finished, sink.lastSeq(id)));
            return created;
        });
        if (!state.owner.equals(owner)) return null;
        state.touched = System.currentTimeMillis();
        return state;
    }

    private void evictStale() {
        long cutoff = System.currentTimeMillis() - STALE_MILLIS;
        runs.values().removeIf(state -> state.touched < cutoff);
    }

    public void model(RunTrace.ModelExchange exchange) {
        try {
            if (exchange == null || exchange.request() == null) return;
            RunState state = state(exchange.runId(), exchange.owner());
            if (state == null) return;
            int seq = state.seq.incrementAndGet();
            List<TraceSink.Blob> blobs = new ArrayList<>();
            ObjectNode payload = JSON.createObjectNode();
            ObjectNode request = payload.putObject("request");
            List<?> messages = exchange.request().get("messages") instanceof List<?> list ? list : List.of();
            List<String> hashes = new ArrayList<>(messages.size());
            for (Object message : messages) hashes.add(hash(JSON.writeValueAsString(message)));
            int prefix = 0;
            Integer base = null;
            synchronized (state) {
                for (Request earlier : state.recent) {
                    int common = commonPrefix(earlier.hashes(), hashes);
                    if (common > prefix) { prefix = common; base = earlier.seq(); }
                }
                state.recent.addFirst(new Request(seq, hashes));
                while (state.recent.size() > RECENT_REQUESTS) state.recent.removeLast();
            }
            if (prefix < 2) { prefix = 0; base = null; }
            if (base != null) request.putObject("base").put("seq", base).put("prefix", prefix);
            request.put("messageCount", messages.size());
            ArrayNode stored = request.putArray("messages");
            for (int i = prefix; i < messages.size(); i++) stored.add(message(state, exchange.runId(), messages.get(i), blobs));
            for (Map.Entry<String, Object> entry : exchange.request().entrySet()) {
                if ("messages".equals(entry.getKey()) || "tools".equals(entry.getKey())) continue;
                request.set(entry.getKey(), JSON.valueToTree(entry.getValue()));
            }
            if (exchange.request().get("tools") instanceof List<?> tools && !tools.isEmpty()) {
                request.set("tools", blob(state, exchange.runId(), JSON.writeValueAsString(tools), blobs));
                ArrayNode names = request.putArray("toolNames");
                for (Object tool : tools) {
                    JsonNode node = JSON.valueToTree(tool);
                    names.add(node.path("function").path("name").asText(node.path("name").asText("")));
                }
            }
            RunTrace.ModelReply reply = exchange.reply();
            String model = String.valueOf(exchange.request().getOrDefault("model", ""));
            if (reply != null) {
                ObjectNode out = payload.putObject("reply");
                if (reply.model() != null) out.put("model", reply.model());
                putText(state, exchange.runId(), out, "content", reply.content(), blobs);
                putText(state, exchange.runId(), out, "reasoning", reply.reasoning(), blobs);
                if (reply.toolCalls() != null && !reply.toolCalls().isEmpty()) {
                    out.set("toolCalls", externalize(state, exchange.runId(), JSON.valueToTree(reply.toolCalls()), blobs));
                    synchronized (state) {
                        for (Map<String, Object> call : reply.toolCalls()) {
                            if (call.get("id") instanceof String id && !id.isBlank()) state.toolParents.put(id, seq);
                        }
                        trim(state.toolParents);
                    }
                }
                if (reply.finishReason() != null) out.put("finishReason", reply.finishReason());
                if (reply.model() != null && !reply.model().isBlank()) model = reply.model();
            }
            payload.put("httpStatus", exchange.httpStatus());
            if (exchange.purpose() != null) payload.put("purpose", exchange.purpose());
            if (exchange.error() != null) payload.put("error", clip(exchange.error(), 8000));
            sink.write(new TraceSink.Node(exchange.runId(), seq, state.owner, "MODEL", clip(model, 160), step(exchange.step()),
                    null, exchange.outcome(), exchange.apiId(), reply == null ? null : reply.inputTokens(),
                    reply == null ? null : reply.outputTokens(), exchange.startedAt(), Math.max(0, exchange.durationMs()),
                    JSON.writeValueAsString(payload)), blobs);
        } catch (Exception failure) {
            log.debug("[RunTrace] model request not recorded runId={}: {}", exchange == null ? null : exchange.runId(), failure.toString());
        }
    }

    public void tool(RunTrace.ToolExchange exchange) {
        try {
            if (exchange == null) return;
            RunState state = state(exchange.runId(), exchange.owner());
            if (state == null) return;
            int seq = state.seq.incrementAndGet();
            Integer parent;
            synchronized (state) { parent = exchange.callId() == null ? null : state.toolParents.get(exchange.callId()); }
            List<TraceSink.Blob> blobs = new ArrayList<>();
            ObjectNode payload = JSON.createObjectNode();
            if (exchange.callId() != null) payload.put("callId", exchange.callId());
            payload.put("source", exchange.source());
            if (exchange.stepLabel() != null) payload.put("stepLabel", exchange.stepLabel());
            putText(state, exchange.runId(), payload, "arguments", exchange.arguments(), blobs);
            putText(state, exchange.runId(), payload, "result", exchange.result(), blobs);
            payload.put("resultChars", exchange.result() == null ? 0 : exchange.result().length());
            if (exchange.returnedChars() >= 0) payload.put("returnedChars", exchange.returnedChars());
            if (exchange.error() != null) payload.put("error", clip(exchange.error(), 8000));
            sink.write(new TraceSink.Node(exchange.runId(), seq, state.owner, "TOOL", clip(exchange.name(), 160),
                    step(exchange.step()), parent, exchange.status(), null, null, null, exchange.startedAt(),
                    Math.max(0, exchange.durationMs()), JSON.writeValueAsString(payload)), blobs);
        } catch (Exception failure) {
            log.debug("[RunTrace] tool call not recorded runId={}: {}", exchange == null ? null : exchange.runId(), failure.toString());
        }
    }

    public void event(String runId, String eventType, String payloadJson) {
        try {
            if (eventType == null || !EVENTS.contains(eventType) || runId == null) return;
            RunState state = runs.get(runId);
            if (state == null) {
                RunBudgetRegistry.State budget = RunBudgetRegistry.find(runId);
                if (budget == null) return;
                state = state(runId, budget.ownerUserId());
                if (state == null) return;
            }
            JsonNode data;
            try {
                data = payloadJson == null ? JSON.nullNode() : JSON.readTree(payloadJson);
            } catch (Exception notJson) {
                data = JSON.getNodeFactory().textNode(payloadJson);
            }
            if ("message".equals(eventType) && !Set.of("summary", "error", "complete", "cancelled")
                    .contains(data.path("type").asText())) return;
            int seq = state.seq.incrementAndGet();
            List<TraceSink.Blob> blobs = new ArrayList<>();
            ObjectNode payload = JSON.createObjectNode();
            payload.set("data", externalize(state, runId, data, blobs));
            String step = data.path("stepId").asText(data.path("step").asText(null));
            sink.write(new TraceSink.Node(runId, seq, state.owner, "EVENT", eventType, step(step), null, null, null,
                    null, null, System.currentTimeMillis(), 0, JSON.writeValueAsString(payload)), blobs);
        } catch (Exception failure) {
            log.debug("[RunTrace] event not recorded runId={} type={}: {}", runId, eventType, failure.toString());
        }
    }

    public void finishRun(String runId) {
        if (runId == null) return;
        RunState state = runs.remove(runId);
        if (state == null) return;
        synchronized (finishedSeq) {
            finishedSeq.put(runId, state.seq.get());
            Iterator<String> oldest = finishedSeq.keySet().iterator();
            while (finishedSeq.size() > MAX_FINISHED && oldest.hasNext()) { oldest.next(); oldest.remove(); }
        }
    }

    /** One request message; system prompts and long texts become per-run blobs, image bytes are not kept. */
    private JsonNode message(RunState state, String runId, Object message, List<TraceSink.Blob> blobs) throws Exception {
        JsonNode node = JSON.valueToTree(message);
        if (!(node instanceof ObjectNode object)) return node;
        JsonNode content = object.get("content");
        if (content != null && content.isTextual() && "system".equals(object.path("role").asText())
                && content.asText().length() > SYSTEM_BLOB_CHARS) {
            object.set("content", blob(state, runId, content.asText(), blobs));
        } else if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                if (part instanceof ObjectNode piece && piece.path("image_url").path("url").asText("").startsWith("data:")) {
                    String url = piece.path("image_url").path("url").asText();
                    ((ObjectNode) piece.get("image_url")).put("url", "[图片数据未保存，" + url.length() + " 字符]");
                }
            }
        }
        return externalize(state, runId, object, blobs);
    }

    private JsonNode externalize(RunState state, String runId, JsonNode node, List<TraceSink.Blob> blobs) {
        if (node == null) return null;
        if (node.isTextual() && node.asText().length() > INLINE_CHARS) return blob(state, runId, node.asText(), blobs);
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<Map.Entry<String, JsonNode>> replaced = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = externalize(state, runId, field.getValue(), blobs);
                if (value != field.getValue()) replaced.add(Map.entry(field.getKey(), value));
            }
            replaced.forEach(entry -> object.set(entry.getKey(), entry.getValue()));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = externalize(state, runId, array.get(i), blobs);
                if (value != array.get(i)) array.set(i, value);
            }
        }
        return node;
    }

    private void putText(RunState state, String runId, ObjectNode target, String field, String text, List<TraceSink.Blob> blobs) {
        if (text == null) return;
        if (text.length() > INLINE_CHARS) target.set(field, blob(state, runId, text, blobs));
        else target.put(field, text);
    }

    private ObjectNode blob(RunState state, String runId, String text, List<TraceSink.Blob> blobs) {
        String hash = hash(text);
        boolean added;
        synchronized (state) { added = state.blobs.add(hash); }
        if (added) blobs.add(new TraceSink.Blob(runId, hash, text));
        return JSON.createObjectNode().put("$blob", hash).put("chars", text.length());
    }

    static String step(String label) {
        if (label == null || label.isBlank()) return null;
        return clip(ATTEMPT.matcher(label.strip()).replaceFirst(""), 160);
    }

    static int commonPrefix(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size()), i = 0;
        while (i < n && a.get(i).equals(b.get(i))) i++;
        return i;
    }

    private static void trim(Map<String, Integer> parents) {
        Iterator<String> oldest = parents.keySet().iterator();
        while (parents.size() > MAX_TOOL_PARENTS && oldest.hasNext()) { oldest.next(); oldest.remove(); }
    }

    static String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String clip(String text, int max) {
        if (text == null || text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
