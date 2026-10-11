package cn.bugstack.ai.domain.agent.service.rag.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Optional answer reuse for server-verified stateless clients. The final complete prompt fingerprint,
 * trusted owner, agent version and model partition every lookup; dynamic advisors/tools bypass it.
 * Runs after normal prompt rendering, so cache hits cannot skip ownership-sensitive context assembly.
 */
@Slf4j
public class SemanticCacheAdvisor implements BaseAdvisor {

    public static final String STATELESS = "semantic_cache.stateless";
    public static final String MODEL_ID = "semantic_cache.model_id";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ISemanticCacheService cacheService;
    private final double minSimilarity;
    private final int order;
    /** 缓存有效期（分钟），只命中此时间窗口内的记录 */
    private final int ttlMinutes;
    /** Claude 改进：命中/未命中分别打 counter，Grafana 直接画 cache hit rate */
    private final MeterRegistry meterRegistry;

    public SemanticCacheAdvisor(ISemanticCacheService cacheService, double minSimilarity) {
        this(cacheService, minSimilarity, -200, 10, null);
    }

    public SemanticCacheAdvisor(ISemanticCacheService cacheService, double minSimilarity, int order) {
        this(cacheService, minSimilarity, order, 10, null);
    }

    public SemanticCacheAdvisor(ISemanticCacheService cacheService, double minSimilarity, int order, MeterRegistry meterRegistry) {
        this(cacheService, minSimilarity, order, 10, meterRegistry);
    }

    public SemanticCacheAdvisor(ISemanticCacheService cacheService, double minSimilarity, int order, int ttlMinutes, MeterRegistry meterRegistry) {
        this.cacheService = cacheService;
        this.minSimilarity = minSimilarity;
        // Shared-user-input and multimodal renderers run before this final-input fingerprint.
        this.order = Math.max(order, 1100);
        this.ttlMinutes = ttlMinutes > 0 ? ttlMinutes : 10;
        this.meterRegistry = meterRegistry;
    }

    private void recordOutcome(String outcome) {
        if (meterRegistry == null) return;
        Counter.builder("agent.semantic_cache.lookup")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public String getName() {
        return "semantic_cache";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // P2.4 13.1 Intent Gate：plan / action 类意图不能走缓存（订票、执行 MCP 等操作），
        // 只有 qa / chat 类知识问答才适合语义缓存命中直接返回。
        // intentCategory 由 AgentDispatchDispatchService 在 dispatch 层写入 MDC。
        String intentCategory = org.slf4j.MDC.get("intentCategory");
        ISemanticCacheService.Scope scope = scope(request);
        if (scope == null || !("qa".equals(intentCategory) || "chat".equals(intentCategory))) {
            recordOutcome("bypass");
            log.info("[SemanticCache] BYPASS intentCategory={}", intentCategory);
            return chain.nextCall(request);
        }

        String userText = extractUserText(request);
        if (userText == null || userText.isBlank()) {
            recordOutcome("skip");
            log.info("[SemanticCache] SKIP empty userText, intentCategory={}", intentCategory);
            return chain.nextCall(request);
        }

        // 查缓存：命中直接返回，跳过 LLM 调用（仅命中 ttlMinutes 内的记录）
        String cached = cacheService.lookup(userText, minSimilarity, ttlMinutes, scope);
        if (cached != null) {
            recordOutcome("hit");
            log.info("[SemanticCache] HIT queryLen={} ttl={}min intentCategory={}", userText.length(), ttlMinutes, intentCategory);
            // 异步清理过期缓存（> ttlMinutes 的记录）
            CompletableFuture.runAsync(() -> {
                try {
                    cacheService.deleteOlderThan(ttlMinutes);
                } catch (Exception ignored) {}
            });
            ChatResponse chatResponse = ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(cached))))
                    .metadata("semantic_cache_hit", "true")
                    .build();
            return ChatClientResponse.builder()
                    .chatResponse(chatResponse)
                    .context(request.context())
                    .build();
        }
        recordOutcome("miss");
        log.info("[SemanticCache] MISS queryLen={} ttl={}min intentCategory={}", userText.length(), ttlMinutes, intentCategory);

        // 缓存未命中：正常调用 LLM
        ChatClientResponse response = chain.nextCall(request);

        // 存入缓存
        try {
            String answer = response.chatResponse() != null
                    && response.chatResponse().getResult() != null
                    && response.chatResponse().getResult().getOutput() != null
                    ? response.chatResponse().getResult().getOutput().getText()
                    : null;
            String model = response.chatResponse() != null
                    && response.chatResponse().getMetadata() != null
                    ? (String) response.chatResponse().getMetadata().get("model")
                    : null;
            if (userText != null && answer != null && !answer.isBlank()) {
                cacheService.store(userText, answer, scope);
                log.info("[SemanticCache] STORED queryLen={} model={}", userText.length(), model);
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] store failed: {}", e.getMessage(), e);
        }

        return response;
    }

    private String extractUserText(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        if (messages != null) {
            for (Message msg : messages) {
                if (msg.getMessageType() == MessageType.USER) {
                    return msg.getText();
                }
            }
        }
        return null;
    }

    private ISemanticCacheService.Scope scope(ChatClientRequest request) {
        // Only assembly-verified stateless clients may skip the downstream advisor chain.
        if (!Boolean.TRUE.equals(request.context().get(STATELESS))
                || "workspace_node_debug".equals(org.slf4j.MDC.get("step"))) return null;
        var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.find(Map.of());
        if (budget == null) return null;
        budget.checkActive();
        var messages = request.prompt().getInstructions();
        if (messages.stream().filter(m -> m.getMessageType() == MessageType.USER).count() != 1
                || messages.stream().anyMatch(m -> m.getMessageType() != MessageType.USER
                    && m.getMessageType() != MessageType.SYSTEM)
                || messages.stream().anyMatch(m -> m instanceof org.springframework.ai.chat.messages.UserMessage u
                    && !u.getMedia().isEmpty())) return null;
        if (request.prompt().getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
                && ((tools.getToolCallbacks() != null && !tools.getToolCallbacks().isEmpty())
                    || (tools.getToolNames() != null && !tools.getToolNames().isEmpty()))) return null;
        try {
            // Include complete input/options, not only query similarity. Histories, options and prompts
            // must never reuse another answer even inside the same user/agent/model/version boundary.
            var input = Map.of("messages", messages.stream().map(m -> Map.of(
                    "type", m.getMessageType().name(), "text", m.getText() == null ? "" : m.getText(),
                    "metadata", m.getMetadata())).toList(),
                    "options", request.prompt().getOptions() == null ? Map.of() : request.prompt().getOptions());
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(input)));
            Object model = request.context().get(MODEL_ID);
            var result = new ISemanticCacheService.Scope(budget.ownerUserId(), org.slf4j.MDC.get("agentId"),
                    org.slf4j.MDC.get("agentVersion"), model instanceof String s ? s : null, hash);
            return result.valid() ? result : null;
        } catch (Exception cannotFingerprint) {
            return null;
        }
    }
}
