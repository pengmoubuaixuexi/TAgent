package cn.bugstack.ai.domain.agent.service.execute.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * T11 (F5) Reflexion critique 结构化：Step3 评审失败时生成的结构化诊断。
 * <p>
 * <b>字段语义</b>（按 Codex 第 29 轮约定，evidence/suggestion 不能混）：
 * <ul>
 *   <li>{@code type}：问题归类，见 {@link CritiqueType}，永不为 null（fallback 时为 {@link CritiqueType#OTHER}）。</li>
 *   <li>{@code evidence}：<b>为什么这么判断</b>，引用上下文/工具结果中的具体片段或缺失项。</li>
 *   <li>{@code suggestion}：<b>下一步怎么修</b>，给 Step2 的可执行修复指示（不要再现错误本身）。</li>
 *   <li>{@code rawText}：原始 critique 字符串。正常路径置 null；JSON 解析失败时填充，
 *       <b>绝不丢信息</b>，Step2 仍可消费旧字符串路径。</li>
 * </ul>
 * <p>
 * <b>不变式</b>：这条数据只能用于增强 Reflexion，不能成为必须成功的链路。Step2 消费时即使全字段为 null
 * 或仅有 rawText，也应保持向后兼容（回退到原 critique 字符串拼接 prompt）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CritiqueRecord {

    private CritiqueType type;

    private String evidence;

    private String suggestion;

    /** JSON 解析失败时保留的原始 critique 字符串，正常路径为 null。 */
    private String rawText;

    /** fallback 工厂：JSON 解析失败时使用，保留原文不丢信息。 */
    public static CritiqueRecord fallback(String rawText) {
        return CritiqueRecord.builder()
                .type(CritiqueType.OTHER)
                .rawText(rawText)
                .build();
    }
}
