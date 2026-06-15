package cn.bugstack.ai.domain.agent.service.execute.common;

/**
 * T11 (F5) Reflexion critique 结构化：critique 的问题类型。
 * <p>
 * Step3 评审失败时输出该类型，Step2 按类型走差异化的修复 prompt。
 * <p>
 * 兼容性约束：枚举只能新增，不应删/改既有值；模型可能输出新类型，未知值统一归到 {@link #OTHER}，
 * 避免因新增/拼写差异导致 parser 抛出中断主流程。
 */
public enum CritiqueType {

    /** 应调用工具却没调用（典型：需要查询/写入/搜索却空想答案）。 */
    MISSING_TOOL_CALL,

    /** 工具调用了但参数错（名/类型/范围/缺必填）。 */
    WRONG_PARAM,

    /** 推理链自相矛盾或与上下文/工具结果冲突。 */
    LOGIC_INCONSISTENT,

    /** 输出无证据（编造事实、引用不存在的来源）。 */
    HALLUCINATION,

    /** JSON 解析成功但 type 字段无法识别 / 缺失 / 落在白名单之外。 */
    OTHER;

    /**
     * 容错解析：null / 空 / 未知值均返 {@link #OTHER}，永不抛异常。
     * 不区分大小写，前后空白自动 trim。
     */
    public static CritiqueType fromString(String raw) {
        if (raw == null) return OTHER;
        String s = raw.trim();
        if (s.isEmpty()) return OTHER;
        try {
            return CritiqueType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            return OTHER;
        }
    }
}
