package cn.bugstack.ai.domain.agent.service.memory.conflict;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * LLM 驱动的记忆冲突解决器（P2.1 10.3）。
 * <p>
 * 用 router-small 判断新旧记忆冲突是否需要覆盖/合并/跳过。
 * LLM 失败时默认 KEEP_BOTH（不丢数据）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.memory.conflict", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmMemoryConflictResolver implements IMemoryConflictResolver {

    private static final String CONFLICT_PROMPT = """
            You are a memory conflict resolver. Given a topic, new memory, and existing memories,
            decide how to handle the new information.

            IMPORTANT: Most memories are ADDITIVE, not contradictory. Two skills (e.g., "knows Python"
            and "knows Java") are both true — the user simply has multiple skills. Only OVERWRITE when
            the new info makes the old info definitively FALSE.

            Examples — CUMULATIVE topics (skill:* / decision:*):
            - skill:Java: "knows Python" + "knows Java" → KEEP_BOTH (user knows both)
            - skill:Database: "uses MySQL" + "uses PostgreSQL" → KEEP_BOTH (user uses both)
            - skill:Framework: "knows Spring Boot" + "specializes in Spring Security" → MERGE

            Examples — SINGULAR topics (fact:role / fact:location / fact:company / preference:*):
            - fact:role: "is a Python engineer" + "is a Java engineer" → OVERWRITE (changed role)
            - fact:role: "is a junior developer" + "is now a senior developer" → OVERWRITE (promotion)
            - fact:location: "lives in Beijing" + "moved to Shanghai" → OVERWRITE (relocated)
            - fact:company: "works at Company A" + "joined Company B" → OVERWRITE (changed jobs)
            - preference:answer_style: "prefers long answers" + "wants short answers" → OVERWRITE

            CRITICAL — Topic type determines the rule:
            - skill:* and decision:* are CUMULATIVE (many can coexist). Default to KEEP_BOTH.
              Example: skill:Java + skill:Python → KEEP_BOTH (user knows both).
            - fact:role, fact:location, fact:company are SINGULAR (only one at a time). OVERWRITE
              when the new value differs. Example: "is a Python engineer" → "is a Java engineer" → OVERWRITE.
            - preference:* is SINGULAR. OVERWRITE when user preference clearly changed.
            - Other fact:* topics: judge by content — if additive, KEEP_BOTH; if contradictory, OVERWRITE.
            - Only SKIP if new info is verbatim identical to existing.

            Topic: %s
            New: %s
            Existing:
            %s

            Reply with only one word: OVERWRITE, MERGE, KEEP_BOTH, or SKIP.""";
    // router-small is assembled by RouterPoolConfig
    @Autowired(required = false)
    @Qualifier("aiClient_router-small")
    private ChatClient chatClient;

    @Override
    public Decision resolve(String topic, String newContent, List<String> existingContents) {
        if (existingContents == null || existingContents.isEmpty()) {
            return Decision.KEEP_BOTH;
        }
        if (chatClient == null) {
            Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
            log.debug("memory.conflict: no router-small ChatClient, default {}", fallback);
            return fallback;
        }

        StringBuilder existingBlock = new StringBuilder();
        for (int i = 0; i < Math.min(existingContents.size(), 3); i++) {
            String s = existingContents.get(i);
            if (s != null && !s.isBlank()) {
                existingBlock.append("- ").append(s.length() > 200 ? s.substring(0, 200) : s).append("\n");
            }
        }
        if (existingBlock.isEmpty()) return Decision.KEEP_BOTH;

        String prompt = String.format(CONFLICT_PROMPT,
                topic != null ? topic : "general",
                newContent.length() > 200 ? newContent.substring(0, 200) : newContent,
                existingBlock.toString());

        String raw;
        try {
            raw = chatClient.prompt(new Prompt(prompt)).call().content();
        } catch (Exception e) {
            Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
            log.debug("memory.conflict LLM call failed, fallback {}: {}", fallback, e.getMessage());
            return fallback;
        }

        if (raw == null || raw.isBlank()) {
            Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
            return fallback;
        }

        String upper = raw.trim().toUpperCase();
        for (Decision d : Decision.values()) {
            if (upper.startsWith(d.name())) return d;
        }
        Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
        log.debug("memory.conflict unknown response '{}', fallback {}", upper, fallback);
        return fallback;
    }

    /** 唯一性主题：同一时间只有一个值成立，变更时应该覆盖而非保留两份 */
    static boolean isSingularTopic(String topic) {
        if (topic == null) return false;
        String t = topic.toLowerCase();
        // 带前缀的精确匹配
        if (t.startsWith("preference")) return true;
        if (t.startsWith("fact:role") || t.startsWith("fact:location")
                || t.startsWith("fact:company") || t.startsWith("fact:name")) return true;
        // 无前缀的常见唯一性主题
        return t.equals("role") || t.equals("location") || t.equals("company")
                || t.equals("name") || t.equals("full_name") || t.equals("username")
                || t.equals("job") || t.equals("occupation") || t.equals("position")
                || t.equals("title") || t.equals("employer") || t.equals("address")
                || t.contains("_role") || t.contains("_location") || t.contains("_name");
    }
}
