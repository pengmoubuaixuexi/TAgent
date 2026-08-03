package cn.bugstack.ai.domain.agent.service.evidence;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.ToolEvidenceRecord;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Builds an Evidence Map only when the user explicitly requests it. */
@Slf4j
@Service
public class EvidenceMapService {

    private static final String EVIDENCE_MAP_VERSION = "v4";
    private static final int MAX_EVIDENCE_ITEMS_FOR_MODEL = 60;
    private static final int MAX_CONTENT_CHARS_PER_ITEM = 3500;
    private static final int MAX_PROMPT_CHARS = 80_000;
    private static final int MAX_CLAIM_CANDIDATES = 12;
    private static final int MAX_BLOCK_CHARS = 5000;

    private static final String VERIFICATION_PROTOCOL_V4 = """

            IMPORTANT V4 OUTPUT CONTRACT (this replaces the earlier block-level links shape):
            - Keep every supplied block claimId exactly once.
            - For each block, identify 2-6 material verification checks. A short single-fact block may have one.
            - Each check.text must be copied verbatim from that block. Prefer a complete sentence, bullet, or table row; never use only a heading.
            - Keep unsupported checks with links=[]; do not omit them.
            - A check may use multiple evidence links. Each link may contain 1-6 exact source fragments in quotes[].
            - Separate fragments are allowed when structured tool output stores related values in different arrays/fields.
            - Weather evidence may only support weather-related check text; ticket evidence may only support the queried direction/date/train/price it actually contains.
            - One-way ticket evidence cannot fully support a round-trip claim. One attraction detail cannot fully support a multi-day itinerary block.
            - Output JSON only:
              {"claims":[{"claimId":"B1","checks":[{"text":"verbatim block fragment","links":[{"evidenceId":"...","relation":"supports|partial_support|specified_by_user|context","quotes":["exact fragment 1","exact fragment 2"],"note":"optional"}]}]}]}
            """;

    private static final String PROMPT = """
            你是 Evidence Map 映射器。后端已经把最终回答按 Markdown 结构切成带编号的业务块。请逐块连接到真实来源。

            严格规则：
            1. 每个候选块都必须返回一次；claimId 直接使用候选块编号，不要复制或改写块内容。
            2. 只能使用证据目录中存在的 evidenceId。
            3. quote 必须逐字复制对应来源的调用参数或内容中的短片段，不能概括或补写。
            4. user_request、user_decision 只能使用 specified_by_user，表示用户给定的任务前提，不是外部事实证明。
            5. RAG、tool 对块内核心内容可直接核验时使用 supports；只覆盖部分内容、需要单位换算/合计/推导、或只查了单程却回答往返时，必须使用 partial_support。
            6. long_term、episodic、chat_summary 只能使用 context，表示个性化或上下文来源，不能冒充事实证明。
            7. 即使某个块没有来源，也必须保留并让 links=[]。不要强行绑定弱相关来源。
            8. 每个块最多连接 3 条最强来源。note 只说明“为什么是部分/推导依据”或“还缺什么”，不能把 note 当证据。
            9. 只输出 JSON，不要 markdown，不要额外解释：
            {"claims":[{"claimId":"B1","links":[{"evidenceId":"...","relation":"supports|partial_support|specified_by_user|context","quote":"来源原文","note":"可选的简短关联说明"}]}]}

            【候选业务块】
            %s

            【证据目录】
            %s
            """;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private LlmCallGateway llmCallGateway;

    @Autowired(required = false)
    private RunSnapshotService runSnapshotService;

    @Value("${agent.evidence-map.client-id:${agent.intent-router.client-id:router-small}}")
    private String clientId;

    /** Coalesce duplicate clicks/tabs on this application instance into one paid model call. */
    private final ConcurrentMap<String, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();

    public Map<String, Object> generate(String runId, String sessionId, String requestedFinalAnswer) {
        return generate(runId, sessionId, requestedFinalAnswer, false);
    }

    public Map<String, Object> generate(String runId, String sessionId, String requestedFinalAnswer,
                                        boolean forceRegenerate) {
        long startedAt = System.currentTimeMillis();
        if (runSnapshotService == null) throw new IllegalStateException("Run snapshot service is not available");
        Optional<RunSnapshot> snapshotResult = runSnapshotService.find(runId);
        if (snapshotResult.isEmpty()) {
            Map<String, Object> retained = runSnapshotService.findEvidenceMap(runId).orElse(null);
            if (!forceRegenerate) {
                if (retained != null) {
                    retained.put("cached", true);
                    retained.put("snapshotAvailable", false);
                    retained.put("regenerateAvailable", false);
                    log.info("[EvidenceMap] retained cache hit after snapshot expiry runId={} latencyMs={}",
                            runId, System.currentTimeMillis() - startedAt);
                    return retained;
                }
                throw new IllegalArgumentException("运行证据已过期，且此前没有可用的证据地图");
            }
            throw new IllegalArgumentException(retained == null
                    ? "运行证据已过期，无法重新生成"
                    : "运行证据已过期，无法重新生成；已生成的证据地图仍会保留");
        }
        RunSnapshot snapshot = snapshotResult.get();
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(snapshot.getSessionId())) {
            throw new IllegalArgumentException("run snapshot not found");
        }
        String finalAnswer = nonBlank(requestedFinalAnswer) ? requestedFinalAnswer.trim() : findFinalAnswer(snapshot);
        if (!nonBlank(finalAnswer)) throw new IllegalArgumentException("final answer is empty");

        List<Map<String, Object>> evidences = collectEvidence(snapshot);
        List<Map<String, String>> claimCandidates = buildClaimCandidates(finalAnswer);
        String signature = evidenceSignature(finalAnswer, evidences);
        log.info("[EvidenceMap] requested runId={} sessionId={} forceRegenerate={} evidenceCount={} evidenceTypes={} signature={}",
                runId, sessionId, forceRegenerate, evidences.size(), evidenceTypeCounts(evidences), signature);
        if (!forceRegenerate) {
            Map<String, Object> cached = runSnapshotService.findEvidenceMap(runId, signature).orElse(null);
            if (cached != null) {
                cached.put("cached", true);
                cached.put("snapshotAvailable", true);
                cached.put("regenerateAvailable", true);
                log.info("[EvidenceMap] cache hit runId={} claims={} evidenceCount={} latencyMs={}",
                        runId, sizeOf(cached.get("claims")), evidences.size(), System.currentTimeMillis() - startedAt);
                return cached;
            }
        }

        String flightKey = runId + ':' + signature;
        CompletableFuture<Map<String, Object>> created = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> running = inFlight.putIfAbsent(flightKey, created);
        if (running != null) {
            log.info("[EvidenceMap] joined in-flight generation runId={} signature={}", runId, signature);
            return awaitInFlight(running);
        }
        try {
            Map<String, Object> generated = generateAndStore(
                    runId, finalAnswer, evidences, claimCandidates, signature, startedAt);
            created.complete(generated);
            return generated;
        } catch (Throwable error) {
            created.completeExceptionally(error);
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException(error);
        } finally {
            inFlight.remove(flightKey, created);
        }
    }

    private Map<String, Object> generateAndStore(String runId,
                                                  String finalAnswer,
                                                  List<Map<String, Object>> evidences,
                                                  List<Map<String, String>> claimCandidates,
                                                  String signature,
                                                  long startedAt) {
        if (evidences.isEmpty()) {
            Map<String, Object> empty = response(runId, finalAnswer, List.of(), evidences,
                    emptyDiagnostics(claimCandidates, evidences, "NO_EVIDENCE"), false);
            empty.put("snapshotAvailable", true);
            empty.put("regenerateAvailable", true);
            runSnapshotService.saveEvidenceMap(runId, signature, empty);
            log.info("[EvidenceMap] completed without evidence runId={} latencyMs={}",
                    runId, System.currentTimeMillis() - startedAt);
            return empty;
        }

        ChatClient client;
        try {
            client = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId), ChatClient.class);
        } catch (Exception error) {
            throw new IllegalStateException("Evidence Map small model is not ready: " + clientId, error);
        }

        String catalog = buildCatalog(evidences);
        String prompt = String.format(PROMPT, buildClaimCandidateCatalog(claimCandidates), catalog)
                + VERIFICATION_PROTOCOL_V4;
        log.info("[EvidenceMap] model call start runId={} model={} promptChars={} evidenceCount={}",
                runId, clientId, prompt.length(), evidences.size());
        ChatResponse chatResponse = llmCallGateway.call(() -> client.prompt(prompt).call());
        String modelOutput = chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText() : null;
        if (!nonBlank(modelOutput)) throw new IllegalStateException("Evidence Map model returned no result");

        ValidationResult validation = validateClaimsDetailed(modelOutput, finalAnswer, claimCandidates, evidences);
        List<Map<String, Object>> claims = validation.claims();
        Map<String, Object> generated = response(runId, finalAnswer, claims, evidences,
                validation.diagnostics(), false);
        generated.put("snapshotAvailable", true);
        generated.put("regenerateAvailable", true);
        // Save only after the replacement is complete and validated. If model generation
        // fails, the previous seven-day map remains untouched.
        runSnapshotService.saveEvidenceMap(runId, signature, generated);
        Map<String, Object> stats = castMap(generated.get("stats"));
        log.info("[EvidenceMap] completed runId={} blocks={} supported={} partial={} userSpecified={} unsupported={} links={} latencyMs={}",
                runId, claims.size(), stats.get("supportedClaims"), stats.get("partialClaims"),
                stats.get("userSpecifiedClaims"), stats.get("unsupportedClaims"), stats.get("links"),
                System.currentTimeMillis() - startedAt);
        return generated;
    }

    private Map<String, Object> awaitInFlight(CompletableFuture<Map<String, Object>> running) {
        try {
            return running.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException(cause == null ? error : cause);
        }
    }

    private Map<String, Object> response(String runId, String finalAnswer,
                                         List<Map<String, Object>> claims,
                                         List<Map<String, Object>> evidences,
                                         Map<String, Object> diagnostics,
                                         boolean cached) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", EVIDENCE_MAP_VERSION);
        result.put("runId", runId);
        result.put("generatedAt", Instant.now().toString());
        result.put("model", clientId);
        result.put("finalAnswer", finalAnswer);
        result.put("claims", claims);
        result.put("evidences", evidences);
        result.put("sourceCounts", evidenceTypeCounts(evidences));
        result.put("stats", buildStats(claims));
        result.put("diagnostics", diagnostics == null ? Map.of() : diagnostics);
        result.put("cached", cached);
        return result;
    }

    List<Map<String, Object>> collectEvidence(RunSnapshot snapshot) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        if (nonBlank(snapshot.getOriginalMessage())) {
            addEvidence(result, fingerprints, "user_request:" + shortHash(snapshot.getOriginalMessage()),
                    "user_request", "user", "用户原始请求", snapshot.getOriginalMessage().trim(), Map.of());
        }
        Map<String, String> questionByInputId = new LinkedHashMap<>();
        Set<String> persistedAnswers = new LinkedHashSet<>();
        if (snapshot.getTimelineEvents() != null) {
            for (RunEventRecord event : snapshot.getTimelineEvents()) {
                if (event == null || !nonBlank(event.getPayloadJson())) continue;
                try {
                    JSONObject payload = JSON.parseObject(event.getPayloadJson());
                    if ("rag_evidence".equals(event.getEventType())) {
                        addRagEvidence(result, fingerprints, payload);
                    } else if ("memory_evidence".equals(event.getEventType())) {
                        addMemoryEvidence(result, fingerprints, payload);
                    } else if ("user_input_required".equals(event.getEventType())) {
                        String inputId = payload.getString("inputId");
                        String question = userInputQuestion(payload);
                        if (nonBlank(inputId) && nonBlank(question)) questionByInputId.put(inputId, question);
                    } else if ("user_input_result".equals(event.getEventType())
                            && "ANSWERED".equalsIgnoreCase(payload.getString("status"))) {
                        String answer = payload.getString("answer");
                        if (nonBlank(answer)) {
                            persistedAnswers.add(normalizeAnswer(answer));
                            addUserDecisionEvidence(result, fingerprints, event.getEventId(),
                                    questionByInputId.get(payload.getString("inputId")), answer);
                        }
                    } else if (isLegacyAskUserAnswer(event.getEventType(), payload)) {
                        String answer = stripUserReplyPrefix(payload.getString("detail"));
                        if (nonBlank(answer) && !persistedAnswers.contains(normalizeAnswer(answer))) {
                            addUserDecisionEvidence(result, fingerprints, event.getEventId(), null, answer);
                        }
                    }
                } catch (Exception error) {
                    log.debug("[EvidenceMap] skip malformed timeline event id={} err={}", event.getEventId(), error.toString());
                }
            }
        }
        if (snapshot.getToolEvidences() != null) {
            for (ToolEvidenceRecord tool : snapshot.getToolEvidences()) {
                if (tool == null || !"success".equalsIgnoreCase(tool.getStatus()) || !nonBlank(tool.getOutput())) continue;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("toolName", tool.getToolName());
                metadata.put("input", tool.getInput());
                metadata.put("outputType", tool.getOutputType());
                metadata.put("step", tool.getStep());
                metadata.put("latencyMs", tool.getLatencyMs());
                addEvidence(result, fingerprints, tool.getEvidenceId(), "tool", "fact",
                        nonBlank(tool.getToolName()) ? tool.getToolName() : "工具调用", tool.getOutput(), metadata);
            }
        }
        return result;
    }

    private void addUserDecisionEvidence(List<Map<String, Object>> result, Set<String> fingerprints,
                                         String eventId, String question, String answer) {
        String content = nonBlank(question)
                ? "问题：" + question.trim() + "\n用户回答：" + answer.trim()
                : "用户回答：" + answer.trim();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (nonBlank(question)) metadata.put("question", question.trim());
        addEvidence(result, fingerprints, "user_decision:" + firstNonBlank(eventId, shortHash(content)),
                "user_decision", "user", "用户补充信息", content, metadata);
    }

    private String userInputQuestion(JSONObject payload) {
        JSONArray details = payload.getJSONArray("questionDetails");
        if (details != null && !details.isEmpty()) {
            List<String> questions = new ArrayList<>();
            for (int i = 0; i < details.size(); i++) {
                JSONObject detail = details.getJSONObject(i);
                if (detail != null && nonBlank(detail.getString("question"))) {
                    questions.add(detail.getString("question").trim());
                }
            }
            if (!questions.isEmpty()) return String.join("\n", questions);
        }
        Object rawQuestions = payload.get("questions");
        if (rawQuestions instanceof JSONArray array) {
            List<String> questions = new ArrayList<>();
            for (Object item : array) if (item != null && nonBlank(String.valueOf(item))) questions.add(String.valueOf(item).trim());
            return String.join("\n", questions);
        }
        return rawQuestions == null ? null : String.valueOf(rawQuestions);
    }

    private boolean isLegacyAskUserAnswer(String eventType, JSONObject payload) {
        return "tool_call_end".equals(eventType)
                && payload.getBooleanValue("meta")
                && "ask_user".equals(payload.getString("toolName"))
                && "success".equalsIgnoreCase(payload.getString("status"))
                && nonBlank(payload.getString("detail"))
                && payload.getString("detail").startsWith("用户回复：");
    }

    private String stripUserReplyPrefix(String detail) {
        return nonBlank(detail) && detail.startsWith("用户回复：") ? detail.substring("用户回复：".length()).trim() : detail;
    }

    private String normalizeAnswer(String answer) {
        return answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
    }

    private void addRagEvidence(List<Map<String, Object>> result, Set<String> fingerprints, JSONObject payload) {
        JSONArray items = payload.getJSONArray("items");
        if (items == null) return;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null || !nonBlank(item.getString("snippet"))) continue;
            String ref = item.getString("ref");
            Map<String, Object> metadata = new LinkedHashMap<>();
            copy(item, metadata, "ref", "relevanceScore", "relevanceType", "matchedChildSnippet");
            addEvidence(result, fingerprints, "rag:" + (nonBlank(ref) ? ref : shortHash(item.toJSONString())),
                    "rag", "fact", item.getString("source"), item.getString("snippet"), metadata);
        }
    }

    private void addMemoryEvidence(List<Map<String, Object>> result, Set<String> fingerprints, JSONObject payload) {
        String type = payload.getString("memoryType");
        JSONArray items = payload.getJSONArray("items");
        if (!nonBlank(type) || items == null) return;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null || !nonBlank(item.getString("content"))) continue;
            Map<String, Object> metadata = new LinkedHashMap<>();
            copy(item, metadata, "topic", "kind", "memoryKind", "memoryId", "similarity", "conversationId");
            String title = firstNonBlank(item.getString("topic"), item.getString("kind"), memoryTitle(type));
            addEvidence(result, fingerprints, type + ":" + shortHash(item.toJSONString()),
                    type, "context", title, item.getString("content"), metadata);
        }
    }

    private void addEvidence(List<Map<String, Object>> result, Set<String> fingerprints, String id,
                             String type, String category, String title, String content,
                             Map<String, Object> metadata) {
        String fingerprint = type + "|" + content.trim();
        if (!fingerprints.add(fingerprint)) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidenceId", nonBlank(id) ? id : type + ":" + shortHash(fingerprint));
        evidence.put("type", type);
        evidence.put("category", category);
        evidence.put("title", nonBlank(title) ? title : type);
        evidence.put("content", content);
        evidence.put("metadata", metadata == null ? Map.of() : metadata);
        result.add(evidence);
    }

    String buildCatalog(List<Map<String, Object>> evidences) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Map<String, Object> evidence : evidences) {
            if (count++ >= MAX_EVIDENCE_ITEMS_FOR_MODEL || builder.length() >= MAX_PROMPT_CHARS) break;
            String content = String.valueOf(evidence.get("content"));
            Map<String, Object> metadata = castMap(evidence.get("metadata"));
            Object matchedChild = metadata.get("matchedChildSnippet");
            List<String> modelParts;
            if ("tool".equals(evidence.get("type"))) {
                modelParts = toolModelParts(content);
            } else {
                String modelContent = "rag".equals(evidence.get("type")) && matchedChild != null
                        ? "MATCHED_CHILD:\n" + matchedChild + "\nPARENT_CONTEXT:\n" + content
                        : content;
                modelParts = List.of(clip(modelContent, MAX_CONTENT_CHARS_PER_ITEM));
            }
            for (int part = 0; part < modelParts.size() && builder.length() < MAX_PROMPT_CHARS; part++) {
                builder.append("\n---\nID: ").append(evidence.get("evidenceId"))
                        .append("\nTYPE: ").append(evidence.get("type"))
                        .append("\nTITLE: ").append(evidence.get("title"));
                if (modelParts.size() > 1) builder.append("\nPART: ").append(part + 1).append('/').append(modelParts.size());
                if (part == 0 && metadata.get("input") != null) {
                    builder.append("\nINPUT:\n").append(clip(normalizeJsonForModel(String.valueOf(metadata.get("input"))), 1800));
                }
                builder.append("\nCONTENT:\n").append(modelParts.get(part));
            }
        }
        return clip(builder.toString(), MAX_PROMPT_CHARS);
    }

    private List<String> toolModelParts(String content) {
        Object normalized = parseAndUnwrapJson(content);
        if (!(normalized instanceof JSONArray array) || array.isEmpty()) {
            return List.of(clip(prettyJson(normalized), MAX_CONTENT_CHARS_PER_ITEM));
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder("[");
        for (int i = 0; i < array.size(); i++) {
            String item = prettyJson(array.get(i));
            if (current.length() > 1 && current.length() + item.length() + 3 > MAX_CONTENT_CHARS_PER_ITEM) {
                current.append("\n]");
                parts.add(current.toString());
                current = new StringBuilder("[");
            }
            if (current.length() > 1) current.append(",\n");
            current.append(clip(item, MAX_CONTENT_CHARS_PER_ITEM - 10));
        }
        current.append("\n]");
        parts.add(current.toString());
        return parts;
    }

    private String normalizeJsonForModel(String value) {
        return prettyJson(parseAndUnwrapJson(value));
    }

    private Object parseAndUnwrapJson(Object value) {
        Object normalized = value;
        if (value instanceof String text) {
            String trimmed = text.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    normalized = JSON.parse(trimmed);
                } catch (Exception ignored) {
                    return text;
                }
            } else {
                return text;
            }
        }
        if (normalized instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            for (Object item : array) copy.add(parseAndUnwrapJson(item));
            if (copy.size() == 1) {
                Object only = copy.get(0);
                if (only instanceof JSONObject object && object.containsKey("text")) {
                    return parseAndUnwrapJson(object.get("text"));
                }
            }
            return copy;
        }
        if (normalized instanceof JSONObject object) {
            if (object.containsKey("text") && (object.size() == 1 || object.size() == 2 && object.containsKey("type"))) {
                return parseAndUnwrapJson(object.get("text"));
            }
            JSONObject copy = new JSONObject(true);
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                copy.put(entry.getKey(), parseAndUnwrapJson(entry.getValue()));
            }
            return copy;
        }
        return normalized;
    }

    private String prettyJson(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray || value instanceof Map || value instanceof List) {
            return JSON.toJSONString(value, SerializerFeature.PrettyFormat);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private String buildClaimCandidateCatalog(List<Map<String, String>> candidates) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, String> candidate : candidates) {
            builder.append("\n---\n")
                    .append(candidate.get("claimId"))
                    .append(" | ").append(candidate.get("title"))
                    .append("\n").append(candidate.get("text"))
                    .append('\n');
        }
        return clip(builder.toString(), 30_000);
    }

    List<Map<String, String>> buildClaimCandidates(String finalAnswer) {
        String answer = String.valueOf(finalAnswer).replace("\r", "");
        String[] lines = answer.split("\n", -1);
        int boundaryLevel = findSectionBoundaryLevel(lines);
        List<String> blocks = boundaryLevel > 0
                ? splitByMarkdownSections(lines, boundaryLevel)
                : splitByParagraphGroups(lines);
        if (blocks.isEmpty() && nonBlank(finalAnswer)) blocks = List.of(finalAnswer.trim());

        List<Map<String, String>> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String rawBlock : blocks) {
            String block = rawBlock == null ? "" : rawBlock.trim();
            if (!isMeaningfulBlock(block) || !seen.add(block)) continue;
            Map<String, String> candidate = new LinkedHashMap<>();
            candidate.put("claimId", "B" + (candidates.size() + 1));
            candidate.put("title", blockTitle(block));
            candidate.put("text", block.length() <= MAX_BLOCK_CHARS ? block : block.substring(0, MAX_BLOCK_CHARS));
            candidates.add(candidate);
            if (candidates.size() >= MAX_CLAIM_CANDIDATES) break;
        }
        return candidates;
    }

    private int findSectionBoundaryLevel(String[] lines) {
        for (int level = 2; level <= 4; level++) {
            String prefix = "#".repeat(level) + " ";
            for (String line : lines) if (line.trim().startsWith(prefix)) return level;
        }
        return 0;
    }

    private List<String> splitByMarkdownSections(String[] lines, int boundaryLevel) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCodeFence = false;
        String boundary = "#".repeat(boundaryLevel) + " ";
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
            }
            if (!inCodeFence && line.startsWith(boundary)) {
                flushBlock(blocks, current);
            }
            if (current.length() > 0) current.append('\n');
            // H1 is only the document title. Keep its following introduction in the first block.
            if (!(line.startsWith("# ") && current.toString().isBlank())) {
                current.append(rawLine);
            }
        }
        flushBlock(blocks, current);
        return blocks;
    }

    private List<String> splitByParagraphGroups(String[] lines) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        boolean inCodeFence = false;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("```")) inCodeFence = !inCodeFence;
            if (!inCodeFence && line.isEmpty()) {
                flushBlock(paragraphs, paragraph);
                continue;
            }
            if (line.startsWith("# ") && paragraph.length() == 0) continue;
            if (paragraph.length() > 0) paragraph.append('\n');
            paragraph.append(rawLine);
        }
        flushBlock(paragraphs, paragraph);

        List<String> groups = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        for (String item : paragraphs) {
            boolean table = item.lines().anyMatch(line -> line.trim().startsWith("|"));
            if (group.length() > 0 && (group.length() >= 700 || table)) flushBlock(groups, group);
            if (group.length() > 0) group.append("\n\n");
            group.append(item);
            if (table || group.length() >= 1400) flushBlock(groups, group);
        }
        flushBlock(groups, group);
        return groups;
    }

    private void flushBlock(List<String> blocks, StringBuilder builder) {
        String value = builder.toString().trim();
        if (isMeaningfulBlock(value)) blocks.add(value);
        builder.setLength(0);
    }

    private boolean isMeaningfulBlock(String block) {
        if (!nonBlank(block)) return false;
        String withoutMarkup = block.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[`*_>|\\-]", "").trim();
        return withoutMarkup.length() >= 6;
    }

    private String blockTitle(String block) {
        for (String line : block.split("\n")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            value = value.replaceFirst("^#{1,6}\\s*", "")
                    .replaceFirst("^(?:[-*+]\\s+|\\d+[.)、]\\s*)", "")
                    .replace("**", "").trim();
            if (nonBlank(value)) return value.length() <= 80 ? value : value.substring(0, 80) + "…";
        }
        return "回答内容";
    }

    List<Map<String, Object>> validateClaims(String modelOutput, String finalAnswer,
                                             List<Map<String, Object>> evidences) {
        return validateClaimsDetailed(modelOutput, finalAnswer, buildClaimCandidates(finalAnswer), evidences).claims();
    }

    private ValidationResult validateClaimsDetailed(String modelOutput, String finalAnswer,
                                                     List<Map<String, String>> claimCandidates,
                                                     List<Map<String, Object>> evidences) {
        JSONObject root = JSON.parseObject(stripModelWrapper(modelOutput));
        JSONArray rawClaims = root == null ? null : root.getJSONArray("claims");
        if (rawClaims == null) rawClaims = new JSONArray();
        Map<String, Map<String, Object>> evidenceById = new LinkedHashMap<>();
        evidences.forEach(evidence -> evidenceById.put(String.valueOf(evidence.get("evidenceId")), evidence));
        Map<String, String> candidateById = new LinkedHashMap<>();
        claimCandidates.forEach(candidate -> candidateById.put(candidate.get("claimId"), candidate.get("text")));

        int modelClaims = rawClaims == null ? 0 : rawClaims.size();
        int rejectedClaims = 0;
        int duplicateClaims = 0;
        int unknownEvidenceLinks = 0;
        int quoteMismatchLinks = 0;
        int acceptedLinks = 0;
        int rejectedChecks = 0;

        List<Map<String, Object>> claims = new ArrayList<>();
        Set<String> seenClaims = new LinkedHashSet<>();
        for (int i = 0; i < rawClaims.size(); i++) {
            JSONObject rawClaim = rawClaims.getJSONObject(i);
            if (rawClaim == null) {
                rejectedClaims++;
                continue;
            }
            String requestedClaimId = rawClaim.getString("claimId");
            String text = candidateById.get(requestedClaimId);
            // Backward compatibility for cached/tests produced by the V1 text-copy protocol.
            if (!nonBlank(text)) text = rawClaim.getString("text");
            if (!nonBlank(text) || !finalAnswer.contains(text)) {
                rejectedClaims++;
                continue;
            }
            if (!seenClaims.add(text.trim())) {
                duplicateClaims++;
                continue;
            }
            JSONArray rawChecks = rawClaim.getJSONArray("checks");
            if (rawChecks == null) {
                // Backward compatibility for V3 tests/old model output.
                rawChecks = new JSONArray();
                JSONObject legacyCheck = new JSONObject();
                legacyCheck.put("text", text);
                legacyCheck.put("links", rawClaim.getJSONArray("links"));
                rawChecks.add(legacyCheck);
            }
            List<Map<String, Object>> checks = new ArrayList<>();
            List<Map<String, Object>> links = new ArrayList<>();
            Set<String> seenCheckTexts = new LinkedHashSet<>();
            Set<String> seenClaimLinks = new LinkedHashSet<>();
            for (int checkIndex = 0; checkIndex < rawChecks.size() && checks.size() < 6; checkIndex++) {
                JSONObject rawCheck = rawChecks.getJSONObject(checkIndex);
                String checkText = rawCheck == null ? null : rawCheck.getString("text");
                if (!nonBlank(checkText) || !text.contains(checkText) || !seenCheckTexts.add(checkText.trim())) {
                    rejectedChecks++;
                    continue;
                }
                JSONArray rawLinks = rawCheck.getJSONArray("links");
                List<Map<String, Object>> checkLinks = new ArrayList<>();
                if (rawLinks != null) {
                    for (int j = 0; j < rawLinks.size() && checkLinks.size() < 4; j++) {
                        JSONObject rawLink = rawLinks.getJSONObject(j);
                        String evidenceId = rawLink == null ? null : rawLink.getString("evidenceId");
                        Map<String, Object> evidence = evidenceById.get(evidenceId);
                        if (evidence == null) {
                            unknownEvidenceLinks++;
                            continue;
                        }
                        String type = String.valueOf(evidence.get("type"));
                        String relation = normalizeRelation(type, rawLink.getString("relation"));
                        String evidenceContent = evidenceQuoteCorpus(evidence);
                        List<String> quotes = exactQuotes(rawLink, evidenceContent);
                        if (quotes.isEmpty()) {
                            quoteMismatchLinks++;
                            continue;
                        }
                        Map<String, Object> link = new LinkedHashMap<>();
                        link.put("evidenceId", evidenceId);
                        link.put("relation", relation);
                        link.put("quote", quotes.get(0));
                        link.put("quotes", quotes);
                        link.put("checkText", checkText.trim());
                        String note = rawLink.getString("note");
                        if (nonBlank(note)) link.put("note", clip(note.trim(), 240));
                        checkLinks.add(link);
                        String linkKey = evidenceId + "|" + relation + "|" + String.join("|", quotes);
                        if (seenClaimLinks.add(linkKey) && links.size() < 12) {
                            // checks[].links and the legacy flattened claim.links must not share the
                            // same object graph. Fastjson serializes repeated object identities as
                            // {"$ref":"..."}; the browser then cannot read evidenceId from the
                            // flattened link and incorrectly reports that the block has no source.
                            Map<String, Object> flattenedLink = new LinkedHashMap<>(link);
                            flattenedLink.put("quotes", new ArrayList<>(quotes));
                            links.add(flattenedLink);
                        }
                        acceptedLinks++;
                    }
                }
                Map<String, Object> check = new LinkedHashMap<>();
                check.put("text", checkText.trim());
                check.put("links", checkLinks);
                check.put("status", checkStatus(checkLinks));
                checks.add(check);
            }
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("claimId", firstNonBlank(requestedClaimId, "C" + (claims.size() + 1)));
            Map<String, String> candidate = claimCandidates.stream()
                    .filter(item -> requestedClaimId != null && requestedClaimId.equals(item.get("claimId")))
                    .findFirst().orElse(Map.of());
            claim.put("title", firstNonBlank(candidate.get("title"), blockTitle(text)));
            claim.put("text", text);
            claim.put("checks", checks);
            claim.put("links", links);
            int requiredChecks = minimumChecksForBlock(text);
            long coveredChecks = checks.stream().filter(check -> isCoveredCheckStatus(String.valueOf(check.get("status")))).count();
            claim.put("requiredChecks", requiredChecks);
            claim.put("coveredChecks", coveredChecks);
            claim.put("status", blockStatus(checks, requiredChecks));
            claims.add(claim);
            if (claims.size() >= MAX_CLAIM_CANDIDATES) break;
        }
        for (Map<String, String> candidate : claimCandidates) {
            if (claims.size() >= MAX_CLAIM_CANDIDATES) break;
            String text = candidate.get("text");
            if (!nonBlank(text) || seenClaims.contains(text.trim())) continue;
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("claimId", candidate.get("claimId"));
            claim.put("title", candidate.get("title"));
            claim.put("text", text);
            claim.put("checks", List.of());
            claim.put("links", List.of());
            claim.put("requiredChecks", minimumChecksForBlock(text));
            claim.put("coveredChecks", 0);
            claim.put("status", "unsupported");
            claims.add(claim);
        }
        String stage = modelClaims == 0 ? "MODEL_RETURNED_NO_CLAIMS"
                : (claims.isEmpty() ? "CLAIMS_REJECTED" : "COMPLETED");
        Map<String, Object> validationDiagnostics = diagnostics(claimCandidates, evidences, modelClaims,
                rejectedClaims, duplicateClaims, unknownEvidenceLinks, quoteMismatchLinks, acceptedLinks, stage);
        validationDiagnostics.put("rejectedChecks", rejectedChecks);
        validationDiagnostics.put("acceptedClaims", claims.size());
        return new ValidationResult(claims, validationDiagnostics);
    }

    private List<String> exactQuotes(JSONObject rawLink, String evidenceContent) {
        if (rawLink == null || evidenceContent == null) return List.of();
        List<String> candidates = new ArrayList<>();
        JSONArray rawQuotes = rawLink.getJSONArray("quotes");
        if (rawQuotes != null) {
            for (int i = 0; i < rawQuotes.size() && candidates.size() < 6; i++) {
                String value = rawQuotes.getString(i);
                if (nonBlank(value)) candidates.add(value);
            }
        }
        String legacyQuote = rawLink.getString("quote");
        if (candidates.isEmpty() && nonBlank(legacyQuote)) candidates.add(legacyQuote);
        List<String> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String quote : candidates) {
            String value = quote.trim();
            if (evidenceContent.contains(value) && seen.add(value)) accepted.add(value);
        }
        return accepted;
    }

    private String checkStatus(List<Map<String, Object>> links) {
        boolean supports = links.stream().anyMatch(link -> "supports".equals(link.get("relation")));
        boolean partial = links.stream().anyMatch(link -> "partial_support".equals(link.get("relation")));
        boolean user = links.stream().anyMatch(link -> "specified_by_user".equals(link.get("relation")));
        boolean context = links.stream().anyMatch(link -> "context".equals(link.get("relation")));
        if (supports) return "supported";
        if (partial) return "partial";
        if (user) return "user_specified";
        if (context) return "context_only";
        return "unsupported";
    }

    private boolean isCoveredCheckStatus(String status) {
        return "supported".equals(status) || "partial".equals(status) || "user_specified".equals(status);
    }

    private String blockStatus(List<Map<String, Object>> checks, int requiredChecks) {
        if (checks.isEmpty()) return "unsupported";
        List<String> statuses = checks.stream().map(check -> String.valueOf(check.get("status"))).toList();
        boolean enough = checks.size() >= requiredChecks;
        boolean allUser = statuses.stream().allMatch("user_specified"::equals);
        boolean allContext = statuses.stream().allMatch("context_only"::equals);
        boolean allVerified = statuses.stream().allMatch(status -> "supported".equals(status) || "user_specified".equals(status));
        boolean anyTraceable = statuses.stream().anyMatch(this::isCoveredCheckStatus);
        boolean anyContext = statuses.stream().anyMatch("context_only"::equals);
        if (enough && allUser) return "user_specified";
        if (enough && allContext) return "context_only";
        if (enough && allVerified) return "supported";
        if (anyTraceable) return "partial";
        if (anyContext) return "context_only";
        return "unsupported";
    }

    private int minimumChecksForBlock(String text) {
        if (!nonBlank(text)) return 1;
        int tableRows = 0;
        int concreteLines = 0;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.matches("^\\|?[\\s:|-]+\\|?$")) continue;
            if (line.startsWith("|") && line.endsWith("|")) tableRows++;
            if (line.matches("^(?:[-*+]\\s+|\\d+[.)、]\\s*).+")) concreteLines++;
        }
        int materialUnits = Math.max(tableRows, concreteLines);
        if (text.length() >= 1200 || materialUnits >= 6) return 3;
        if (text.length() >= 320 || materialUnits >= 2) return 2;
        return 1;
    }

    private String normalizeRelation(String type, String requested) {
        if ("user_request".equals(type) || "user_decision".equals(type)) return "specified_by_user";
        if ("long_term".equals(type) || "episodic".equals(type) || "chat_summary".equals(type)) return "context";
        if ("rag".equals(type) || "tool".equals(type)) {
            return "supports".equals(requested) || !nonBlank(requested) ? "supports" : "partial_support";
        }
        return "context";
    }

    private String evidenceQuoteCorpus(Map<String, Object> evidence) {
        StringBuilder corpus = new StringBuilder(String.valueOf(evidence.get("content")));
        Map<String, Object> metadata = castMap(evidence.get("metadata"));
        if (metadata.get("input") != null) corpus.append('\n').append(normalizeJsonForModel(String.valueOf(metadata.get("input"))));
        if ("tool".equals(evidence.get("type"))) corpus.append('\n').append(normalizeJsonForModel(String.valueOf(evidence.get("content"))));
        return corpus.toString();
    }

    private Map<String, Object> emptyDiagnostics(List<Map<String, String>> claimCandidates,
                                                 List<Map<String, Object>> evidences,
                                                 String stage) {
        return diagnostics(claimCandidates, evidences, 0, 0, 0, 0, 0, 0, stage);
    }

    private Map<String, Object> diagnostics(List<Map<String, String>> claimCandidates,
                                            List<Map<String, Object>> evidences,
                                            int modelClaims, int rejectedClaims, int duplicateClaims,
                                            int unknownEvidenceLinks, int quoteMismatchLinks,
                                            int acceptedLinks, String stage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage);
        result.put("candidateClaims", claimCandidates.size());
        result.put("modelClaims", modelClaims);
        result.put("acceptedClaims", Math.max(0, modelClaims - rejectedClaims - duplicateClaims));
        result.put("rejectedClaims", rejectedClaims);
        result.put("duplicateClaims", duplicateClaims);
        result.put("acceptedLinks", acceptedLinks);
        result.put("unknownEvidenceLinks", unknownEvidenceLinks);
        result.put("quoteMismatchLinks", quoteMismatchLinks);
        result.put("sourceCount", evidences.size());
        result.put("sourceCounts", evidenceTypeCounts(evidences));
        return result;
    }

    private record ValidationResult(List<Map<String, Object>> claims, Map<String, Object> diagnostics) {
    }

    private Map<String, Object> buildStats(List<Map<String, Object>> claims) {
        int supported = 0;
        int partial = 0;
        int userSpecified = 0;
        int contextOnly = 0;
        int unsupported = 0;
        int links = 0;
        int checks = 0;
        int coveredChecks = 0;
        for (Map<String, Object> claim : claims) {
            String status = String.valueOf(claim.get("status"));
            if ("supported".equals(status)) supported++;
            else if ("partial".equals(status)) partial++;
            else if ("user_specified".equals(status)) userSpecified++;
            else if ("context_only".equals(status)) contextOnly++;
            else unsupported++;
            links += sizeOf(claim.get("links"));
            checks += sizeOf(claim.get("checks"));
            Object covered = claim.get("coveredChecks");
            if (covered instanceof Number number) coveredChecks += number.intValue();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalClaims", claims.size());
        stats.put("supportedClaims", supported);
        stats.put("partialClaims", partial);
        stats.put("userSpecifiedClaims", userSpecified);
        stats.put("contextOnlyClaims", contextOnly);
        stats.put("unsupportedClaims", unsupported);
        stats.put("links", links);
        stats.put("checks", checks);
        stats.put("coveredChecks", coveredChecks);
        stats.put("coverage", claims.isEmpty() ? 0.0d : (double) supported / claims.size());
        stats.put("traceability", claims.isEmpty() ? 0.0d : (double) (supported + partial + userSpecified) / claims.size());
        return stats;
    }

    private String evidenceSignature(String finalAnswer, List<Map<String, Object>> evidences) {
        StringBuilder value = new StringBuilder(EVIDENCE_MAP_VERSION).append('|').append(shortHash(finalAnswer));
        for (Map<String, Object> evidence : evidences) {
            value.append('|').append(evidence.get("evidenceId"))
                    .append(':').append(shortHash(String.valueOf(evidence.get("content"))));
        }
        return shortHash(value.toString());
    }

    private Map<String, Integer> evidenceTypeCounts(List<Map<String, Object>> evidences) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> evidence : evidences) {
            String type = String.valueOf(evidence.get("type"));
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    private static int sizeOf(Object value) {
        return value instanceof java.util.Collection<?> collection ? collection.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String findFinalAnswer(RunSnapshot snapshot) {
        if (snapshot.getTimelineEvents() == null) return null;
        String answer = null;
        for (RunEventRecord event : snapshot.getTimelineEvents()) {
            if (event == null || !nonBlank(event.getPayloadJson())) continue;
            try {
                JSONObject payload = JSON.parseObject(event.getPayloadJson());
                if (("message".equals(event.getEventType()) || event.getEventType() == null)
                        && "summary".equals(payload.getString("type"))) {
                    answer = payload.getString("content");
                }
            } catch (Exception ignored) {
            }
        }
        return answer;
    }

    private static void copy(JSONObject source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static String stripModelWrapper(String value) {
        String text = value == null ? "" : value.trim();
        int thinkEnd = text.lastIndexOf("</think>");
        if (thinkEnd >= 0) text = text.substring(thinkEnd + 8).trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) text = text.substring(newline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private static String memoryTitle(String type) {
        return switch (type) {
            case "long_term" -> "长期记忆";
            case "episodic" -> "情景记忆";
            case "chat_summary" -> "会话摘要";
            default -> "记忆";
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (nonBlank(value)) return value;
        return "";
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "\n...(truncated)";
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }
}
