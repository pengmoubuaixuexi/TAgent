package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.ToolEvidenceRecord;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded projections for the model; references only address evidence inside its own run. */
@Component
public class ToolResultReferenceService {
    public static final String TOOL_NAME = "inspect_tool_result";
    private static final Pattern REFERENCE = Pattern.compile("tool:[a-zA-Z0-9_-]{1,100}");
    private static final Set<String> ARGUMENTS = Set.of("resultRef", "jsonPointer", "offset", "limit");
    private static final int MAX_PAGE_CHARS = 4000;
    private final RunSnapshotService snapshots;
    private final ObjectMapper json;
    @Value("${agent.tool-results.inline-chars:6000}") private int inlineChars = 6000;
    @Value("${agent.tool-results.max-stored-chars:262144}") private int maxStoredChars = 262144;

    @Autowired
    public ToolResultReferenceService(ObjectProvider<RunSnapshotService> snapshots) { this(snapshots.getIfAvailable()); }

    public ToolResultReferenceService(RunSnapshotService snapshots) {
        this.snapshots = snapshots;
        json = new ObjectMapper();
        json.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(100).maxStringLength(2_000_000).maxNumberLength(1000).build());
    }

    public record StoredResult(String resultRef, String reason, int originalChars) {
        public boolean stored() { return resultRef != null; }
    }

    /** Must run on the original provider response before any tool-specific compactor. */
    public StoredResult store(Map<String, Object> scope, String callId, String toolName, String input,
                              String rawResult, String step, long latencyMs) {
        int rawChars = rawResult == null ? 0 : rawResult.length();
        if (snapshots == null) return new StoredResult(null, "RESULT_STORAGE_UNAVAILABLE", rawChars);
        if (rawResult == null) return new StoredResult(null, "EMPTY_RESULT", rawChars);
        if (rawChars > storageLimit()) return new StoredResult(null, "RESULT_STORAGE_LIMIT", rawChars);
        String runId = runId(scope), userId = userId(scope);
        if (runId == null || userId == null || callId == null) return new StoredResult(null, "MISSING_RUN_SCOPE", rawChars);
        String reference = "tool:" + callId;
        if (!REFERENCE.matcher(reference).matches()) return new StoredResult(null, "INVALID_REFERENCE", rawChars);
        ToolEvidenceRecord evidence = ToolEvidenceRecord.builder().evidenceId(reference).callId(callId)
                .toolName(toolName).input(prefix(input, 4000)).output(rawResult)
                .outputType(ToolCallProgressEmitter.detectOutputType(rawResult)).step(step).status("success")
                .rawResultChars(rawChars).resultChars(rawChars).latencyMs(Math.max(0, latencyMs))
                .createdAt(System.currentTimeMillis()).build();
        try {
            if (snapshots.saveToolEvidence(runId, userId, evidence)
                    && snapshots.findToolEvidence(runId, userId, reference)
                    .filter(saved -> rawResult.equals(saved.getOutput())).isPresent()) {
                return new StoredResult(reference, null, rawChars);
            }
        } catch (RuntimeException failure) {
            StreamingActivityTracker.rethrowCancellation(failure);
        }
        return new StoredResult(null, "RESULT_NOT_STORED", rawChars);
    }

    public String project(String toolName, String raw, String compacted, StoredResult stored) {
        if (raw == null) return compacted;
        int limit = inlineLimit();
        String forecast = WeatherForecastResultAnnotator.annotate(toolName, raw, json);
        String preview = !raw.equals(forecast) ? forecast : compacted == null ? "" : compacted;
        // POI responses are often below the generic 6k ceiling but contain 20 candidates and photos.
        // Select useful fields before repeated searches accumulate in one model request.
        String poiSummary = AmapPoiResultCompactor.compact(toolName, raw);
        boolean poiCompacted = !raw.equals(poiSummary);
        if (poiCompacted) preview = poiSummary;
        if (raw.length() <= limit && preview.length() <= limit && raw.equals(preview)) return preview;
        ObjectNode output = json.createObjectNode();
        output.put("tool", toolName);
        output.put("originalChars", raw.length());
        output.put("inlineTruncated", preview.length() > limit);
        output.put("summary", prefix(preview, limit));
        if (stored != null && stored.stored()) {
            output.put("resultRef", stored.resultRef());
            output.put("readTool", TOOL_NAME);
            output.put("message", "这是有界摘要。需要细节时使用 inspect_tool_result 按 JSON Pointer 或 offset/limit 分页读取原始结果，勿重复调用原工具。");
        } else {
            output.put("resultAvailable", false);
            output.put("reason", stored == null ? "RESULT_NOT_STORED" : stored.reason());
            output.put("storageLimitChars", storageLimit());
            output.put("message", "原始结果未保存为可读取引用；当前仅提供有界摘要，不代表完整结果。请基于已有信息作答，勿盲目重复提交有副作用的操作。");
        }
        if (poiCompacted) {
            try {
                // Preserve valid structured fields instead of clipping/re-escaping a JSON string.
                ObjectNode summary = (ObjectNode) json.readTree(preview);
                output.set("summary", summary);
                var pois = (com.fasterxml.jackson.databind.node.ArrayNode) summary.path("pois");
                while (output.toString().length() > limit && pois.size() > 1) pois.remove(pois.size() - 1);
                summary.put("returnedPois", pois.size());
                summary.put("omittedPois", summary.path("totalPois").asInt() - pois.size());
                if (output.toString().length() > limit) {
                    summary.remove("pois");
                    summary.put("returnedPois", 0);
                    summary.put("omittedPois", summary.path("totalPois").asInt());
                    summary.put("detailOmitted", true);
                    output.put("inlineTruncated", true);
                }
                if (output.toString().length() <= limit) {
                    String structured = output.toString();
                    // For an already-small response, metadata must not make its projection larger.
                    if (raw.equals(compacted) && raw.length() <= limit && structured.length() >= raw.length()) return raw;
                    return structured;
                }
                output.put("summary", preview);
            } catch (Exception invalidSummary) {
                output.put("summary", preview);
            }
        }
        String encoded = output.toString();
        // The model budget applies to serialized text, including JSON escaping and the reference.
        int previewChars = Math.min(preview.length(), limit);
        while (encoded.length() > limit && previewChars > 0) {
            previewChars = Math.max(0, previewChars - Math.max(1, (encoded.length() - limit + 5) / 6));
            output.put("summary", prefix(preview, previewChars));
            output.put("inlineTruncated", true);
            encoded = output.toString();
        }
        return encoded;
    }

    /** Only the current owner's live run can establish map-coordinate provenance. */
    public String validateMapArguments(String toolName, String arguments, Map<String, Object> scope) {
        if (!AmapMapEvidenceGuard.supports(toolName)) return null;
        String runId = runId(scope), userId = userId(scope);
        if (snapshots == null || runId == null || userId == null)
            return error("POI_COORDINATES_NOT_VERIFIED", "缺少可校验的本次运行证据，请改为文字行程，不能猜坐标生成地图");
        try {
            var snapshot = snapshots.find(runId).filter(s -> runId.equals(s.getRunId()) && userId.equals(s.getUserId()))
                    .filter(s -> s.getExpiresAt() == null || s.getExpiresAt() > System.currentTimeMillis()).orElse(null);
            if (snapshot == null) return error("POI_COORDINATES_NOT_VERIFIED", "本次运行的 POI 证据已过期或不可用");
            String rejection = AmapMapEvidenceGuard.rejection(arguments, snapshot.getToolEvidences(), json);
            return rejection == null ? null : error("POI_COORDINATES_NOT_VERIFIED", rejection);
        } catch (RuntimeException unavailable) {
            StreamingActivityTracker.rethrowCancellation(unavailable);
            return error("POI_COORDINATES_NOT_VERIFIED", "暂时无法核验地点坐标，请改为文字行程，勿猜测后重复提交");
        }
    }

    public String inspect(String arguments, Map<String, Object> scope) {
        if (snapshots == null) return error("RESULT_NOT_AVAILABLE", "结果存储未启用");
        if (arguments == null || arguments.length() > 4096) return error("INVALID_ARGUMENTS", "参数必须是小于 4096 字符的 JSON 对象");
        try {
            JsonNode input = json.readTree(arguments);
            if (input == null || !input.isObject()) return error("INVALID_ARGUMENTS", "参数必须是 JSON 对象");
            var fields = input.fieldNames();
            while (fields.hasNext()) if (!ARGUMENTS.contains(fields.next())) return error("INVALID_ARGUMENTS", "只支持 resultRef、jsonPointer、offset、limit");
            if (!input.path("resultRef").isTextual() || !REFERENCE.matcher(input.path("resultRef").asText()).matches())
                return error("INVALID_ARGUMENTS", "resultRef 格式不正确");
            String runId = runId(scope), userId = userId(scope);
            if (runId == null || userId == null) return error("MISSING_RUN_SCOPE", "当前调用缺少授权的用户或运行上下文");
            String pointer = input.has("jsonPointer") ? input.path("jsonPointer").asText(null) : "";
            if ((input.has("jsonPointer") && !input.path("jsonPointer").isTextual())
                    || pointer == null || pointer.length() > 512 || (!pointer.isEmpty() && !pointer.startsWith("/")))
                return error("INVALID_POINTER", "jsonPointer 必须为空或以 / 开头，最多 512 字符");
            if (!validInteger(input, "offset", 0, Integer.MAX_VALUE) || !validInteger(input, "limit", 1, MAX_PAGE_CHARS))
                return error("INVALID_RANGE", "offset 必须为非负整数；limit 必须为 1–4000 的整数");
            int offset = input.path("offset").asInt(0), limit = input.path("limit").asInt(2000);
            String reference = input.path("resultRef").asText();
            ToolEvidenceRecord evidence = snapshots.findToolEvidence(runId, userId, reference).orElse(null);
            if (evidence == null || evidence.getOutput() == null)
                return error("RESULT_NOT_AVAILABLE", "引用已过期、不存在，或不属于当前用户的本次运行");
            String selected = evidence.getOutput();
            if (selected.length() > storageLimit()) return error("RESULT_STORAGE_LIMIT", "该历史结果超过当前允许读取的大小");
            if (!pointer.isEmpty()) {
                JsonNode value;
                try {
                    JsonNode document = json.readTree(selected);
                    value = document == null ? null : document.at(pointer);
                } catch (Exception malformed) { return error("INVALID_POINTER", "原始结果不是可按该 JSON Pointer 读取的 JSON"); }
                if (value == null || value.isMissingNode()) return error("POINTER_NOT_FOUND", "JSON Pointer 对应的字段不存在");
                selected = value.isTextual() ? value.textValue() : value.toString();
            }
            if (offset > selected.length()) return error("INVALID_RANGE", "offset 超过所选内容长度");
            if (offset < selected.length() && Character.isLowSurrogate(selected.charAt(offset))) return error("INVALID_RANGE", "offset 不能位于 Unicode 字符中间");
            int end = Math.min(selected.length(), offset + Math.min(limit, selected.length() - offset));
            if (end > offset && end < selected.length() && Character.isHighSurrogate(selected.charAt(end - 1))) end--;
            if (end == offset && offset < selected.length()) return error("INVALID_RANGE", "limit 太小，无法读取一个完整 Unicode 字符");
            ObjectNode output = json.createObjectNode();
            output.put("resultRef", reference); output.put("jsonPointer", pointer);
            output.put("offset", offset); output.put("totalChars", selected.length());
            while (true) {
                output.put("data", selected.substring(offset, end)); output.put("hasMore", end < selected.length());
                if (end < selected.length()) output.put("nextOffset", end); else output.remove("nextOffset");
                String encoded = output.toString();
                if (encoded.length() <= inlineLimit()) return encoded;
                int reduction = Math.max(1, (encoded.length() - inlineLimit() + 5) / 6);
                end = Math.max(offset, end - reduction);
                if (end > offset && Character.isHighSurrogate(selected.charAt(end - 1))) end--;
                if (end == offset) return error("INVALID_RANGE", "所选字段路径过长，无法在单页预算中返回数据");
            }
        } catch (RuntimeException failure) {
            StreamingActivityTracker.rethrowCancellation(failure);
            return error("RESULT_NOT_AVAILABLE", "结果暂时无法读取，请检查引用是否仍有效");
        } catch (Exception malformed) {
            return error("INVALID_ARGUMENTS", "参数不是有效 JSON");
        }
    }

    private boolean validInteger(JsonNode input, String field, int min, int max) {
        JsonNode value = input.get(field);
        return value == null || value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= min && value.intValue() <= max;
    }
    private String error(String code, String message) {
        ObjectNode value = json.createObjectNode(); value.put("ok", false); value.put("error", code); value.put("message", message); return value.toString();
    }
    private int storageLimit() { return Math.max(1024, Math.min(2_000_000, maxStoredChars)); }
    private int inlineLimit() { return Math.max(1024, Math.min(20_000, inlineChars)); }
    private static String prefix(String text, int limit) {
        if (text == null || text.length() <= limit) return text;
        int end = limit; if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--; return text.substring(0, end);
    }
    private static String runId(Map<String, Object> context) {
        String value = identity(context, "agent.run_id");
        return value == null ? MDC.get("runId") : value;
    }
    private static String userId(Map<String, Object> context) { return identity(context, "userId"); }
    private static String identity(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        String text = value == null ? MDC.get(key) : String.valueOf(value);
        return text == null || text.isBlank() ? null : text;
    }
}
