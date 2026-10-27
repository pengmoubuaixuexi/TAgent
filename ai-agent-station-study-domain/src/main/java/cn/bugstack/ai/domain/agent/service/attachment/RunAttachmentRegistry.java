package cn.bugstack.ai.domain.agent.service.attachment;

import cn.bugstack.ai.domain.agent.model.entity.ChatFileRef;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Attachments of each running task, keyed by run id.
 *
 * <p>Filled when the run is admitted and read by the prompt advisor and read_attachment
 * on every model request. Entries expire after two idle hours, which also covers a Flow
 * plan waiting for review; a resumed plan registers again from the database.</p>
 */
@Component
public class RunAttachmentRegistry {

    public record Scope(String runId, String userId, String sessionId, List<ChatFileRef> current,
                        List<ChatFileRef> sessionFiles) {
        public boolean readable() {
            return (current != null && !current.isEmpty()) || sessionFiles.stream()
                    .anyMatch(ref -> ChatFileRef.STATUS_ACTIVE.equals(ref.getStatus()));
        }
    }

    private final Cache<String, Scope> runs = CacheBuilder.newBuilder()
            .expireAfterAccess(Duration.ofHours(2))
            // Previews and small inline texts only; bounded so a burst of runs cannot exhaust the heap.
            .maximumWeight(48L * 1024 * 1024)
            .weigher((String runId, Scope scope) -> weight(scope))
            .build();

    /** Runs without any attachment in the message or the session are not registered. */
    public void register(String runId, String userId, String sessionId, List<ChatFileRef> current,
                         List<ChatFileRef> sessionFiles) {
        if (blank(runId) || blank(userId)) return;
        List<ChatFileRef> files = current == null ? List.of() : List.copyOf(current);
        List<ChatFileRef> index = sessionFiles == null ? List.of() : sessionFiles.stream()
                .filter(Objects::nonNull)
                .map(ref -> ref.toBuilder().inlineText(null).previewText(null).summary(null).build()).toList();
        if (files.isEmpty() && index.isEmpty()) {
            runs.invalidate(runId);
            return;
        }
        runs.put(runId, new Scope(runId, userId, sessionId, files, index));
    }

    public Scope find(String runId, String userId) {
        if (blank(runId) || blank(userId)) return null;
        Scope scope = runs.getIfPresent(runId);
        return scope != null && Objects.equals(scope.userId(), userId) ? scope : null;
    }

    /** Uses the request's ToolContext identity; MDC is not reliable on Reactor workers. */
    public Scope find(Map<String, Object> toolContext) {
        if (toolContext == null) return null;
        Scope scope = find(text(toolContext.get("agent.run_id")), text(toolContext.get("userId")));
        return scope != null && Objects.equals(scope.sessionId(), text(toolContext.get("sessionId"))) ? scope : null;
    }

    public boolean readable(Map<String, Object> toolContext) {
        Scope scope = find(toolContext);
        return scope != null && scope.readable();
    }

    public void clear(String runId) {
        if (!blank(runId)) runs.invalidate(runId);
    }

    private static int weight(Scope scope) {
        long chars = 256;
        if (scope.current() != null) {
            for (ChatFileRef ref : scope.current()) {
                chars += 512L + length(ref.getPreviewText()) + length(ref.getInlineText()) + length(ref.getSummary());
            }
        }
        for (ChatFileRef ref : scope.sessionFiles()) {
            chars += 512L + length(ref.getFileName());
        }
        return (int) Math.min(Integer.MAX_VALUE, chars * 2);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
