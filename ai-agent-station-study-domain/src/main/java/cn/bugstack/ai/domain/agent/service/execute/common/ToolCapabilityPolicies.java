package cn.bugstack.ai.domain.agent.service.execute.common;

/**
 * P1-A1 调用原型 → policy ceiling 的唯一真相源。
 * 调用节点只能引用这里的命名常量/函数，禁止依据 clientType、中文 stepLabel 或 prompt 文案自行猜测。
 */
public final class ToolCapabilityPolicies {

    private ToolCapabilityPolicies() {
    }

    public static final ToolCapabilityProfile FIXED_NORMAL = ToolCapabilityProfile.ALL;
    public static final ToolCapabilityProfile FIXED_STEER = ToolCapabilityProfile.ALL;

    public static final ToolCapabilityProfile AUTO_STEP1_ANALYSIS = ToolCapabilityProfile.EARLY_STAGE_DEFAULT;
    public static final ToolCapabilityProfile AUTO_STEP2_EXECUTION = ToolCapabilityProfile.ALL;
    public static final ToolCapabilityProfile AUTO_STEP3_QUALITY = ToolCapabilityProfile.DISCOVERY_ONLY;
    public static final ToolCapabilityProfile AUTO_STEP4_SUMMARY = ToolCapabilityProfile.ARTIFACT_ONLY;

    // FLOW reads the executor's authorized metadata; loading is deferred until an actual execution step.
    public static final ToolCapabilityProfile FLOW_STEP1_TOOL_ANALYSIS = ToolCapabilityProfile.CLARIFICATION_ONLY;
    public static final ToolCapabilityProfile FLOW_STEP2_PLANNING = ToolCapabilityProfile.CLARIFICATION_ONLY;
    public static final ToolCapabilityProfile FLOW_DAG_EXECUTION = ToolCapabilityProfile.ALL;
    public static final ToolCapabilityProfile FLOW_FINAL_SYNTHESIS = ToolCapabilityProfile.ARTIFACT_ONLY;

    /** finalize-tools 只是产品开关；实际没有挂上工具时仍必须 NONE。 */
    public static ToolCapabilityProfile answerNow(boolean toolsActuallyAttached) {
        return toolsActuallyAttached ? ToolCapabilityProfile.BUSINESS_ONLY : ToolCapabilityProfile.NONE;
    }
}
