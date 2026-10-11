package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageContext;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Final wire boundary, including recursive tool-loop requests and synchronous helpers. */
public final class ModelRequestGuard {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static volatile int maxInputEstimate = 128000;
    private static volatile int defaultOutputLimit = 8192;
    private static volatile Map<String, Integer> modelContextWindows = Map.of();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelRequestGuard.class);
    private static class Tokenizer {
        static final com.knuddels.jtokkit.api.Encoding INSTANCE =
                com.knuddels.jtokkit.Encodings.newLazyEncodingRegistry()
                        .getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
    }
    private ModelRequestGuard() {}

    public static void configure(int inputLimit, int outputLimit) {
        configure(inputLimit, outputLimit, Map.of());
    }

    public static void configure(int inputLimit, int outputLimit, Map<String, Integer> contextWindows) {
        if (inputLimit < 1024 || outputLimit < 1) throw new IllegalArgumentException("Invalid context budget");
        if (contextWindows == null || contextWindows.entrySet().stream().anyMatch(e ->
                e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue() < 1024))
            throw new IllegalArgumentException("Invalid model context window");
        maxInputEstimate = inputLimit;
        defaultOutputLimit = outputLimit;
        modelContextWindows = Map.copyOf(contextWindows);
    }

    public record Prepared(byte[] body, RunBudgetRegistry.Reservation reservation, ModelUsageAccounting.Ticket usageTicket) {}

    public static Prepared prepare(Map<String, Object> request, RunBudgetRegistry.State budget) throws IOException {
        return prepare(request,budget,null);
    }
    public static Prepared prepare(Map<String, Object> request, RunBudgetRegistry.State budget,ModelUsageContext usageContext) throws IOException {
        // The guard may compact nested tool messages. Never mutate the caller's persisted history.
        request = JSON.readValue(JSON.writeValueAsBytes(request), MAP);
        if (!(request.get("messages") instanceof List<?> original)) {
            // Embeddings share the same provider budget but have no completion allowance.
            if(!request.containsKey("input"))throw new IllegalArgumentException("不支持的模型请求结构，未发送请求");
            long input=estimate(request);
            var reservation = budget != null ? budget.reserveModel(input, 0) : null;
            return new Prepared(JSON.writeValueAsBytes(request), reservation,account(budget,usageContext,String.valueOf(request.get("model")),input,0));
        }
        // Work on the outgoing copy only. Never mutate persisted history.
        List<Object> messages = new ArrayList<>(original);
        request.put("messages", messages);
        String outputKey = request.containsKey("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
        long output = request.get(outputKey) instanceof Number n && n.longValue() > 0
                ? n.longValue() : defaultOutputLimit;
        request.put(outputKey, output);
        Integer window = modelContextWindows.get(String.valueOf(request.get("model")));
        long inputLimit = window == null ? maxInputEstimate : Math.min(maxInputEstimate, (long) window - output);
        if (inputLimit < 1) throw new RunBudgetExceededException(
                "模型配置的输出上限已占满上下文窗口，请调整该模型的上下文或输出配置");
        long before = estimate(request), estimate = before;
        int compacted = 0, removedTurns = 0;
        // Reduce already-stored evidence before dropping old conversation turns. Keep call/result
        // pairs, current task, user corrections and system constraints exactly as supplied.
        for (int i = 0; estimate > inputLimit && i < messages.size(); i++) {
            if (compactReferencedResult(messages, i)) { compacted++; estimate = estimate(request); }
        }
        while (estimate > inputLimit) {
            // Remove complete old user turns, retaining system instructions and the last two
            // user messages (shared ask_user facts + current task). Tool pairs remain together.
            List<Integer> starts = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++)
                if (messages.get(i) instanceof Map<?, ?> m && "user".equals(m.get("role"))) starts.add(i);
            if (starts.size() <= 2) {
                log.warn("[ContextBudget] cannot fit runId={} model={} estimatedTokens={} inputLimit={} compactedResults={} removedTurns={}",
                        budget == null ? null : budget.runId(), request.get("model"), estimate, inputLimit, compacted, removedTurns);
                throw new RunBudgetExceededException("已压缩可引用的工具结果，当前请求仍超过上下文容量（估算输入 "
                        + estimate + " Token，可用 " + inputLimit + "；已保留当前任务和用户约束）。请拆分本步任务或选择更大上下文的模型");
            }
            int from = starts.get(0), to = starts.get(1);
            List<Object> removed = new ArrayList<>(messages.subList(from, to));
            if (removed.stream().anyMatch(v -> v instanceof Map<?, ?> m &&
                    ("system".equals(m.get("role")) || "developer".equals(m.get("role")))))
                throw new RunBudgetExceededException("上下文超出预算，无法安全移除系统约束");
            if (crossesToolPairBoundary(messages, removed))
                throw new RunBudgetExceededException("上下文超出容量，旧轮次与当前工具结果有关联，无法安全删除。请拆分本步任务或选择更大上下文的模型");
            messages.subList(from, to).clear();
            removedTurns++;
            estimate = estimate(request);
        }
        if (compacted > 0 || removedTurns > 0) log.info(
                "[ContextBudget] fitted runId={} estimatedTokensBefore={} estimatedTokensAfter={} inputLimit={} compactedResults={} removedTurns={}",
                budget == null ? null : budget.runId(), before, estimate, inputLimit, compacted, removedTurns);
        RunBudgetRegistry.Reservation reservation = budget == null ? null : budget.reserveModel(estimate, output);
        return new Prepared(JSON.writeValueAsBytes(request), reservation,account(budget,usageContext,String.valueOf(request.get("model")),estimate,output));
    }

    public static ModelUsageAccounting.Ticket account(RunBudgetRegistry.State budget,ModelUsageContext context,
                                                      String model,long inputEstimate,long outputLimit) {
        if(!ModelUsageLedger.enabled())return null;
        if(budget==null||context==null)throw new SecurityException("模型调用缺少运行预算或提供商归属，未发送请求");
        budget.checkActive();
        String purpose=context.purpose();
        if(purpose==null||purpose.isBlank())purpose=org.slf4j.MDC.get("modelPurpose");
        return ModelUsageLedger.begin(new ModelUsageAccounting.Attempt(budget.ownerUserId(),budget.runId(),context.apiId(),model,
                purpose,context.providerHost(),inputEstimate,outputLimit));
    }

    /** CL100K BPE estimate plus 20% and message framing; provider usage is the billing source.
     * Other providers can tokenize differently, so this is never advertised as an exact count. */
    static long estimate(Map<String, Object> request) throws IOException {
        Object copy = JSON.readValue(JSON.writeValueAsBytes(request), Object.class);
        long images = replaceImagePayloads(copy);
        // User text that spells a tokenizer sentinel is ordinary text, not a protocol token.
        int tokens = Tokenizer.INSTANCE.countTokensOrdinary(JSON.writeValueAsString(copy));
        int messages = request.get("messages") instanceof List<?> list ? list.size() : 0;
        return (long) Math.ceil(tokens * 1.2d) + messages * 8L + 32L + images * 16384L;
    }

    @SuppressWarnings("unchecked")
    private static boolean compactReferencedResult(List<Object> messages, int index) throws IOException {
        if (!(messages.get(index) instanceof Map<?, ?> raw) || !"tool".equals(raw.get("role"))
                || !(raw.get("content") instanceof String text) || text.length() < 1800) return false;
        Map<String, Object> envelope;
        try { envelope = JSON.readValue(text, MAP); } catch (IOException malformed) { return false; }
        if (!(envelope.get("resultRef") instanceof String ref) || !ref.matches("tool:[a-zA-Z0-9_-]{1,100}")
                || !ToolResultReferenceService.TOOL_NAME.equals(envelope.get("readTool"))
                || !envelope.containsKey("summary")) return false;
        Object summary = envelope.get("summary");
        String summaryText = summary instanceof String value ? value : JSON.writeValueAsString(summary);
        if (summaryText.length() < 1000) return false;
        // Do not turn a structured summary into a cut JSON string with half an id/coordinate.
        // Keep a complete candidate if it fits, otherwise retain the authorized reference alone.
        Object structured = summary;
        if (summary instanceof String) {
            try { structured = JSON.readValue(summaryText, Object.class); } catch (IOException plainText) { /* text preview below */ }
        }
        if (structured instanceof Map<?, ?> map) {
            Map<String, Object> bounded = new java.util.LinkedHashMap<>();
            bounded.put("contextCompacted", true);
            bounded.put("detailsAvailableViaResultRef", true);
            for (String key : List.of("source", "status", "ok", "error", "code", "totalPois")) {
                if (map.containsKey(key)) {
                    Object value = map.get(key);
                    // Never hide an oversized error under a success-like projection.
                    if (JSON.writeValueAsString(value).length() > 300) return false;
                    bounded.put(key, value);
                }
            }
            if (map.get("pois") instanceof List<?> pois && !pois.isEmpty()
                    && JSON.writeValueAsString(pois.get(0)).length() <= 900) {
                bounded.put("pois", List.of(pois.get(0)));
                bounded.put("returnedPois", 1);
            }
            envelope.put("summary", bounded);
        } else if (structured instanceof List<?>) {
            envelope.put("summary", Map.of("contextCompacted", true, "detailsAvailableViaResultRef", true));
        } else {
            int end = Math.min(700, summaryText.length());
            if (end > 0 && Character.isHighSurrogate(summaryText.charAt(end - 1))) end--;
            envelope.put("summary", summaryText.substring(0, end));
        }
        envelope.put("inlineTruncated", true);
        envelope.put("contextCompacted", true);
        envelope.put("message", "为控制当前上下文，已缩短此前工具摘要。需要完整字段时使用 resultRef 和 inspect_tool_result 读取原始结果，无需重新执行原工具。");
        ((Map<String, Object>) raw).put("content", JSON.writeValueAsString(envelope));
        return true;
    }

    private static boolean crossesToolPairBoundary(List<Object> all, List<Object> removed) {
        java.util.Set<Object> removedCalls = toolIds(removed, true), removedResults = toolIds(removed, false);
        List<Object> retained = new ArrayList<>(all);
        // Compare by reference: identical messages may legitimately occur in separate turns.
        retained.removeIf(value -> removed.stream().anyMatch(deleted -> deleted == value));
        return toolIds(retained, false).stream().anyMatch(removedCalls::contains)
                || toolIds(retained, true).stream().anyMatch(removedResults::contains);
    }

    private static java.util.Set<Object> toolIds(List<Object> messages, boolean calls) {
        java.util.Set<Object> ids = new java.util.HashSet<>();
        for (Object value : messages) if (value instanceof Map<?, ?> message) {
            if (calls && "assistant".equals(message.get("role")) && message.get("tool_calls") instanceof List<?> tools) {
                for (Object tool : tools) if (tool instanceof Map<?, ?> call && call.get("id") != null) ids.add(call.get("id"));
            } else if (!calls && "tool".equals(message.get("role")) && message.get("tool_call_id") != null) ids.add(message.get("tool_call_id"));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static long replaceImagePayloads(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String,Object> map = (Map<String,Object>) raw;
            if ("image_url".equals(map.get("type")) && map.containsKey("image_url")) {
                map.put("image_url", "[image input; provider usage settles actual cost]");
                return 1;
            }
            long count = 0;
            for (Object child : map.values()) count += replaceImagePayloads(child);
            return count;
        }
        if (value instanceof List<?> list) {
            long count = 0;
            for (Object child : list) count += replaceImagePayloads(child);
            return count;
        }
        return 0;
    }

    /** Per-request read timeout respects the remaining run deadline, including synchronous helpers. */
    public static org.springframework.http.client.ClientHttpRequestFactory requestFactory() {
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        return (uri, method) -> {
            RunBudgetRegistry.State budget = RunBudgetRegistry.find(Map.of());
            if (budget != null) budget.checkActive();
            long millis = budget == null ? 90000 : Math.max(1L, Math.min(90000L,
                    ((Number) budget.snapshot().get("remainingMillis")).longValue()));
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(java.time.Duration.ofMillis(millis));
            return factory.createRequest(uri, method);
        };
    }

    public static Long usage(Map<String, Object> response) {
        if (!(response.get("usage") instanceof Map<?, ?> usage)) return null;
        if (usage.get("total_tokens") instanceof Number total) return total.longValue();
        if (usage.get("prompt_tokens") instanceof Number input && usage.get("completion_tokens") instanceof Number output)
            return input.longValue() + output.longValue();
        return null;
    }

    public static ClientHttpRequestInterceptor synchronous() {
        return synchronous(null);
    }
    public static ClientHttpRequestInterceptor synchronous(ModelUsageContext usageContext) {
        return (request, body, execution) -> {
            if(request.getMethod()!=org.springframework.http.HttpMethod.POST)return execution.execute(request,body);
            Map<String, Object> json;
            try { json = JSON.readValue(body, MAP); }
            catch (Exception invalid) { throw new IllegalArgumentException("模型请求体不可解析，未发送未经预算检查的请求"); }
            RunBudgetRegistry.State budget = RunBudgetRegistry.find(Map.of());
            Prepared prepared = prepare(json, budget,usageContext);
            request.getHeaders().setContentLength(prepared.body().length);
            RunBudgetRegistry.Reservation reservation = prepared.reservation();
            ClientHttpResponse response = null;
            boolean handedOff = false;
            try {
                response = execution.execute(request, prepared.body());
                byte[] bytes = response.getBody().readAllBytes();
                Long tokens = null;
                try { tokens = usage(JSON.readValue(bytes, MAP)); } catch (Exception ignored) {}
                if (reservation != null) reservation.settle(tokens);
                if(prepared.usageTicket()!=null)prepared.usageTicket().settle(tokens,response.getStatusCode().is2xxSuccessful()?"SUCCESS":"ERROR");
                if (budget != null) budget.checkActive();
                ClientHttpResponse delegate = response;
                handedOff = true;
                return new ClientHttpResponse() {
                    @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
                    public int getRawStatusCode() throws IOException { return delegate.getStatusCode().value(); }
                    @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
                    @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
                    @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                    @Override public void close() { delegate.close(); }
                };
            } finally {
                try {
                    if (reservation != null) reservation.settle(null);
                    if(prepared.usageTicket()!=null)prepared.usageTicket().settle(null,"ERROR");
                } catch (RuntimeException accountingFailure) {
                    handedOff = false;
                    throw accountingFailure;
                } finally {
                    if (!handedOff && response != null) response.close();
                }
            }
        };
    }
}
