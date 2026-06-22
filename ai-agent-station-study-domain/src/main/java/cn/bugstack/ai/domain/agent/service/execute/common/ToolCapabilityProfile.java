package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * P1-A1：按调用阶段声明的工具权限 profile。
 * <p>2026-06-22 用户启用：早期<b>分析/规划</b>阶段（Auto Step1 / Flow Step1/Step2）改为允许 ask_user，
 * 即 {@link #EARLY_STAGE_DEFAULT} 从 DISCOVERY_ONLY 提升为 {@link #INTERACTIVE_META}（ask_user + request_tool）。
 * 配合全局开关 {@code agent.user-input.enabled=true}。<b>质检步（AUTO_STEP3_QUALITY）不走本默认、显式保持 DISCOVERY_ONLY</b>
 * —— 质检自评不阻塞问用户。</p>
 */
public enum ToolCapabilityProfile {
    NONE(EnumSet.noneOf(ToolCapability.class)),
    DISCOVERY_ONLY(EnumSet.of(ToolCapability.REQUEST_TOOL)),
    INTERACTIVE_META(EnumSet.of(ToolCapability.ASK_USER, ToolCapability.REQUEST_TOOL)),
    BUSINESS_ONLY(EnumSet.of(ToolCapability.BUSINESS_TOOL)),
    ALL(EnumSet.allOf(ToolCapability.class));

    /** 早期分析/规划阶段默认（2026-06-22 用户启用 ask_user）；禁止在各调用点散写另一套早期阶段默认值。质检步不引用本默认。 */
    public static final ToolCapabilityProfile EARLY_STAGE_DEFAULT = INTERACTIVE_META;

    private final Set<ToolCapability> capabilities;

    ToolCapabilityProfile(EnumSet<ToolCapability> capabilities) {
        this.capabilities = Collections.unmodifiableSet(capabilities);
    }

    public boolean allows(ToolCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public Set<ToolCapability> capabilities() {
        return capabilities;
    }
}
