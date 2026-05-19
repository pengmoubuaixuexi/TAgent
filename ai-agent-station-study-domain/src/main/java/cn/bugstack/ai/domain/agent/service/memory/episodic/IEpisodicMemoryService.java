package cn.bugstack.ai.domain.agent.service.memory.episodic;

import java.util.List;

/**
 * 跨会话摘要记忆服务（P2.1 Episodic Memory）。
 * <p>
 * 与 ChatMemory 的区别：ChatMemory 存的是"本次对话的消息流"，
 * Episodic Memory 存的是"历史会话的 1-2 句摘要"，新会话开场注入帮助模型理解用户上下文。
 * <p>
 * 与 Long-Term Memory 的区别：LTM 存的是"用户级事实/偏好"（可检索、可衰减），
 * Episodic 存的是"某次会话讲了什么"（纯文本摘要、时间序、轻量）。
 */
public interface IEpisodicMemoryService {

    /**
     * 保存一次会话的摘要。
     * @param userId   用户维度
     * @param tenantId 租户维度
     * @param sessionId 来源对话 sessionId
     * @param topic    主题/意图分类（可选）
     * @param summary  1-2 句摘要文本（≤512 字符）
     */
    void save(String userId, String tenantId, String sessionId, String topic, String summary);

    /**
     * @return 返回用户最近 N 次的 session 摘要文本（按时间倒序），新会话开场拼到 prompt
     */
    List<String> getRecent(String userId, int topN);

    /**
     * @return 返回用户最近 N 次且在 withinDays 天内的会话摘要
     */
    List<String> getRecentWithinDays(String userId, int topN, int withinDays);

    /**
     * 清理旧记录，只保留最近 keepCount 条。
     */
    void trim(String userId, int keepCount);

    /**
     * 覆盖式 upsert：同一 session 已有摘要则整体覆盖，否则新增。
     * summary 参数就是由 LLM 重新摘要后的完整文本，不再做字符串拼接。
     * @param lastSummarizedMsgCount 摘要写入时 ChatMemory 中消息总数，用于"每 2 轮"节流
     */
    void upsert(String userId, String tenantId, String sessionId, String topic, String summary, Integer lastSummarizedMsgCount);

    /**
     * 查指定 session 的摘要（跨会话注入：当前会话段落用）
     */
    String findBySessionId(String sessionId);

    /**
     * 查指定 session 上次摘要时的消息总数，用于"每 N 条消息"节流。
     * @return 上次摘要时的消息数；session 无记录时返回 -1
     */
    int getLastSummarizedMsgCount(String sessionId);

    /**
     * 查用户最近 K 个 OTHER session 的摘要（排除当前 sessionId），5 天内。
     * 用于跨会话注入"最近聊过的其他话题"段落。
     */
    List<String> getOtherSessions(String userId, String currentSessionId, int k, int withinDays);

    /**
     * @deprecated 用 {@link #upsert(String, String, String, String, String, Integer)} 代替
     */
    @Deprecated
    default void upsert(String userId, String tenantId, String sessionId, String topic, String summary) {
        upsert(userId, tenantId, sessionId, topic, summary, null);
    }
}
