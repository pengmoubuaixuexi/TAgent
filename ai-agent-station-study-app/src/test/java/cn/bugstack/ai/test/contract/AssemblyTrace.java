package cn.bugstack.ai.test.contract;

import java.util.List;
import java.util.Map;

/** Provenance that is not observable at the final ChatModel boundary. */
public record AssemblyTrace(
        String stableId,
        String runId,
        String currentPolicyMechanism,
        String targetPolicyReference,
        List<String> advisors,
        Map<String, String> toolLineage,
        List<String> resolvedToolNames) {

    public AssemblyTrace {
        advisors = advisors == null ? List.of() : List.copyOf(advisors);
        toolLineage = toolLineage == null ? Map.of() : Map.copyOf(toolLineage);
        resolvedToolNames = resolvedToolNames == null ? List.of() : List.copyOf(resolvedToolNames);
    }
}
