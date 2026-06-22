package cn.bugstack.ai.domain.agent.model.valobj.enums;

/**
 * P0-B2b-Step3：Auto 链「质量交付状态」——§35.2 四层模型里独立于 verdict（解析结果）与 route（路由动作）的<b>交付状态</b>层。
 *
 * <p>由 Step3 enforce 唯一写入；Step4 只读它决定对用户的简短说明（不泄内部 prompt/错误）。
 * <ul>
 *   <li>{@link #NOT_ASSESSED}：未经质检（Step1 历史判完 / AnswerNow）——不显示为异常；</li>
 *   <li>{@link #VERIFIED_PASS}：质检明确通过；</li>
 *   <li>{@link #VERIFIED_FAIL}：质检明确未通过（到上限仍 FAIL）；</li>
 *   <li>{@link #VERIFIED_OPTIMIZE}：质检判可优化（到上限仍 OPTIMIZE）；</li>
 *   <li>{@link #QUALITY_NOT_VERIFIED}：空/冲突/repair 后仍无法取得权威裁决——交付须明示"未能完成质量验证"。</li>
 * </ul>
 */
public enum QualityVerificationStatus {
    NOT_ASSESSED,
    VERIFIED_PASS,
    VERIFIED_FAIL,
    VERIFIED_OPTIMIZE,
    QUALITY_NOT_VERIFIED
}
