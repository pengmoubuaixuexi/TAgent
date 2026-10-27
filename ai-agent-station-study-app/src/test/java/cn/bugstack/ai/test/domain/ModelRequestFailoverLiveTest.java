package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.model.ModelCredentialRequestAuthorizer;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelConnectionAssembler;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelEndpointRegistry;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelRetryPolicy;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.retry.support.RetryTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Real provider check, skipped unless MIMO_API_KEY is set: mimo-v2.5 answers
 * "400 Unsupported model", the request layer moves the whole tool loop to mimo-v2.6-flash.
 */
public class ModelRequestFailoverLiveTest {
    private static final String KEY = System.getenv("MIMO_API_KEY");
    private final ModelRequestFailoverTest.Usage usage = new ModelRequestFailoverTest.Usage();
    private final ModelRequestFailoverTest.Health health = new ModelRequestFailoverTest.Health();
    private final ModelRequestFailoverTest.Weather weather = new ModelRequestFailoverTest.Weather();
    private String run;

    @Before public void start() {
        Assume.assumeTrue("MIMO_API_KEY not set", KEY != null && !KEY.isBlank());
        ModelUsageLedger.install(usage);
        run = "failover-live-" + UUID.randomUUID();
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(5), 10, 10, 400_000));
        MDC.put("runId", run);
        MDC.put("userId", "alice");
    }

    @After public void stop() {
        if (run == null) return;
        ModelUsageLedger.uninstall(usage);
        RunBudgetRegistry.finish(run);
        ReasoningContentFilter.clearRun(run);
        MDC.clear();
    }

    @Test public void synchronousToolLoopMovesFromV25ToV26Flash() throws Exception {
        String answer = model().call(prompt(null)).getResult().getOutput().getText();
        report("sync", answer);
    }

    @Test public void streamingToolLoopMovesFromV25ToV26Flash() throws Exception {
        var activity = StreamingActivityTracker.start("failover-live");
        String answer;
        try (var call = ReasoningContentFilter.scopeCall("live", run); var bound = StreamingActivityTracker.scope(activity)) {
            answer = StreamingActivityTracker.timeoutOnInactivity(model().stream(prompt(activity)), activity, Duration.ofSeconds(90))
                    .map(ModelRequestFailoverLiveTest::text).collect(Collectors.joining()).block(Duration.ofMinutes(3));
        }
        report("stream", answer);
    }

    private void report(String mode, String answer) throws Exception {
        List<String> attempts = usage.settled();
        System.out.println("[live-failover] mode=" + mode + " attempts=" + attempts + " substitutions=" + health.substitutions
                + " budget=" + RunBudgetRegistry.find(run).snapshot() + " answer=" + answer);
        assertNotNull(answer);
        assertFalse(answer.isBlank());
        assertEquals("tool ran exactly once", 1, weather.calls.get());
        assertTrue(attempts.get(0), attempts.get(0).startsWith("mimo/mimo-v2.5/ERROR/"));
        assertEquals(3, attempts.size());
        assertTrue(attempts.subList(1, 3).stream().allMatch(a -> a.startsWith("mimo/mimo-v2.6-flash/") && !a.endsWith("/null")));
    }

    private OpenAiChatModel model() {
        ModelProviderAccess access = new ModelProviderAccess() {
            @Override public String apiKeyForRequest(String apiId, String user) { return KEY; }
            @Override public String apiKeyForRequest(String apiId, String user, URI uri) { return KEY; }
            @Override public void requirePlatformAuxiliary(String user, String purpose) { }
        };
        var substitute = new ModelTarget("mimo", "mimo-v2.6-flash");
        var assembler = new ModelConnectionAssembler(new ModelCredentialRequestAuthorizer(access), new ModelEndpointRegistry(),
                (requested, traits) -> "mimo-v2.5".equals(requested.model()) ? List.of(substitute) : List.of(),
                health, ModelRetryPolicy.SINGLE_ATTEMPT);
        var api = assembler.assemble(new ModelConnectionAssembler.Settings(
                "mimo", "https://api.xiaomimimo.com", "/v1/chat/completions", "/v1/embeddings", null));
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("mimo-v2.5").toolCallbacks(List.of(weather)).build())
                .toolCallingManager(new RobustToolCallingManager(ToolCallingManager.builder().build()))
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    private Prompt prompt(StreamingActivityTracker.Activity activity) {
        Map<String, Object> context = new HashMap<>(Map.of("userId", "alice", "agent.run_id", run, "sessionId", "live",
                ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.BUSINESS_ONLY.name()));
        if (activity != null) context.put(StreamingActivityTracker.CONTEXT_KEY, activity);
        return new Prompt("用 get_weather 工具查一下北京今天的天气，然后用一句话回答。",
                OpenAiChatOptions.builder().toolContext(context).build());
    }

    private static String text(ChatResponse response) {
        return response.getResult() != null && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null ? response.getResult().getOutput().getText() : "";
    }
}
