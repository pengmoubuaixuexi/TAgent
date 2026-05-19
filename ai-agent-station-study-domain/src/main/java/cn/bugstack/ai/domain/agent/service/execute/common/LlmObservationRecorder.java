package cn.bugstack.ai.domain.agent.service.execute.common;

import cn.bugstack.ai.domain.agent.service.execute.EventLogEntry;
import cn.bugstack.ai.domain.agent.service.execute.IEventLogService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Single writer for LLM observability sinks:
 * Prometheus metrics, ai_event_log, and MDC fields consumed by Logstash/ES.
 */
@Slf4j
@Component
public class LlmObservationRecorder {

    public static final String BILLING_SCOPE_USER_CHARGEABLE = "USER_CHARGEABLE";
    public static final String BILLING_SCOPE_SYSTEM_OVERHEAD = "SYSTEM_OVERHEAD";

    @Resource
    private LlmMetrics llmMetrics;

    @Autowired(required = false)
    private IEventLogService eventLogService;

    public void record(LlmCallContext ctx, ChatResponse response, long latencyMs, Throwable error) {
        if (ctx == null) {
            ctx = LlmCallContext.builder().build();
        }

        Usage usage = response != null && response.getMetadata() != null
                ? response.getMetadata().getUsage()
                : null;
        long promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
        long completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;
        long responseTotalTokens = usage != null && usage.getTotalTokens() != null ? usage.getTotalTokens() : 0L;
        long totalTokens = promptTokens + completionTokens;
        if (totalTokens <= 0L) {
            totalTokens = responseTotalTokens;
        }

        long cachedTokens = extractCachedPromptTokens(usage);
        String model = firstNonBlank(
                response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null,
                ctx.getModel(),
                "unknown");
        String stepName = firstNonBlank(ctx.getStepName(), "unknown_step");
        String resolvedSessionId = firstNonBlank(ctx.getSessionId(), MDC.get("sessionId"), MDC.get("requestId"), "unknown-session");
        String billingScope = firstNonBlank(ctx.getBillingScope(), inferBillingScope(stepName));
        String resultText = firstNonBlank(ctx.getResultText(), extractResultText(response));
        boolean success = error == null && resultText != null && !resultText.isBlank();

        try {
            llmMetrics.record(stepName, model, latencyMs, promptTokens, completionTokens, success);
            if (cachedTokens > 0L) {
                llmMetrics.recordCachedTokens(stepName, model, cachedTokens);
            }
        } catch (Exception e) {
            log.debug("LLM metric record failed: {}", e.getMessage());
        }

        if (eventLogService != null && success) {
            try {
                eventLogService.log(EventLogEntry.builder()
                        .sessionId(resolvedSessionId)
                        .userId(firstNonBlank(ctx.getUserId(), MDC.get("userId")))
                        .tenantId(firstNonBlank(ctx.getTenantId(), MDC.get("tenantId")))
                        .agentId(firstNonBlank(ctx.getAgentId(), MDC.get("agentId")))
                        .billingScope(billingScope)
                        .stepName(stepName)
                        .stepIndex(0)
                        .inputPrompt(ctx.getPrompt())
                        .outputText(resultText)
                        .model(model)
                        .promptTokens((int) promptTokens)
                        .completionTokens((int) completionTokens)
                        .latencyMs(latencyMs)
                        .build());
            } catch (Exception e) {
                log.debug("LLM event log record failed: {}", e.getMessage());
            }
        }

        writeEsLog(ctx, stepName, model, resolvedSessionId, billingScope, promptTokens, completionTokens, totalTokens, cachedTokens, latencyMs, success, error);
    }

    private void writeEsLog(LlmCallContext ctx, String stepName, String model,
                            String resolvedSessionId, String billingScope, long promptTokens, long completionTokens, long totalTokens,
                            long cachedTokens, long latencyMs, boolean success, Throwable error) {
        String oldStep = MDC.get("step");
        String oldModel = MDC.get("model");
        String oldClientId = MDC.get("clientId");
        String oldSessionId = MDC.get("sessionId");
        String oldUserId = MDC.get("userId");
        String oldTenantId = MDC.get("tenantId");
        String oldAgentId = MDC.get("agentId");
        String oldBillingScope = MDC.get("billingScope");
        String oldPromptTokens = MDC.get("promptTokens");
        String oldCompletionTokens = MDC.get("completionTokens");
        String oldTotalTokens = MDC.get("totalTokens");
        String oldLatencyMs = MDC.get("latencyMs");
        String oldCachedTokens = MDC.get("cachedTokens");

        putIfPresent("step", stepName);
        putIfPresent("model", model);
        putIfPresent("clientId", ctx.getClientId());
        putIfPresent("sessionId", resolvedSessionId);
        putIfPresent("userId", firstNonBlank(ctx.getUserId(), MDC.get("userId")));
        putIfPresent("tenantId", firstNonBlank(ctx.getTenantId(), MDC.get("tenantId")));
        putIfPresent("agentId", firstNonBlank(ctx.getAgentId(), MDC.get("agentId")));
        putIfPresent("billingScope", billingScope);
        MDC.put("promptTokens", String.valueOf(promptTokens));
        MDC.put("completionTokens", String.valueOf(completionTokens));
        MDC.put("totalTokens", String.valueOf(totalTokens));
        MDC.put("latencyMs", String.valueOf(latencyMs));
        if (cachedTokens > 0L) {
            MDC.put("cachedTokens", String.valueOf(cachedTokens));
        }
        try {
            if (error == null) {
                log.info("LLM step completed: step={} model={} clientId={} promptTokens={} completionTokens={} totalTokens={} latencyMs={} success={}",
                        stepName, model, ctx.getClientId(), promptTokens, completionTokens, totalTokens, latencyMs, success);
            } else {
                log.info("LLM step completed: step={} model={} clientId={} promptTokens={} completionTokens={} totalTokens={} latencyMs={} success=false error={}",
                        stepName, model, ctx.getClientId(), promptTokens, completionTokens, totalTokens, latencyMs, error.getClass().getSimpleName());
            }
        } finally {
            restore("step", oldStep);
            restore("model", oldModel);
            restore("clientId", oldClientId);
            restore("sessionId", oldSessionId);
            restore("userId", oldUserId);
            restore("tenantId", oldTenantId);
            restore("agentId", oldAgentId);
            restore("billingScope", oldBillingScope);
            restore("promptTokens", oldPromptTokens);
            restore("completionTokens", oldCompletionTokens);
            restore("totalTokens", oldTotalTokens);
            restore("latencyMs", oldLatencyMs);
            restore("cachedTokens", oldCachedTokens);
        }
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String extractResultText(ChatResponse response) {
        return response != null
                && response.getResult() != null
                && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String inferBillingScope(String stepName) {
        if (stepName == null) {
            return BILLING_SCOPE_USER_CHARGEABLE;
        }
        return switch (stepName) {
            case "unified_router", "rag_router", "query_decomposer", "query_rewriter",
                 "memory_extractor", "summarizer", "reranker" -> BILLING_SCOPE_SYSTEM_OVERHEAD;
            default -> BILLING_SCOPE_USER_CHARGEABLE;
        };
    }

    private long extractCachedPromptTokens(Usage usage) {
        if (usage == null) return 0L;
        try {
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage == null) return 0L;
            try {
                java.lang.reflect.Method m1 = nativeUsage.getClass().getMethod("promptTokensDetails");
                Object details = m1.invoke(nativeUsage);
                if (details != null) {
                    java.lang.reflect.Method m2 = details.getClass().getMethod("cachedTokens");
                    Object value = m2.invoke(details);
                    if (value instanceof Number n) return n.longValue();
                }
            } catch (NoSuchMethodException ignored) {
            }
            try {
                java.lang.reflect.Method method = nativeUsage.getClass().getMethod("cacheReadInputTokens");
                Object value = method.invoke(nativeUsage);
                if (value instanceof Number n) return n.longValue();
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
}
