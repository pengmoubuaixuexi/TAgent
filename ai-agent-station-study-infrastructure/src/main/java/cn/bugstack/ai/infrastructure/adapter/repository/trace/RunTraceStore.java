package cn.bugstack.ai.infrastructure.adapter.repository.trace;

import cn.bugstack.ai.domain.agent.service.trace.RunTrace;
import cn.bugstack.ai.domain.agent.service.trace.TraceRecorder;
import cn.bugstack.ai.domain.agent.service.trace.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * MySQL storage of run traces. Nodes are queued and written in batches by one daemon thread; a full
 * queue or a failed write drops trace data with a warning and never affects the run.
 */
@Slf4j
@Component
public class RunTraceStore implements TraceSink {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 200;
    /** A node detail never returns more than this much text in total. */
    private static final int MAX_DETAIL_CHARS = 8 * 1024 * 1024;

    private record Item(Node node, List<Blob> blobs) { }

    private final JdbcTemplate db;
    private final BlockingQueue<Item> queue;
    private final Map<String, Integer> queuedSeq = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastWarning;
    private volatile boolean running = true;
    private Thread writer;
    private TraceRecorder recorder;

    @Value("${agent.trace.enabled:true}")
    private boolean enabled = true;

    public RunTraceStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db,
                         @Value("${agent.trace.queue-capacity:20000}") int capacity) {
        this.db = db;
        this.queue = new ArrayBlockingQueue<>(Math.max(100, capacity));
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[RunTrace] disabled (agent.trace.enabled=false)");
            return;
        }
        writer = new Thread(this::drain, "run-trace-writer");
        writer.setDaemon(true);
        writer.start();
        recorder = new TraceRecorder(this);
        RunTrace.install(recorder);
    }

    @PreDestroy
    public void stop() {
        if (recorder != null) RunTrace.uninstall(recorder);
        running = false;
        if (writer != null) {
            try { writer.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        drainNow();
    }

    /** The recorder bound to this store, for tests that drive it without the static bridge. */
    public TraceRecorder recorder() {
        if (recorder == null) recorder = new TraceRecorder(this);
        return recorder;
    }

    @Override
    public void write(Node node, List<Blob> blobs) {
        queuedSeq.merge(node.runId(), node.seq(), Math::max);
        if (!queue.offer(new Item(node, blobs == null ? List.of() : blobs))) {
            dropped.incrementAndGet();
            warn("queue full; trace nodes dropped so far: " + dropped.get());
        }
    }

    @Override
    public int lastSeq(String runId) {
        int queued = queuedSeq.getOrDefault(runId, 0);
        try {
            Integer stored = db.queryForObject("SELECT COALESCE(MAX(seq),0) FROM ai_run_trace WHERE run_id=?", Integer.class, runId);
            return Math.max(queued, stored == null ? 0 : stored);
        } catch (DataAccessException unavailable) {
            return queued;
        }
    }

    private void drain() {
        List<Item> batch = new ArrayList<>(BATCH);
        while (running || !queue.isEmpty()) {
            try {
                Item first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                flush(batch);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                warn("write failed: " + failure);
            } finally {
                batch.clear();
            }
        }
    }

    /** Writes everything queued so far on the calling thread; used at shutdown and by tests. */
    public int drainNow() {
        int written = 0;
        List<Item> batch = new ArrayList<>(BATCH);
        while (queue.drainTo(batch, BATCH) > 0) {
            written += batch.size();
            flush(batch);
            batch.clear();
        }
        return written;
    }

    private void flush(List<Item> batch) {
        List<Object[]> blobs = new ArrayList<>();
        List<Object[]> nodes = new ArrayList<>(batch.size());
        for (Item item : batch) {
            for (Blob blob : item.blobs()) {
                blobs.add(new Object[]{blob.runId(), blob.hash(), gzip(blob.content()), blob.content().length()});
            }
            Node n = item.node();
            nodes.add(new Object[]{n.runId(), n.seq(), n.owner(), n.kind(), n.name(), n.step(), n.parentSeq(), n.status(),
                    n.apiId(), n.inputTokens(), n.outputTokens(), new Timestamp(n.startedAt()), n.durationMs(),
                    gzip(n.payload()), n.payload().length()});
        }
        try {
            if (!blobs.isEmpty()) db.batchUpdate("INSERT IGNORE INTO ai_run_trace_blob(run_id,hash,content,bytes) VALUES(?,?,?,?)", blobs);
            db.batchUpdate("""
                    INSERT IGNORE INTO ai_run_trace(run_id,seq,owner_user_id,kind,name,step,parent_seq,status,api_id,
                        input_tokens,output_tokens,started_at,duration_ms,payload,payload_bytes)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", nodes);
        } catch (DataAccessException failure) {
            warn("batch of " + nodes.size() + " nodes not stored: " + failure.getMostSpecificCause());
        } finally {
            for (Item item : batch) queuedSeq.computeIfPresent(item.node().runId(), (run, max) -> max <= item.node().seq() ? null : max);
        }
    }

    // ------------------------------------------------------------------ reading

    /** Node list of a run with short previews, in order. Ownership is checked by the caller. */
    public List<Map<String, Object>> nodes(String runId) {
        return db.query("""
                SELECT seq,kind,name,step,parent_seq,status,api_id,input_tokens,output_tokens,started_at,duration_ms,payload,payload_bytes
                FROM ai_run_trace WHERE run_id=? ORDER BY seq""", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", rs.getInt("seq"));
            row.put("kind", rs.getString("kind"));
            row.put("name", rs.getString("name"));
            row.put("step", rs.getString("step"));
            row.put("parentSeq", rs.getObject("parent_seq"));
            row.put("status", rs.getString("status"));
            row.put("apiId", rs.getString("api_id"));
            row.put("inputTokens", rs.getObject("input_tokens"));
            row.put("outputTokens", rs.getObject("output_tokens"));
            row.put("startedAt", rs.getTimestamp("started_at").getTime());
            row.put("durationMs", rs.getLong("duration_ms"));
            row.put("bytes", rs.getInt("payload_bytes"));
            row.put("summary", summary(rs.getString("kind"), parse(gunzip(rs.getBytes("payload")))));
            return row;
        }, runId);
    }

    /** Whether the run is a case of an evaluation whose dataset the user owns (isolated evaluations run as another user). */
    public boolean evaluatedBy(String runId, String userId) {
        try {
            Integer count = db.queryForObject("""
                    SELECT COUNT(*) FROM ai_eval_case_result r JOIN ai_eval_run e ON e.eval_run_id=r.eval_run_id
                    JOIN ai_eval_dataset d ON d.dataset_id=e.dataset_id WHERE r.agent_run_id=? AND d.owner_user_id=?""",
                    Integer.class, runId, userId);
            return count != null && count > 0;
        } catch (DataAccessException unavailable) {
            return false;
        }
    }

    /** Owner of the run's trace, or null when nothing was recorded. */
    public String owner(String runId) {
        List<String> owners = db.queryForList("SELECT owner_user_id FROM ai_run_trace WHERE run_id=? LIMIT 1", String.class, runId);
        return owners.isEmpty() ? null : owners.get(0);
    }

    /**
     * One node with everything restored: a model request's complete message list (rebuilt from its
     * base requests), tool definitions, reasoning, the reply; a tool call's arguments and full result.
     */
    public ObjectNode node(String runId, int seq) {
        ObjectNode payload = payload(runId, seq);
        if (payload == null) return null;
        Map<String, String> blobCache = new HashMap<>();
        if (payload.path("request").isObject()) {
            ObjectNode request = (ObjectNode) payload.get("request");
            ArrayNode messages = messages(runId, seq, request, new HashMap<>(), 0);
            request.set("messages", messages);
            request.remove("base");
            if (messages.size() < request.path("messageCount").asInt(messages.size())) {
                request.put("incomplete", "部分早期消息未能还原（对应的请求记录缺失）");
            }
        }
        int[] budget = {MAX_DETAIL_CHARS};
        resolveBlobs(runId, payload, blobCache, budget);
        JsonNode tools = payload.path("request").path("tools");
        if (tools.isTextual()) {
            try { ((ObjectNode) payload.get("request")).set("tools", JSON.readTree(tools.asText())); } catch (IOException ignored) { }
        }
        return payload;
    }

    /** A node as stored: a model request keeps only the messages it added; long texts are restored. */
    public ObjectNode storedNode(String runId, int seq, int maxChars) {
        ObjectNode payload = payload(runId, seq);
        if (payload != null) resolveBlobs(runId, payload, new HashMap<>(), new int[]{Math.max(1, maxChars)});
        return payload;
    }

    private ObjectNode payload(String runId, int seq) {
        List<byte[]> rows = db.query("SELECT payload FROM ai_run_trace WHERE run_id=? AND seq=?", (rs, i) -> rs.getBytes(1), runId, seq);
        if (rows.isEmpty()) return null;
        JsonNode node = parse(gunzip(rows.get(0)));
        return node instanceof ObjectNode object ? object : null;
    }

    /** Messages of a request: the base request's first {@code prefix} messages, then the stored ones. */
    private ArrayNode messages(String runId, int seq, ObjectNode request, Map<Integer, ArrayNode> memo, int depth) {
        ArrayNode cached = memo.get(seq);
        if (cached != null) return cached;
        ArrayNode result = JSON.createArrayNode();
        JsonNode base = request.path("base");
        if (base.isObject() && depth < 500) {
            int baseSeq = base.path("seq").asInt(), prefix = base.path("prefix").asInt();
            ObjectNode basePayload = baseSeq < seq ? payload(runId, baseSeq) : null;
            if (basePayload != null && basePayload.path("request").isObject()) {
                ArrayNode earlier = messages(runId, baseSeq, (ObjectNode) basePayload.get("request"), memo, depth + 1);
                for (int i = 0; i < prefix && i < earlier.size(); i++) result.add(earlier.get(i).deepCopy());
            }
        }
        for (JsonNode message : request.path("messages")) result.add(message);
        memo.put(seq, result);
        return result;
    }

    private void resolveBlobs(String runId, JsonNode node, Map<String, String> cache, int[] budget) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<Map.Entry<String, JsonNode>> replaced = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isObject() && value.has("$blob")) {
                    replaced.add(Map.entry(field.getKey(), JSON.getNodeFactory().textNode(blob(runId, value.path("$blob").asText(), cache, budget))));
                } else {
                    resolveBlobs(runId, value, cache, budget);
                }
            }
            replaced.forEach(entry -> object.set(entry.getKey(), entry.getValue()));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isObject() && value.has("$blob")) {
                    array.set(i, JSON.getNodeFactory().textNode(blob(runId, value.path("$blob").asText(), cache, budget)));
                } else {
                    resolveBlobs(runId, value, cache, budget);
                }
            }
        }
    }

    private String blob(String runId, String hash, Map<String, String> cache, int[] budget) {
        String text = cache.computeIfAbsent(hash, h -> {
            List<byte[]> rows = db.query("SELECT content FROM ai_run_trace_blob WHERE run_id=? AND hash=?", (rs, i) -> rs.getBytes(1), runId, h);
            return rows.isEmpty() ? "[内容未保存]" : gunzip(rows.get(0));
        });
        if (budget[0] <= 0) return "[内容过长，已省略]";
        if (text.length() > budget[0]) {
            String cut = text.substring(0, budget[0]) + "\n[内容过长，以下已省略]";
            budget[0] = 0;
            return cut;
        }
        budget[0] -= text.length();
        return text;
    }

    /** A line for the node list: what was added, what came back, what failed. */
    static Map<String, Object> summary(String kind, JsonNode payload) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (payload == null) return summary;
        switch (kind == null ? "" : kind) {
            case "MODEL" -> {
                JsonNode request = payload.path("request"), reply = payload.path("reply");
                summary.put("messages", request.path("messageCount").asInt());
                summary.put("newMessages", request.path("messages").size());
                summary.put("tools", request.path("toolNames").size());
                if (payload.has("purpose")) summary.put("purpose", payload.path("purpose").asText());
                summary.put("httpStatus", payload.path("httpStatus").asInt());
                summary.put("reply", preview(reply.path("content"), 200));
                if (reply.path("reasoning").isTextual() || reply.path("reasoning").isObject()) summary.put("reasoning", true);
                List<String> calls = new ArrayList<>();
                for (JsonNode call : reply.path("toolCalls")) calls.add(call.path("function").path("name").asText());
                if (!calls.isEmpty()) summary.put("toolCalls", calls);
                if (payload.has("error")) summary.put("error", preview(payload.path("error"), 300));
            }
            case "TOOL" -> {
                summary.put("source", payload.path("source").asText());
                summary.put("arguments", preview(payload.path("arguments"), 200));
                summary.put("result", preview(payload.path("result"), 200));
                summary.put("resultChars", payload.path("resultChars").asInt());
                if (payload.has("stepLabel")) summary.put("stepLabel", payload.path("stepLabel").asText());
                if (payload.has("error")) summary.put("error", preview(payload.path("error"), 300));
            }
            default -> {
                JsonNode data = payload.path("data");
                for (String field : List.of("stepId", "displayName", "agentName", "type", "notice", "summary", "status")) {
                    if (data.path(field).isTextual()) summary.put(field, preview(data.path(field), 200));
                }
                summary.put("data", preview(data, 300));
            }
        }
        return summary;
    }

    private static String preview(JsonNode value, int max) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        String text = value.isObject() && value.has("$blob") ? "[长文本 " + value.path("chars").asInt() + " 字符]"
                : value.isTextual() ? value.asText() : value.toString();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ deletion and retention

    public void deleteRun(String runId) {
        db.update("DELETE FROM ai_run_trace_blob WHERE run_id=?", runId);
        db.update("DELETE FROM ai_run_trace WHERE run_id=?", runId);
    }

    /** Traces of a deleted conversation: they hold its complete messages. */
    public int deleteSession(String userId, String sessionId) {
        List<String> runs = db.queryForList("SELECT run_id FROM ai_run_history WHERE user_id=? AND session_id=?", String.class, userId, sessionId);
        for (String run : runs) deleteRun(run);
        return runs.size();
    }

    public int deleteUser(String userId) {
        List<String> runs = db.queryForList("SELECT DISTINCT run_id FROM ai_run_trace WHERE owner_user_id=?", String.class, userId);
        for (String run : runs) deleteRun(run);
        return runs.size();
    }

    /**
     * Removes traces whose last node is older than {@code days}. Runs with feedback, used by an
     * evaluation or referenced by an improvement suggestion are kept.
     */
    public int purgeOlderThan(int days, int pageSize) {
        if (days <= 0) return 0;
        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
        int page = Math.max(1, pageSize), removed = 0;
        String after = "";
        while (true) {
            // Paged by run id, so kept runs never hide later candidates.
            List<String> candidates = db.queryForList("SELECT run_id FROM ai_run_trace WHERE run_id>? GROUP BY run_id "
                    + "HAVING MAX(started_at)<? ORDER BY run_id LIMIT " + page, String.class, after, cutoff);
            for (String run : candidates) {
                if (referenced(run)) continue;
                deleteRun(run);
                removed++;
            }
            if (candidates.size() < page) return removed;
            after = candidates.get(candidates.size() - 1);
        }
    }

    private boolean referenced(String runId) {
        for (String sql : List.of("SELECT COUNT(*) FROM ai_run_feedback WHERE run_id=?",
                "SELECT COUNT(*) FROM ai_eval_case_result WHERE agent_run_id=?",
                "SELECT COUNT(*) FROM ai_improvement_suggestion WHERE source_run_id=?")) {
            try {
                Integer count = db.queryForObject(sql, Integer.class, runId);
                if (count != null && count > 0) return true;
            } catch (DataAccessException tableMissing) {
                // A table of a later migration may not exist yet; it then holds no references.
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000) {
            lastWarning = now;
            log.warn("[RunTrace] {}", message);
        }
    }

    static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, text.length() / 4));
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String gunzip(byte[] bytes) {
        if (bytes == null) return null;
        try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonNode parse(String text) {
        if (text == null) return null;
        try { return JSON.readTree(text); } catch (IOException e) { return null; }
    }
}
