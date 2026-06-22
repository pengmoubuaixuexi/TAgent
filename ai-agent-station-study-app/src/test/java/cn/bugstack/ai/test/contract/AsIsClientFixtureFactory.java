package cn.bugstack.ai.test.contract;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic, deterministic fixtures for the twelve main-chain call prototypes.
 * They freeze structural AS-IS behavior; real DB prompt text is fingerprinted in the manifest.
 */
public final class AsIsClientFixtureFactory {

    public record Prototype(
            String stableId,
            String currentPolicyMechanism,
            String targetPolicyReference,
            String defaultSystem,
            String requestSystemOverride,
            List<String> advisors,
            Map<String, String> toolLineage) {

        public Prototype {
            advisors = List.copyOf(advisors);
            toolLineage = Map.copyOf(toolLineage);
        }

        public List<String> resolvedTools() {
            return toolLineage.keySet().stream().sorted().toList();
        }
    }

    private static final List<String> MEMORY = List.of(
            "LongTermMemoryAdvisor", "EpisodicMemoryAdvisor", "ReadOnlyChatMemoryAdvisor");
    private static final List<String> MEMORY_WITH_RAG = List.of(
            "LongTermMemoryAdvisor", "EpisodicMemoryAdvisor", "RagAnswerAdvisor", "ReadOnlyChatMemoryAdvisor");

    private static final List<Prototype> PROTOTYPES = List.of(
            p("Fixed-NORMAL", "legacy: unfiltered", "ALL", MEMORY_WITH_RAG,
                    tools("resident_lookup", "resident", "dynamic_fetch", "dynamic")),
            p("Fixed-ANSWERNOW-OFF", "finalize branch: tools not attached", "NONE", MEMORY,
                    tools()),
            p("Fixed-ANSWERNOW-ON", "finalize-tools branch: resident + resolved dynamic", "BUSINESS_ONLY", MEMORY,
                    tools("resident_lookup", "resident", "dynamic_fetch", "dynamic")),
            p("Fixed-STEER", "inherits current unfiltered step", "ALL", MEMORY,
                    tools("resident_lookup", "resident", "dynamic_fetch", "dynamic")),
            p("Auto-S1", "legacy non-exec gate disabled", "EVALUATION_GATE", MEMORY_WITH_RAG,
                    tools("resident_lookup", "resident", "request_tool", "meta")),
            p("Auto-S2", "legacy: unfiltered execution", "ALL", MEMORY,
                    tools("resident_lookup", "resident", "dynamic_fetch", "dynamic", "ask_user", "meta", "request_tool", "meta")),
            p("Auto-S3", "legacy non-exec gate disabled", "DISCOVERY_ONLY", MEMORY,
                    tools("resident_lookup", "resident", "request_tool", "meta")),
            p("Auto-S4", "normal/answer-now branch decides attachment", "NONE|BUSINESS_ONLY", MEMORY,
                    tools()),
            p("Flow-S1", "legacy non-exec gate disabled", "EVALUATION_GATE", MEMORY_WITH_RAG,
                    tools("resident_lookup", "resident", "request_tool", "meta")),
            p("Flow-S2", "legacy non-exec gate disabled", "EVALUATION_GATE", MEMORY,
                    tools("resident_lookup", "resident", "request_tool", "meta")),
            new Prototype("Flow-DAG", "request system override; legacy unfiltered", "ALL",
                    "DB executor system", "SUB_STEP_EXECUTOR_SYSTEM", MEMORY,
                    tools("resident_lookup", "resident", "dynamic_fetch", "dynamic")),
            p("Flow-SYNTH", "legacy misclassified exec; unfiltered", "NONE", MEMORY,
                    tools("resident_lookup", "resident", "dynamic_fetch", "dynamic"))
    );

    private AsIsClientFixtureFactory() {}

    public static List<Prototype> prototypes() {
        return PROTOTYPES;
    }

    public static ChatClient client(Prototype prototype, CapturingChatModel model) {
        ChatClient.Builder builder = ChatClient.builder(model).defaultSystem(prototype.defaultSystem());
        if (!prototype.toolLineage().isEmpty()) {
            builder.defaultToolCallbacks(prototype.resolvedTools().stream()
                    .map(StubToolCallback::new).toArray(ToolCallback[]::new));
        }
        return builder.build();
    }

    public static OpenAiChatOptions requestOptions(Prototype prototype) {
        return OpenAiChatOptions.builder().maxTokens(321).build();
    }

    public static AssemblyTrace trace(Prototype prototype, String runId) {
        return new AssemblyTrace(prototype.stableId(), runId,
                prototype.currentPolicyMechanism(), prototype.targetPolicyReference(),
                prototype.advisors(), prototype.toolLineage(), prototype.resolvedTools());
    }

    private static Prototype p(String id, String current, String target, List<String> advisors,
                               Map<String, String> tools) {
        return new Prototype(id, current, target, "DB default system for " + id, null, advisors, tools);
    }

    private static Map<String, String> tools(String... nameAndSource) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAndSource.length; i += 2) {
            result.put(nameAndSource[i], nameAndSource[i + 1]);
        }
        return result;
    }

    private static final class StubToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        private StubToolCallback(String name) {
            this.definition = ToolDefinition.builder().name(name).description("fixture " + name)
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { return "fixture-result"; }
    }
}
