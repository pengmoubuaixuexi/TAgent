package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 意图类目（P0.1.1 Intent Router）。
 * <p>
 * 五类划分对应路线图的 Intent → Strategy 映射：
 * <ul>
 *   <li>{@link #CHAT}：闲聊 → fixed 单跳</li>
 *   <li>{@link #QA}：知识库问答 → fixed + RAG</li>
 *   <li>{@link #TOOL}：需要调用外部工具 → flow（带规划 + 工具）</li>
 *   <li>{@link #PLAN}：复杂多步任务 → auto（4-step 自治）</li>
 *   <li>{@link #SENSITIVE}：敏感/破坏性操作 → human_in_loop</li>
 *   <li>{@link #UNKNOWN}：分类失败/低置信度 → 回退当前 agent 自身策略</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum IntentCategoryEnumVO {

    CHAT("chat", "闲聊"),
    QA("qa", "知识库问答"),
    TOOL("tool", "工具调用"),
    PLAN("plan", "多步规划"),
    SENSITIVE("sensitive", "敏感操作"),
    UNKNOWN("unknown", "未识别");

    private final String code;
    private final String desc;

    public static IntentCategoryEnumVO fromCode(String code) {
        if (code == null) return UNKNOWN;
        return Arrays.stream(values())
                .filter(e -> e.code.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
