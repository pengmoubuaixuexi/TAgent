package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryRecall;

/**
 * One agent turn should see a stable long-term-memory recall set.
 * <p>
 * Auto/Flow can invoke several clients in one user request. Without this snapshot, each step
 * performs its own semantic recall and can inject duplicated or drifting memory evidence.
 */
@Component
public class LongTermMemoryTurnSnapshot {

    private static final int MAX_ENTRIES = 100_000;

    private final Map<String, List<String>> snapshots = new ConcurrentHashMap<>();
    private final Map<String, List<LongTermMemoryRecall>> detailedSnapshots = new ConcurrentHashMap<>();

    public List<String> getOrLoad(String sessionId, String userId, Supplier<List<String>> loader) {
        if (isBlank(sessionId) || isBlank(userId)) {
            List<String> loaded = loader.get();
            return loaded == null ? Collections.emptyList() : loaded;
        }
        if (snapshots.size() >= MAX_ENTRIES && !snapshots.containsKey(key(sessionId, userId))) {
            List<String> loaded = loader.get();
            return loaded == null ? Collections.emptyList() : loaded;
        }
        return snapshots.computeIfAbsent(key(sessionId, userId), ignored -> immutableCopy(loader.get()));
    }

    public void clear(String sessionId, String userId) {
        if (isBlank(sessionId) || isBlank(userId)) return;
        snapshots.remove(key(sessionId, userId));
        detailedSnapshots.remove(key(sessionId, userId));
    }

    public void clearSession(String sessionId) {
        if (isBlank(sessionId)) return;
        String prefix = sessionId + "::";
        snapshots.keySet().removeIf(key -> key.startsWith(prefix));
        detailedSnapshots.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public List<LongTermMemoryRecall> getOrLoadDetailed(String sessionId, String userId,
                                                         Supplier<List<LongTermMemoryRecall>> loader) {
        if (isBlank(sessionId) || isBlank(userId)) return immutableDetailedCopy(loader.get());
        if (detailedSnapshots.size() >= MAX_ENTRIES && !detailedSnapshots.containsKey(key(sessionId, userId))) {
            return immutableDetailedCopy(loader.get());
        }
        return detailedSnapshots.computeIfAbsent(key(sessionId, userId), ignored -> immutableDetailedCopy(loader.get()));
    }

    int size() {
        return snapshots.size();
    }

    private static List<String> immutableCopy(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    private static List<LongTermMemoryRecall> immutableDetailedCopy(List<LongTermMemoryRecall> recalls) {
        if (recalls == null || recalls.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(recalls));
    }

    private static String key(String sessionId, String userId) {
        return sessionId + "::" + userId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
