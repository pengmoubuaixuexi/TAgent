package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryRecall;

/**
 * H2-A：记忆证据 emitter —— 让用户看到"本轮用了哪些记忆"。
 * <p>
 * 跟 {@link RagEvidenceEmitter} 同款套路：复用 G1-A {@link ApprovalChannelRegistry} 通道，emit
 * {@code memory_evidence} SSE 事件，零侵入现有流。
 * <p>
 * <b>设计原则</b>（按 Codex 第 57 轮校正版 H2 方案）：
 * <ul>
 *   <li>不新增 {@code memory_inject_enabled} —— 记忆是否注入由 advisor 链是否挂载控制（advisor 本就可拆卸）</li>
 *   <li>只做 explain/evidence 展示：{@code agent.memory.explain-enabled} 控制是否 emit，<b>不影响</b>注入</li>
 *   <li>所有异常吞掉，advisor 失败 ≠ 主回答失败</li>
 * </ul>
 * <p>
 * <b>SSE 事件契约</b>：
 * <pre>
 * event: memory_evidence
 * data: {"sessionId":"...","memoryType":"long_term","items":[{"topic":"skill:Java","content":"..."}],"timestamp":...}
 * data: {"sessionId":"...","memoryType":"episodic","items":[{"kind":"current","content":"..."},{"kind":"other","content":"..."}],"timestamp":...}
 * </pre>
 */
@Slf4j
@Component
public class MemoryEvidenceEmitter {

    public static final String TYPE_LONG_TERM = "long_term";
    public static final String TYPE_EPISODIC = "episodic";
    public static final String TYPE_CHAT_SUMMARY = "chat_summary";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** explain 开关：false → 不 emit 任何 memory_evidence（不影响记忆注入本身）。 */
    @Value("${agent.memory.explain-enabled:true}")
    private boolean explainEnabled;

    @Resource
    private ApprovalChannelRegistry approvalChannelRegistry;

    @Resource
    private RunEventPublisher runEventPublisher;

    public MemoryEvidenceEmitter() {}

    public MemoryEvidenceEmitter(ApprovalChannelRegistry registry, boolean explainEnabled) {
        this.approvalChannelRegistry = registry;
        this.explainEnabled = explainEnabled;
    }

    /**
     * emit 长期记忆证据。
     *
     * @param sessionId    会话 ID；null/blank → 静默跳过
     * @param profileLines LTM 召回的记忆行，格式 {@code [topic] content} 或纯文本；null/empty → 不 emit
     */
    public void emitLongTermEvidence(String sessionId, List<String> profileLines) {
        if (!explainEnabled) return;
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            if (profileLines == null || profileLines.isEmpty()) return;
            ResponseBodyEmitter emitter = lookupEmitter(sessionId);
            if (emitter == null && !hasRun(sessionId)) return;

            List<Map<String, Object>> items = new ArrayList<>(profileLines.size());
            for (String line : profileLines) {
                if (line == null || line.isBlank()) continue;
                items.add(parseTopicLine(line));
            }
            if (items.isEmpty()) return;
            sendEvent(sessionId, TYPE_LONG_TERM, items, emitter);
        } catch (Exception e) {
            log.debug("[MemoryEvidence] long_term emit aborted sessionId={} err={}", sessionId, e.toString());
        }
    }

    public void emitLongTermEvidenceDetailed(String sessionId, List<LongTermMemoryRecall> recalls) {
        if (!explainEnabled || recalls == null || recalls.isEmpty()) return;
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            ResponseBodyEmitter emitter = lookupEmitter(sessionId);
            if (emitter == null && !hasRun(sessionId)) return;
            List<Map<String, Object>> items = new ArrayList<>();
            for (LongTermMemoryRecall recall : recalls) {
                if (recall == null || recall.getContent() == null || recall.getContent().isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("topic", recall.getTopic() == null ? "other" : recall.getTopic());
                item.put("content", recall.getContent());
                item.put("memoryKind", recall.getKind());
                if (recall.getMemoryId() != null) item.put("memoryId", recall.getMemoryId());
                if (LongTermMemoryRecall.KIND_RELEVANT.equals(recall.getKind()) && recall.getSimilarity() != null) {
                    item.put("similarity", recall.getSimilarity());
                }
                items.add(item);
            }
            if (!items.isEmpty()) sendEvent(sessionId, TYPE_LONG_TERM, items, emitter);
        } catch (Exception error) {
            log.debug("[MemoryEvidence] detailed long_term emit aborted sessionId={} err={}", sessionId, error.toString());
        }
    }

    public void emitChatSummaryEvidence(String sessionId, String conversationId, String summary) {
        if (!explainEnabled || summary == null || summary.isBlank()) return;
        try {
            ResponseBodyEmitter emitter = lookupEmitter(sessionId);
            if (emitter == null && !hasRun(sessionId)) return;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "rolling_summary");
            item.put("content", summary.trim());
            if (conversationId != null) item.put("conversationId", conversationId);
            sendEvent(sessionId, TYPE_CHAT_SUMMARY, List.of(item), emitter);
        } catch (Exception error) {
            log.debug("[MemoryEvidence] chat summary emit aborted sessionId={} err={}", sessionId, error.toString());
        }
    }

    /**
     * emit 跨会话摘要记忆证据。
     *
     * @param sessionId         会话 ID；null/blank → 静默跳过
     * @param currentSummary    当前会话摘要；null/blank 则不放 current 项
     * @param otherEpisodes     其他会话摘要列表；null/empty 则不放 other 项
     */
    public void emitEpisodicEvidence(String sessionId, String currentSummary, List<String> otherEpisodes) {
        if (!explainEnabled) return;
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            ResponseBodyEmitter emitter = lookupEmitter(sessionId);
            if (emitter == null && !hasRun(sessionId)) return;

            List<Map<String, Object>> items = new ArrayList<>();
            if (currentSummary != null && !currentSummary.isBlank()) {
                items.add(item("current", currentSummary.trim()));
            }
            if (otherEpisodes != null) {
                for (String ep : otherEpisodes) {
                    if (ep != null && !ep.isBlank()) items.add(item("other", ep.trim()));
                }
            }
            if (items.isEmpty()) return;
            sendEvent(sessionId, TYPE_EPISODIC, items, emitter);
        } catch (Exception e) {
            log.debug("[MemoryEvidence] episodic emit aborted sessionId={} err={}", sessionId, e.toString());
        }
    }

    /** 解析 {@code [topic] content} 行 → {topic, content}；无方括号则 topic=other。 */
    static Map<String, Object> parseTopicLine(String line) {
        String topic = "other";
        String content = line.trim();
        if (content.startsWith("[")) {
            int close = content.indexOf("] ");
            if (close > 1) {
                topic = content.substring(1, close);
                content = content.substring(close + 2);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", topic);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> item(String kind, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("content", content);
        return m;
    }

    private ResponseBodyEmitter lookupEmitter(String sessionId) {
        if (approvalChannelRegistry == null) return null;
        return approvalChannelRegistry.get(sessionId);
    }

    private boolean hasRun(String sessionId) {
        return runEventPublisher != null && runEventPublisher.currentRunId(sessionId) != null;
    }

    private void sendEvent(String sessionId, String memoryType, List<Map<String, Object>> items, ResponseBodyEmitter emitter) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("memoryType", memoryType);
        data.put("items", items);
        data.put("timestamp", System.currentTimeMillis());
        String payload;
        try {
            payload = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.debug("[MemoryEvidence] json serialize failed sessionId={} err={}", sessionId, e.toString());
            return;
        }
        if (hasRun(sessionId)) {
            runEventPublisher.publishCurrent(sessionId, "memory_evidence", data);
            return;
        }
        try {
            emitter.send("event: memory_evidence\ndata: " + payload + "\n\n");
        } catch (Exception e) {
            log.debug("[MemoryEvidence] emit failed sessionId={} err={}", sessionId, e.toString());
        }
    }
}
