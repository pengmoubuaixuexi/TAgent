package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 长期记忆 topic 分类法（单一事实源）。
 * <p>
 * 历史上这套规则在两处各写了一份且不一致：{@code LongTermMemoryService.isCumulativeTopic}
 * （中文 V041 词表，正确）与 {@code LlmMemoryConflictResolver.isSingularTopic}（残留英文词表）。
 * 中文 topic（画像:/计划:/情况:）在后者匹配不到任何英文关键字 → 被误判成「累加」→ 单值槽位
 * 永远 KEEP_BOTH → 画像被重复污染（张伟×2、职业×3）。统一到这里，杜绝再漂移。
 * <p>
 * 受控词表（V041，对齐 {@code LongTermMemoryAdvisor.EXTRACTION_PROMPT}）：
 * <ul>
 *   <li><b>单值（新值覆盖旧值）</b>：画像:/计划:/情况:（兼容旧 fact:/decision:）。
 *       语义同 MemGPT/Letta 的 core-memory block —— 同一 topic 只保留最新值。</li>
 *   <li><b>累加（不同 topic 共存）</b>：技能:/偏好:（兼容旧 skill:/preference:）及一切未知 topic，
 *       默认累加避免误删。</li>
 * </ul>
 * 注意：去重键始终是完整 topic 串（类别:主体）。技能:Java 与 技能:Python 是不同 topic 天然共存；
 * 技能:Java 与 技能:Java 是同一 topic，仍应收敛成一条（抽取契约：「同一主题只输出一条最新事实」）。
 */
public final class MemoryTopics {

    private MemoryTopics() {
    }

    /** 累加性主题：不同 topic 可并存，同 topic 仍取最新。未知主题默认累加（防误删）。 */
    public static boolean isCumulative(String topic) {
        if (topic == null || topic.isBlank()) return true;
        String t = topic.trim();
        if (t.startsWith("画像") || t.startsWith("计划") || t.startsWith("情况")) return false;
        String lower = t.toLowerCase();
        if (lower.startsWith("fact") || lower.startsWith("decision")) return false;
        // 技能:/偏好:/skill:/preference: 及一切未知主题 → 累加
        return true;
    }

    /** 单值主题：同一 topic 同时只应有一个值，新值覆盖旧值。 */
    public static boolean isSingular(String topic) {
        return !isCumulative(topic);
    }
}
