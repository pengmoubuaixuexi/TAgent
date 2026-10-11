package cn.bugstack.ai.trigger.eval;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON value snapshots, not serializer-specific Java object identity references. */
final class EvalTraceCodec {
    private static final int MAX_TRACE_CHARS = 64 * 1024 * 1024;
    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256)
                            .maxDocumentLength(MAX_TRACE_CHARS).build())
                    .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(256).build())
                    .build())
            .addModule(new JavaTimeModule())
            .addModule(new SimpleModule().addSerializer(new TraceMapSerializer()))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final String LEGACY_SCHEMA = "eval-trace-v2-old-compatible";
    // fastjson 2.0.28's compatibility writer omitted the property name AND comma for
    // these repeated bean list fields. Its reference path was also incorrectly scoped.
    private static final Pattern LEGACY_ORPHAN = Pattern.compile(
            "\\{\"\\$ref\":\"\\$\\.case\\.config\\.expectedCapabilities\\.(steps|toolEvidences)\"}");
    private static final String POSITIVE_INTEGER = "[1-9][0-9]{0,9}";
    private static final String DEPENDENCY_ENTRY = POSITIVE_INTEGER + ":\\[(?:" + POSITIVE_INTEGER
            + "(?:," + POSITIVE_INTEGER + "){0,255})?\\]";
    private static final Pattern LEGACY_DEPENDENCIES = Pattern.compile(
            "\\{" + DEPENDENCY_ENTRY + "(?:," + DEPENDENCY_ENTRY + "){0,255}}");
    private static final Pattern LEGACY_INTEGER_KEY = Pattern.compile("(?<=[{,])(" + POSITIVE_INTEGER + "):");

    private EvalTraceCodec() {
    }

    static String write(Map<String, Object> trace) {
        try {
            // Jackson expands shared collections at each location, preserving both the
            // legacy fields and runSnapshot evidence as ordinary standalone JSON values.
            BoundedTraceWriter output = new BoundedTraceWriter();
            MAPPER.writeValue(output, trace);
            return output.toString();
        } catch (IOException error) {
            throw new IllegalArgumentException("评测 Trace 序列化失败", error);
        }
    }

    /**
     * Fastjson's Redis round trip can return JSONObject (declared String keys) whose
     * backing map actually holds Integer keys. Iterate as Map<?, ?> so no inferred
     * String cast occurs, including inside beans, event payloads and nested lists.
     */
    private static final class TraceMapSerializer extends JsonSerializer<Map<?, ?>> {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Class<Map<?, ?>> handledType() { return (Class) Map.class; }

        @Override
        public void serialize(Map<?, ?> value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            for (JsonStreamContext context = generator.getOutputContext(); context != null; context = context.getParent()) {
                if (context.getCurrentValue() == value) throw JsonMappingException.from(generator, "评测 Trace 包含循环引用");
            }
            generator.writeStartObject(value);
            HashSet<String> keys = new HashSet<>();
            for (Map.Entry<?, ?> entry : value.entrySet()) {
                String key = jsonKey(entry.getKey(), generator);
                if (!keys.add(key)) throw JsonMappingException.from(generator, "评测 Trace 键转换后重名：" + key);
                generator.writeFieldName(key);
                provider.defaultSerializeValue(entry.getValue(), generator);
            }
            generator.writeEndObject();
        }

        private static String jsonKey(Object key, JsonGenerator generator) throws JsonMappingException {
            if (key instanceof String text) return text;
            if (key instanceof Byte || key instanceof Short || key instanceof Integer || key instanceof Long
                    || key instanceof BigInteger || key instanceof BigDecimal) return key.toString();
            if (key instanceof Double number && Double.isFinite(number)) return number.toString();
            if (key instanceof Float number && Float.isFinite(number)) return number.toString();
            throw JsonMappingException.from(generator, "评测 Trace 不支持的对象键类型："
                    + (key == null ? "null" : key.getClass().getName()));
        }
    }

    /** Enforce the same size bound on writes and reads without building an oversized JSON string. */
    private static final class BoundedTraceWriter extends Writer {
        private final StringBuilder output = new StringBuilder();

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            requireCapacity(length);
            output.append(chars, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) throws IOException {
            requireCapacity(length);
            output.append(value, offset, offset + length);
        }

        private void requireCapacity(int length) throws IOException {
            if (length > MAX_TRACE_CHARS - output.length()) throw new IOException("评测 Trace 超过写入上限");
        }

        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return output.toString(); }
    }

    static JSONObject read(String source) {
        if (source == null || source.isBlank()) return new JSONObject(true);
        if (source.length() > MAX_TRACE_CHARS) throw new IllegalArgumentException("评测 Trace 超过读取上限，原始记录已保留");
        String candidate = source;
        List<String> recoveredFields = new ArrayList<>(3);
        for (int attempt = 0; attempt <= 3; attempt++) {
            try (JsonParser parser = MAPPER.createParser(candidate)) {
                JsonNode parsed;
                try {
                    parsed = MAPPER.readTree(parser);
                } catch (JsonProcessingException error) {
                    LegacyRepair repair = identifyLegacyRepair(candidate, parser, error);
                    if (repair == null || attempt == 3 || recoveredFields.contains(repair.field())) throw error;
                    recoveredFields.add(repair.field());
                    candidate = candidate.substring(0, repair.start()) + repair.replacement()
                            + candidate.substring(repair.end());
                    continue;
                }
                if (!(parsed instanceof ObjectNode root)) throw new IllegalArgumentException("评测 Trace 必须为 JSON 对象");
                if (!recoveredFields.isEmpty()) restoreLegacyCopies(root, recoveredFields);
                @SuppressWarnings("unchecked")
                Map<String, Object> values = MAPPER.convertValue(root, LinkedHashMap.class);
                return new JSONObject(values);
            } catch (IOException | IllegalArgumentException error) {
                // Do not feed malformed/partial evidence into a paid judge or silently
                // convert it into an empty trace. The original database value is retained.
                throw new IllegalArgumentException("评测 Trace 解析失败，原始记录已保留", error);
            }
        }
        throw new IllegalArgumentException("评测 Trace 解析失败，原始记录已保留");
    }

    private static LegacyRepair identifyLegacyRepair(String source, JsonParser parser,
                                                       JsonProcessingException error) {
        JsonStreamContext context = parser.getParsingContext();
        JsonStreamContext parent = context == null ? null : context.getParent();
        if (context == null || !context.inObject() || parent == null || !parent.inObject()) return null;
        long position = error.getLocation() == null ? -1 : error.getLocation().getCharOffset();
        if (position < 0 || position >= source.length()) return null;
        // The comma-validation failure points at the unexpected opening brace.
        int start = (int) position;
        if ("runSnapshot".equals(parent.getCurrentName())
                && parent.getParent() != null && parent.getParent().inRoot()) {
            Matcher matcher = LEGACY_ORPHAN.matcher(source).region(start, source.length());
            if (!matcher.lookingAt()) return null;
            String field = matcher.group(1);
            return new LegacyRepair(start, matcher.end(), field, ",\"" + field + "\":null");
        }
        JsonStreamContext grandparent = parent.getParent();
        if (!"flowPlanDependencies".equals(parent.getCurrentName()) || grandparent == null
                || !"runSnapshot".equals(grandparent.getCurrentName()) || grandparent.getParent() == null
                || !grandparent.getParent().inRoot() || start == 0 || source.charAt(start - 1) != '{') return null;
        // The old writer also emitted integer Map keys without quotes. Only accept the
        // complete known DAG grammar here, never unquoted keys in arbitrary tool output.
        Matcher dependencies = LEGACY_DEPENDENCIES.matcher(source)
                .region(start - 1, Math.min(source.length(), start - 1 + 64_000));
        if (!dependencies.lookingAt()) return null;
        String raw = dependencies.group();
        if (raw.length() > 64_000) return null;
        String fixed = LEGACY_INTEGER_KEY.matcher(raw).replaceAll("\"$1\":");
        return new LegacyRepair(start - 1, dependencies.end(), "flowPlanDependencies", fixed);
    }

    private static void restoreLegacyCopies(ObjectNode root, List<String> fields) {
        if (!LEGACY_SCHEMA.equals(root.path("traceSchemaVersion").asText())
                || !(root.get("runSnapshot") instanceof ObjectNode snapshot)
                || root.has("traceReadCompatibility")) {
            throw new IllegalArgumentException("不支持的历史评测 Trace 结构");
        }
        for (String field : fields) {
            if ("flowPlanDependencies".equals(field)) continue;
            JsonNode original = root.get(field);
            if (original == null || !original.isArray() || !snapshot.path(field).isNull()) {
                throw new IllegalArgumentException("历史评测 Trace 缺少可验证的重复字段：" + field);
            }
            snapshot.set(field, original.deepCopy());
        }
        ObjectNode compatibility = root.putObject("traceReadCompatibility");
        compatibility.put("method", "fastjson-2.0.28-snapshot-json");
        compatibility.put("sourceUnchanged", true);
        fields.forEach(compatibility.putArray("restoredFields")::add);
    }

    private record LegacyRepair(int start, int end, String field, String replacement) {
    }
}
