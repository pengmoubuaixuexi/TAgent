package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import org.slf4j.MDC;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Server-populated advisor context survives Reactor hops; conflicting identities fail closed. */
final class TrustedAdvisorIdentity {
    private TrustedAdvisorIdentity() {}

    static String userId(Map<String, Object> context) {
        Set<String> candidates = candidates(context);
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    static boolean conflictsWith(String userId, Map<String, Object> context) {
        return candidates(context).stream().anyMatch(candidate -> !candidate.equals(userId));
    }

    static String sessionId(Map<String, Object> context) {
        String[] parts = conversation(context);
        return parts != null ? parts[2] : MDC.get("sessionId");
    }

    static String tenantId(Map<String, Object> context) {
        String[] parts = conversation(context);
        String tenant = parts != null ? parts[0] : MDC.get("tenantId");
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private static Set<String> candidates(Map<String, Object> context) {
        Set<String> identities = new HashSet<>();
        add(identities, MDC.get("userId"));
        if (context != null) {
            add(identities, context.get("userId"));
            add(identities, context.get("user_id"));
            String[] parts = conversation(context);
            if (parts != null) add(identities, parts[1]);
        }
        return identities;
    }

    private static String[] conversation(Map<String, Object> context) {
        Object value = context == null ? null : context.get(ChatMemory.CONVERSATION_ID);
        if (!(value instanceof String id)) return null;
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) return null;
        return parts;
    }

    private static void add(Set<String> identities, Object value) {
        if (value instanceof String text && !text.isBlank()) identities.add(text.trim());
    }
}
