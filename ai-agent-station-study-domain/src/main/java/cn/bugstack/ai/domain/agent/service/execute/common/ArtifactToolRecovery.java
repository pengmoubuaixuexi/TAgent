package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.ai.model.tool.ToolCallingChatOptions;
import java.util.HashMap;

/** Request-local recovery, shared only with recursive copies of these model-call options. */
final class ArtifactToolRecovery {
    static final String CONTEXT_KEY = "agent.artifact_recovery";
    private int failedRounds;
    private boolean blocked;

    static ArtifactToolRecovery find(ToolCallingChatOptions options) {
        Object value = options == null || options.getToolContext() == null ? null : options.getToolContext().get(CONTEXT_KEY);
        return value instanceof ArtifactToolRecovery state ? state : null;
    }

    static ArtifactToolRecovery get(ToolCallingChatOptions options) {
        ArtifactToolRecovery state = find(options);
        if (state != null) return state;
        state = new ArtifactToolRecovery();
        var context = new HashMap<String, Object>();
        if (options.getToolContext() != null) context.putAll(options.getToolContext());
        context.put(CONTEXT_KEY, state);
        options.setToolContext(context);
        return state;
    }

    synchronized int failed() { failedRounds++; if (failedRounds >= 2) blocked = true; return failedRounds; }
    synchronized boolean blocked() { return blocked; }
    synchronized void succeeded() { if (!blocked) failedRounds = 0; }
}
