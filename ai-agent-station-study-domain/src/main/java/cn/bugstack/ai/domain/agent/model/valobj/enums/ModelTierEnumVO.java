package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模型档位（P0.1.3 Model Router）。
 * <p>
 * 给每个 ai_client_model 贴一个档位标签，让 step 节点按任务难度选模型；目标是省 token 成本。
 * <p>
 * 经验阈值（截至 2026-04）：
 * <ul>
 *   <li>{@link #SMALL}：分类 / 解析 / 摘要 / 改写——haiku / mini / nano / 3.5-turbo</li>
 *   <li>{@link #MEDIUM}：常规对话 / QA / Step 评审——sonnet / 4o-base</li>
 *   <li>{@link #LARGE}：深度推理 / 复杂规划 / Reflexion——opus / gpt-4-turbo</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum ModelTierEnumVO {

    SMALL("small", "轻量任务（分类/摘要/改写）"),
    MEDIUM("medium", "常规对话与 QA"),
    LARGE("large", "深度推理与复杂规划"),
    ;

    private final String code;
    private final String desc;

    public static ModelTierEnumVO fromCode(String code) {
        if (code == null) return MEDIUM;
        for (ModelTierEnumVO t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) return t;
        }
        return MEDIUM;
    }
}
